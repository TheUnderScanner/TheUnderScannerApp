"""Tests for usbcopy: lsblk parsing, copy planning (FAT32 / free space), rsync progress,
and the background transfer job (with a fake rsync).

    python -m unittest discover -s UnderScannerAPI/tests -v
"""
import json
import sys
import tempfile
import time
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import usbcopy as uc  # noqa: E402

LSBLK = (HERE / "fixtures" / "lsblk_usb.json").read_text(encoding="utf-8")


def mounted(text: str, mountpoint: str, free: int) -> str:
    """The fixture with sda1 mounted at [mountpoint]."""
    d = json.loads(text)
    part = d["blockdevices"][1]["children"][0]
    part["mountpoint"], part["fsavail"] = mountpoint, str(free)
    return json.dumps(d)


class DevicesTest(unittest.TestCase):
    def test_only_usb_filesystems(self):
        devs = uc.parse_lsblk(LSBLK)
        self.assertEqual([d.device for d in devs], ["/dev/sda1"])     # not loop9, not nvme
        d = devs[0]
        self.assertEqual((d.disk, d.label, d.vendor, d.model, d.fstype, d.fsver),
                         ("/dev/sda", "ESD-USB", "SanDisk", "Cruzer Force", "vfat", "FAT32"))
        self.assertIsNone(d.mountpoint)
        self.assertIsNone(d.free)
        self.assertTrue(d.is_fat32)
        self.assertEqual(d.as_dict()["max_file"], uc.FAT32_MAX_FILE)
        self.assertEqual(d.name, "ESD-USB")

    def test_mounted_has_free_space(self):
        d = uc.parse_lsblk(mounted(LSBLK, "/media/orin4slam/ESD-USB", 123))[0]
        self.assertEqual((d.mountpoint, d.free), ("/media/orin4slam/ESD-USB", 123))

    def test_partitionless_stick_and_garbage(self):
        d = json.loads(LSBLK)
        disk = d["blockdevices"][1]
        disk.pop("children")
        disk["fstype"], disk["label"] = "exfat", "NOPART"
        devs = uc.parse_lsblk(json.dumps(d))
        self.assertEqual([(x.device, x.disk) for x in devs], [("/dev/sda", "/dev/sda")])
        self.assertEqual(uc.parse_lsblk("not json"), [])

    def test_udisks_errors_are_readable(self):
        class R:
            returncode, stdout = 1, ""
            stderr = "Error mounting /dev/sda1: GDBus.Error:org.freedesktop.UDisks2.Error.NotAuthorized: x"
        dev = uc.parse_lsblk(LSBLK)[0]
        with self.assertRaises(uc.UsbError) as cm:
            uc.mount(dev, run=lambda *a, **k: R())
        self.assertIn("polkit", str(cm.exception))


class PlanTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.data = root / "data"
        self.stick = root / "stick"
        self.stick.mkdir()
        (self.data / "bags" / "S").mkdir(parents=True)
        (self.data / "bags" / "S" / "S_0.db3").write_bytes(b"x" * 1000)
        (self.data / "bags" / "S" / "metadata.yaml").write_bytes(b"m" * 10)
        (self.data / "maps").mkdir()
        (self.data / "maps" / "S.pcd").write_bytes(b"p" * 200)
        self.arts = [self.data / "bags" / "S", self.data / "maps" / "S.pcd",
                     self.data / "notes" / "S.json"]          # notes absent: skipped

    def tearDown(self):
        self.tmp.cleanup()

    def dev(self, free=10**9, fstype="exfat"):
        d = uc.parse_lsblk(mounted(LSBLK, str(self.stick), free))[0]
        d.fstype = fstype
        return d

    def test_plan_counts_and_paths(self):
        p = uc.plan(self.data, self.arts, self.dev())
        self.assertTrue(p.ok, p.problems)
        self.assertEqual(p.rels, ["bags/S", "maps/S.pcd"])
        self.assertEqual((p.bytes_total, p.bytes_needed, p.files_total), (1210, 1210, 3))
        self.assertFalse(p.already)

    def test_already_copied_files_are_not_needed(self):
        (self.stick / "underscanner" / "maps").mkdir(parents=True)
        (self.stick / "underscanner" / "maps" / "S.pcd").write_bytes(b"p" * 200)
        p = uc.plan(self.data, self.arts, self.dev())
        self.assertEqual(p.bytes_needed, 1010)
        self.assertFalse(uc.is_on_stick(self.data, self.arts, str(self.stick)))
        (self.stick / "underscanner" / "bags" / "S").mkdir(parents=True)
        (self.stick / "underscanner" / "bags" / "S" / "S_0.db3").write_bytes(b"x" * 1000)
        (self.stick / "underscanner" / "bags" / "S" / "metadata.yaml").write_bytes(b"m" * 10)
        self.assertTrue(uc.plan(self.data, self.arts, self.dev()).already)
        self.assertTrue(uc.is_on_stick(self.data, self.arts, str(self.stick)))

    def test_not_enough_space(self):
        p = uc.plan(self.data, self.arts, self.dev(free=500))
        self.assertFalse(p.ok)
        self.assertIn("pas assez de place", p.problems[0])

    def test_fat32_file_too_big(self):
        old = uc.FAT32_MAX_FILE
        uc.FAT32_MAX_FILE = 999
        try:
            p = uc.plan(self.data, self.arts, self.dev(fstype="vfat"))
            self.assertEqual(len(p.problems), 1)
            self.assertIn("bags/S/S_0.db3", p.problems[0])
            self.assertIn("FAT32", p.problems[0])
            self.assertTrue(uc.plan(self.data, self.arts, self.dev(fstype="exfat")).ok)
        finally:
            uc.FAT32_MAX_FILE = old

    def test_not_mounted(self):
        d = uc.parse_lsblk(LSBLK)[0]
        self.assertIn("pas montée", uc.plan(self.data, self.arts, d).problems[0])

    def test_rsync_argv(self):
        argv = uc.rsync_argv(Path("/data/underscanner"), ["bags/S", "maps/S.pcd"],
                             "/media/orin4slam/ESD-USB")
        self.assertEqual(argv[-3:], ["/data/underscanner/./bags/S", "/data/underscanner/./maps/S.pcd",
                                     "/media/orin4slam/ESD-USB/underscanner/"])
        self.assertIn("-rtR", argv)
        self.assertIn("--modify-window=1", argv)
        self.assertNotIn("-a", argv)


class ProgressTest(unittest.TestCase):
    def test_parse(self):
        self.assertEqual(uc.parse_progress("  1,234,567  45%   12.34MB/s    0:01:23 (xfr#1, to-chk=3/7)"),
                         (1234567, 45))
        self.assertEqual(uc.parse_progress("          0   0%    0.00kB/s    0:00:00"), (0, 0))
        self.assertIsNone(uc.parse_progress("sending incremental file list"))
        self.assertIsNone(uc.parse_progress("bags/S/S_0.db3"))


FAKE_RSYNC = r"""
import sys, time
for b, p in [(0, 0), (500, 41), (1000, 83), (1210, 100)]:
    sys.stdout.write(f"{b:>12,} {p:>3}%  1.00MB/s    0:00:01\r"); sys.stdout.flush(); time.sleep(0.05)
sys.stdout.write("\n"); sys.exit(int(sys.argv[1]))
"""
SLOW_RSYNC = "import time; time.sleep(30)"


class TransferTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dev = uc.parse_lsblk(mounted(LSBLK, self.tmp.name, 10**9))[0]
        self.plan = uc.CopyPlan([], ["bags/S"], 1210, 1210, 3, False)
        self.synced = []

    def tearDown(self):
        self.tmp.cleanup()

    def wait(self, t, states=("done", "error", "cancelled")):
        for _ in range(200):
            if t.snapshot()["state"] in states:
                return t.snapshot()
            time.sleep(0.05)
        self.fail(f"stuck in {t.snapshot()}")

    def test_success_reports_progress_and_syncs(self):
        t = uc.Transfer(sync=lambda: self.synced.append(1))
        t.start("S", self.dev, self.plan, [sys.executable, "-c", FAKE_RSYNC, "0"])
        self.assertTrue(t.active)
        with self.assertRaises(uc.UsbError):
            t.start("S2", self.dev, self.plan, [sys.executable, "-c", FAKE_RSYNC, "0"])
        s = self.wait(t)
        self.assertEqual(s["state"], "done", s)
        self.assertEqual((s["percent"], s["bytes_done"], s["scan"], s["label"]), (100, 1210, "S", "ESD-USB"))
        self.assertEqual(self.synced, [1])
        self.assertTrue((Path(self.tmp.name) / "underscanner").is_dir())
        self.assertFalse(any(k.startswith("_") for k in s))
        self.assertFalse(t.active)

    def test_rsync_failure(self):
        t = uc.Transfer(sync=lambda: None)
        t.start("S", self.dev, self.plan, [sys.executable, "-c", FAKE_RSYNC, "23"])
        s = self.wait(t)
        self.assertEqual(s["state"], "error")
        self.assertIn("code 23", s["error"])
        self.assertIn("clé retirée", s["error"])

    def test_cancel(self):
        t = uc.Transfer(sync=lambda: None)
        t.start("S", self.dev, self.plan, [sys.executable, "-c", SLOW_RSYNC])
        self.assertTrue(t.cancel())
        self.assertEqual(self.wait(t)["state"], "cancelled")
        self.assertFalse(t.cancel())


if __name__ == "__main__":
    unittest.main()
