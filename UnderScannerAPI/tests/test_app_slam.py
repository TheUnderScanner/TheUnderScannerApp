"""End-to-end test of app.py's SLAM endpoints + scan start/stop, with ROS and the
processes faked. Needs fastapi, httpx, numpy, pyyaml (skipped otherwise):

    pip install pyyaml fastapi httpx numpy
    python -m unittest discover -s UnderScannerAPI/tests -v
"""
import dataclasses
import hashlib
import importlib
import json
import os
import sys
import tempfile
import time
import types
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

try:
    import fastapi  # noqa: F401
    import httpx    # noqa: F401
    import numpy    # noqa: F401
    from fastapi.testclient import TestClient
    HAVE_DEPS = True
except ImportError:
    HAVE_DEPS = False

FIXTURE = HERE / "fixtures" / "fastlio2_mid360.yaml"


def _stub_ros():
    """Minimal stand-ins for the ROS modules app.py imports."""
    class Node:
        def __init__(self, name): self._name = name
        def create_subscription(self, *a, **k): return object()
        def destroy_subscription(self, s): pass
        def get_logger(self): return types.SimpleNamespace(info=lambda *a, **k: None)

    class Executor:
        def add_node(self, n): pass
        def spin(self): pass

    mods = {
        "rclpy": types.SimpleNamespace(init=lambda: None),
        "rclpy.node": types.SimpleNamespace(Node=Node),
        "rclpy.executors": types.SimpleNamespace(MultiThreadedExecutor=Executor),
        "nav_msgs": types.SimpleNamespace(), "nav_msgs.msg": types.SimpleNamespace(Odometry=object),
        "sensor_msgs": types.SimpleNamespace(),
        "sensor_msgs.msg": types.SimpleNamespace(PointCloud2=object),
        "sensor_msgs_py": types.SimpleNamespace(point_cloud2=None),
    }
    sys.modules.update(mods)


class FakeProc:
    def __init__(self, cmd, rc=None):
        self.cmd, self.rc = cmd, rc
    def poll(self):
        return self.rc


@unittest.skipUnless(HAVE_DEPS, "needs fastapi, httpx, numpy")
class AppSlamTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.data = Path(cls.tmp.name) / "data"
        os.environ["UNDERSCANNER_DATA"] = str(cls.data)
        os.environ["FASTLIO_CONFIG"] = str(FIXTURE)
        _stub_ros()
        sys.modules.pop("app", None)
        cls.app = app = importlib.import_module("app")

        # No warm-up waits, and processes are fakes.
        app.DRIVER_WARMUP_S = app.BAG_WARMUP_S = 0
        fast = dataclasses.replace(app.ALGOS[0], warmup_s=0)
        app.ALGOS[0] = fast
        app.ALGO_BY_ID[fast.id] = fast
        app.PROFILES.algos[fast.id] = fast
        cls.spawned = []
        cls.fail_stage = None

        def spawn(name, cmd):
            cls.spawned.append((name, list(cmd)))
            app.S.procs[name] = FakeProc(cmd, rc=1 if name == cls.fail_stage else None)

        def stop_proc(name, sig=None, timeout=8):
            app.S.procs.pop(name, None)

        def run(cmd, timeout=None):       # the map save: FAST-LIO writes map_file_path
            app.LASTSCAN_PCD.write_bytes(b"PCD" * 100)

        app.spawn, app.stop_proc = spawn, stop_proc
        app.subprocess = types.SimpleNamespace(run=run, Popen=None)
        cls.client = TestClient(app.app)
        cls.client.__enter__()               # runs startup: factory snapshot

    @classmethod
    def tearDownClass(cls):
        cls.client.__exit__(None, None, None)
        for f in cls.data.rglob("*.yaml"):
            f.chmod(0o644)
        cls.tmp.cleanup()

    def setUp(self):
        self.spawned.clear()
        type(self).fail_stage = None

    # ---- helpers ----
    def start(self, **body):
        body.setdefault("client_date", "2026-11-08")
        body.setdefault("location", "Test")
        return self.client.post("/scan/start", json=body)

    def wait_not_starting(self):
        for _ in range(100):
            if not self.app.S.starting:
                return
            time.sleep(0.02)
        self.fail("S.starting stuck")

    # ---- tests (run in name order) ----
    def test_1_profiles_crud(self):
        c = self.client
        r = c.get("/slam/algos").json()
        self.assertEqual(r["default"], "fastlio2")
        self.assertEqual(r["algos"][0]["default_profile"], "usine")
        self.assertEqual([p["name"] for p in c.get("/slam/fastlio2/profiles").json()["profiles"]],
                         ["usine"])
        self.assertEqual(c.get("/slam/fastlio2/profiles/usine").text, FIXTURE.read_text("utf-8"))
        self.assertEqual(c.put("/slam/fastlio2/profiles/usine", content="x").status_code, 403)

        self.assertEqual(c.post("/slam/fastlio2/profiles/usine/copy",
                                json={"to": "voxel-0.3"}).status_code, 200)
        text = c.get("/slam/fastlio2/profiles/voxel-0.3").text
        voxel = (text.replace("filter_size_surf: 0.5", "filter_size_surf: 0.3")
                     .replace("filter_size_map: 0.5", "filter_size_map: 0.3"))
        r = c.put("/slam/fastlio2/profiles/voxel-0.3", content=voxel.encode(),
                  headers={"Content-Type": "text/plain"})
        self.assertEqual(r.status_code, 200, r.text)

        bad = voxel.replace("blind: 0.5", "blind: oui")
        r = c.put("/slam/fastlio2/profiles/voxel-0.3", content=bad.encode())
        self.assertEqual(r.status_code, 422)
        issue = r.json()["detail"]["issues"][0]
        self.assertEqual(issue["line"], 22)
        self.assertIn("réel attendu", issue["msg"])

        r = c.put("/slam/defaults", json={"profile": "voxel-0.3"}).json()
        self.assertEqual(r["profiles"]["fastlio2"], "voxel-0.3")
        self.assertEqual(c.delete("/slam/fastlio2/profiles/voxel-0.3").status_code, 409)
        self.assertIn(c.get("/slam/fastlio2/profiles/..").status_code, (400, 404))
        self.assertEqual(c.get("/slam/glim/profiles").status_code, 404)

    def test_2_validate(self):
        c = self.client
        r = c.post("/slam/fastlio2/validate", json={"overrides": "det_range: 60"}).json()
        self.assertTrue(r["ok"], r)
        self.assertEqual(r["overrides"], {"mapping.det_range": 60.0})
        self.assertIn("filter_size_map: 0.3", r["effective"])          # default profile used
        r = c.post("/slam/fastlio2/validate", json={"overrides": "ok_key: 1\nblind: x"}).json()
        self.assertFalse(r["ok"])
        self.assertEqual([(i["where"], i["line"]) for i in r["issues"]],
                         [("overrides", 1), ("overrides", 2)])
        r = c.post("/slam/fastlio2/validate", json={"yaml": "a: [1"}).json()
        self.assertFalse(r["ok"])
        self.assertEqual(r["issues"][0]["where"], "yaml")

    def test_3_bad_overrides_never_spawn(self):
        r = self.start(overrides="pcd_save_en: false")
        self.assertEqual(r.status_code, 422)
        self.assertIn("imposée", r.json()["detail"]["issues"][0]["msg"])
        self.assertEqual(self.spawned, [])
        self.assertFalse(self.app.S.starting)

    def test_4_start_runs_on_archived_config_then_stop(self):
        app, c = self.app, self.client
        r = self.start(overrides="mapping.det_range: 60")
        self.assertEqual(r.status_code, 200, r.text)
        name = r.json()["scan"]
        self.assertEqual(name, "2026-11-08_Test_01")
        self.assertEqual(r.json()["slam"], {"algo": "fastlio2", "profile": "voxel-0.3",
                                            "overrides": {"mapping.det_range": 60.0}})
        cfg = app.CONFIGS / f"{name}.yaml"
        meta = json.loads((app.CONFIGS / f"{name}.json").read_text("utf-8"))
        text = cfg.read_text("utf-8")
        self.assertEqual(meta["sha256"], hashlib.sha256(text.encode()).hexdigest())
        self.assertTrue(text.startswith("# UnderScanner - config effective : algo fastlio2, profil voxel-0.3"))
        self.assertIn("det_range:     60.0", text)
        self.assertIn("filter_size_map: 0.3", text)
        self.assertIn(f'map_file_path: "{app.LASTSCAN_PCD.as_posix()}"', text)
        slam_cmd = dict(self.spawned)["slam"]
        self.assertIn(f"config_path:={app.CONFIGS.as_posix()}", slam_cmd)
        self.assertIn(f"config_file:={name}.yaml", slam_cmd)
        self.assertEqual(c.get("/scan/status").json()["slam"]["profile"], "voxel-0.3")
        self.assertEqual(self.start().status_code, 409)

        (app.BAGS / name).mkdir(parents=True)        # what ros2 bag record would create
        r = c.post("/scan/stop").json()
        self.assertEqual(r["slam"]["profile"], "voxel-0.3")
        self.assertTrue((app.MAPS / f"{name}.pcd").exists())
        self.assertEqual(cfg.read_text("utf-8"), text)           # not overwritten at stop
        row = next(s for s in c.get("/scans").json()["scans"] if s["name"] == name)
        self.assertEqual(row["slam"]["sha256"], meta["sha256"])

        r = c.post(f"/scans/{name}/rename", json={"location": "Grotte"}).json()
        new = r["name"]
        self.assertTrue((app.CONFIGS / f"{new}.json").exists())
        self.assertFalse((app.CONFIGS / f"{name}.json").exists())

    def test_5_failed_launch_cleans_up(self):
        type(self).fail_stage = "slam"
        r = self.start(location="Panne")
        self.assertEqual(r.status_code, 500)
        self.assertIn("slam", r.json()["detail"])
        self.wait_not_starting()
        self.assertFalse((self.app.CONFIGS / "2026-11-08_Panne_01.yaml").exists())
        self.assertFalse((self.app.CONFIGS / "2026-11-08_Panne_01.json").exists())

    def test_6_legacy_scan_has_no_meta(self):
        app = self.app
        (app.CONFIGS / "2026-01-01_Old_01.yaml").write_text("x: 1", encoding="utf-8")
        row = next(s for s in self.client.get("/scans").json()["scans"]
                   if s["name"] == "2026-01-01_Old_01")
        self.assertIsNone(row["slam"])
        self.assertTrue(row["config"]["present"])

    # ---- USB stick (lsblk / udisksctl / rsync faked) ----
    def _stick(self, free):
        mp = Path(self.tmp.name) / "stick"
        mp.mkdir(exist_ok=True)
        return self.app.usb.UsbDevice(device="/dev/sda1", disk="/dev/sda", label="ESD-USB",
                                      model="Cruzer Force", vendor="SanDisk", size=32 * 10**9,
                                      fstype="exfat", fsver=None, mountpoint=str(mp), free=free)

    def test_7_usb_copy_flow(self):
        from unittest import mock
        app, c = self.app, self.client
        scan = "2026-11-08_Grotte_01"                 # created + renamed by test_4
        fake_rsync = [sys.executable, "-c",
                      "import sys,time\n"
                      "for p in (10,60,100):\n"
                      "  sys.stdout.write(f'  {p*3:,} {p}%  1.00MB/s  0:00:01\\r'); sys.stdout.flush(); time.sleep(0.15)\n"]
        ejected, mounted = [], []

        with mock.patch.object(app.usb, "list_devices", return_value=[]):
            self.assertEqual(c.get("/usb/devices").json()["devices"], [])
            r = c.post(f"/scans/{scan}/usb", json={})
            self.assertEqual(r.status_code, 409)
            self.assertIn("aucune clé", r.json()["detail"])
            self.assertIsNone(next(s for s in c.get("/scans").json()["scans"] if s["name"] == scan)["usb"])

        stick = self._stick(free=10**9)
        unmounted = dataclasses.replace(stick, mountpoint=None, free=None)
        with mock.patch.object(app.usb, "list_devices", return_value=[unmounted]), \
             mock.patch.object(app.usb, "mount", side_effect=lambda d: mounted.append(d.device)):
            self.assertEqual(c.post("/usb/mount", json={"device": "/dev/sda1"}).status_code, 200)
            self.assertEqual(mounted, ["/dev/sda1"])
            self.assertEqual(c.post("/usb/mount", json={"device": "/dev/nvme0n1p1"}).status_code, 404)

        with mock.patch.object(app.usb, "list_devices", return_value=[stick]), \
             mock.patch.object(app.usb, "rsync_argv", return_value=fake_rsync), \
             mock.patch.object(app.usb, "eject", side_effect=lambda d: ejected.append(d.device)):
            plan = c.get(f"/scans/{scan}/usb").json()
            self.assertTrue(plan["ok"], plan)
            self.assertGreater(plan["bytes_total"], 0)
            self.assertEqual(plan["device"]["name"], "ESD-USB")
            row = next(s for s in c.get("/scans").json()["scans"] if s["name"] == scan)
            self.assertEqual(row["usb"], {"present": False, "stick": "ESD-USB"})

            r = c.post(f"/scans/{scan}/usb", json={})
            self.assertEqual(r.status_code, 200, r.text)
            self.assertEqual(r.json()["state"], "copying")
            # guards while copying
            self.assertEqual(c.post(f"/scans/{scan}/usb", json={}).status_code, 409)
            r = self.start(location="PendantCopie")
            self.assertEqual(r.status_code, 423)
            self.assertIn("transfert USB", r.json()["detail"])
            self.assertEqual(self.spawned, [])
            self.assertEqual(c.post("/system/shutdown").status_code, 423)
            self.assertEqual(c.post("/usb/eject", json={}).status_code, 409)

            for _ in range(100):
                s = c.get("/usb/transfer").json()
                if s["state"] not in ("copying", "syncing"):
                    break
                time.sleep(0.05)
            self.assertEqual((s["state"], s["percent"], s["scan"]), ("done", 100, scan), s)
            self.assertEqual(c.post("/usb/eject", json={}).json()["name"], "ESD-USB")
            self.assertEqual(ejected, ["/dev/sda1"])

        with mock.patch.object(app.usb, "list_devices", return_value=[self._stick(free=10)]):
            r = c.post(f"/scans/{scan}/usb", json={})
            self.assertEqual(r.status_code, 422)
            self.assertIn("pas assez de place", r.json()["detail"]["msg"])


if __name__ == "__main__":
    unittest.main()
