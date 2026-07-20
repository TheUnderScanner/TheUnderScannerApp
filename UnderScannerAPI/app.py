#!/usr/bin/env python3
"""Underscanner backend — Phase 1 (files) + Phase 2 (scan control + live preview).

Run inside a sourced ROS 2 environment:
    source /opt/ros/humble/setup.bash
    source ~/ws_livox/install/setup.bash
    source ~/ws_fastlio/install/setup.bash
    source ~/underscanner-api/venv/bin/activate
    python3 app.py

------------------------------------------------------------------------------
LIVE PREVIEW BINARY FRAME FORMAT  ("USC1")
------------------------------------------------------------------------------
Each cloud frame pushed over the WebSocket is a single binary blob:

    Header (8 bytes):
        char[4]  magic         = "USC1"           (ASCII, also the version tag)
        uint32   point_count   (little-endian)

    Then point_count records, 16 bytes each (little-endian), interleaved:
        offset  0 : float32  x
        offset  4 : float32  y
        offset  8 : float32  z
        offset 12 : uint8    reflectivity   (0-255, Livox intensity)
        offset 13 : uint8    tag            (Livox tag byte, 0 if unavailable)
        offset 14 : uint8    line           (laser id,       0 if unavailable)
        offset 15 : uint8    _reserved      (0, keeps 4-byte alignment)

Notes:
  * The 16-byte stride is 4-byte aligned so the trailing 4 uint8 can be read
    as a normalized vec4 attribute in an OpenGL vertex buffer.
  * reflectivity is Livox reflectivity: 0-150 => diffuse 0-100%,
    151-255 => (near) total reflection (wet / specular / metal).
  * tag / line are populated only if the source topic carries them. Stock
    FAST-LIO2 /cloud_registered usually exposes x,y,z,intensity only, so tag
    and line will be 0 unless your FAST-LIO fork forwards them. The backend
    logs the fields it actually sees on the first cloud (see PreviewNode).
  * Pose messages are still sent as JSON text (unchanged).
  * GET /preview/format returns this layout as JSON for the client to verify.

------------------------------------------------------------------------------
PER-SCAN HEALTH LOG  (health/{scan}.jsonl)
------------------------------------------------------------------------------
JSON Lines. Line 1 is a header, every following line is one sample:

    {"type":"header","v":1,"scan":...,"started_at":...,"interval_s":5.0,
     "static":{"cpu_cores":6,"cpu_max_mhz":1728,"mem_total_mb":7607,
               "power_mode":"MAXN_SUPER","zones":[...],"rails":[...]}}
    {"t":5.0,"cpu":41.2,"cpu_f":1420,"gpu":3.0,"mem":1024,
     "t_cpu":52.1,"t_gpu":51.0,"t_soc":50.5,"t_tj":52.5,
     "w_in":8.21,"w_cpu_gpu":3.10,"w_soc":1.22,"disk_free":186.2,
     "cloud_hz":9.8,"odom_hz":10.1,"odom_ok":true,"cloud_ok":true,
     "pts":4210,"traj_n":812}

  * "t" is seconds since the scan started; one sample every HEALTH_WRITE_S.
  * EVERY field except "t" is optional — a key is absent when the board doesn't
    expose that sensor or the value wasn't readable for that sample. Read the
    available series from the header's "static", never from a hard-coded list.
  * JSONL, not a JSON array, so a scan cut short by a dead battery still parses
    up to its last complete line. The file is append-only through a handle held
    open for the whole scan: write cost does not grow with scan length.
  * GET /scans/{name}/health serves the file; GET /system/health returns the
    latest live sample for the control-room / active-scan HUD.
"""
import os, re, glob, json, socket, struct, time, signal, threading, datetime, subprocess, asyncio
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, PlainTextResponse, Response
from fastapi.middleware.cors import CORSMiddleware

import numpy as np
import rclpy
from rclpy.node import Node
from rclpy.executors import MultiThreadedExecutor
from nav_msgs.msg import Odometry
from sensor_msgs.msg import PointCloud2
from sensor_msgs_py import point_cloud2

# ----------------------------- config -----------------------------
DATA_ROOT = Path(os.environ.get("UNDERSCANNER_DATA", "/data/underscanner"))
BAGS, MAPS, CONFIGS, NOTES = (DATA_ROOT / d for d in ("bags", "maps", "configs", "notes"))
TRAJ = DATA_ROOT / "trajectories"          # per-scan LiDAR path (/Odometry) files
HEALTH = DATA_ROOT / "health"              # per-scan JSONL system-health logs
SCAN_STATE = DATA_ROOT / ".current_scan"
FASTLIO_CONFIG = Path(os.path.expanduser(
    os.environ.get("FASTLIO_CONFIG", "~/ws_fastlio/src/FAST_LIO_ROS2/config/mid360.yaml")))
LASTSCAN_PCD = DATA_ROOT / ".lastscan.pcd"

# >>> VERIFY all of these against your working setup <<<
LIDAR_LAUNCH     = ["ros2", "launch", "livox_ros_driver2", "msg_MID360_launch.py"]
SLAM_LAUNCH_BASE = ["ros2", "launch", "fast_lio", "mapping.launch.py"]
SLAM_RVIZ_ARG    = "rviz:=false"          # or "" if you made a headless launch file
MAP_SAVE_CMD     = ["ros2", "service", "call", "/map_save", "std_srvs/srv/Trigger"]
TOPIC_LIDAR, TOPIC_IMU = "/livox/lidar", "/livox/imu"
TOPIC_CLOUD, TOPIC_ODOM = "/cloud_registered", "/Odometry"

VOXEL    = 0.10     # preview downsample size (m); larger = sparser/lighter
CLOUD_HZ = 4        # preview cloud frames/sec to the phone
POSE_HZ  = 10       # pose updates/sec to the phone
# \w is Unicode-aware in Python 3, so accented locations (Résidence, Gouffre-d'Été)
# are accepted. It still excludes "/", "\" and ".", so this remains the path-traversal
# guard that safe() relies on.
NAME_RE  = re.compile(r"^\d{4}-\d{2}-\d{2}_[\w\-]+_\d{2}$")

# ---- live preview binary protocol ----
CLOUD_MAGIC  = b"USC1"          # 4-byte magic; the "1" is the version
CLOUD_STRIDE = 16              # bytes per point

# ---- trajectory binary protocol ----
# Header: char[4] "UTR1" + uint32 count (LE); then count * (x,y,z) float32 (12 B each, LE).
TRAJ_MAGIC     = b"UTR1"
TRAJ_MIN_STEP  = 0.05          # min move (m) between recorded path points (dedup)
# interleaved record: xyz float32 + reflectivity/tag/line/pad uint8
CLOUD_DTYPE = np.dtype([
    ("x", "<f4"), ("y", "<f4"), ("z", "<f4"),
    ("refl", "u1"), ("tag", "u1"), ("line", "u1"), ("_pad", "u1"),
])
assert CLOUD_DTYPE.itemsize == CLOUD_STRIDE

# ---- system health telemetry ----
# Everything is read from unprivileged sysfs/procfs. Paths that vary by board or
# JetPack version are probed ONCE at startup and cached: on the Orin Nano the
# cv0/cv1/cv2 thermal zones exist but their temp node returns EAGAIN (sensor not
# wired), so re-probing every second would spam the journal forever.
THERMAL_GLOB    = "/sys/class/thermal/thermal_zone*"
GPU_LOAD_PATHS  = ("/sys/devices/platform/bus@0/17000000.gpu/load",)
INA_GLOB        = "/sys/bus/i2c/drivers/ina3221/*/hwmon/hwmon*"
NVPMODEL_STATUS = "/var/lib/nvpmodel/status"
NVPMODEL_CONF   = "/etc/nvpmodel.conf"

HEALTH_SAMPLE_S = 1.0   # internal sample period (CPU% is a delta — needs two reads)
HEALTH_WRITE_S  = 5.0   # one JSONL line per this many seconds while a scan runs
HEALTH_FLUSH_S  = 10.0  # flush cadence; never fsync per sample (slow on eMMC/NVMe)

# Thermal zones we keep, mapped to short names. The three soc* zones collapse onto
# one series ("soc") and are reported as their max — they track within ~1 degC.
ZONE_MAP = {"cpu-thermal": "cpu", "gpu-thermal": "gpu", "tj-thermal": "tj",
            "soc0-thermal": "soc", "soc1-thermal": "soc", "soc2-thermal": "soc"}
# INA3221 rail labels -> short names. This kernel exposes bus voltage (mV) and
# current (mA) but no power node, so watts are computed as V*I.
RAIL_MAP = {"VDD_IN": "in", "VDD_CPU_GPU_CV": "cpu_gpu", "VDD_SOC": "soc"}

app = FastAPI(title="Underscanner Backend", version="2.2")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

# ----------------------------- helpers -----------------------------
def human(n: float) -> str:
    for u in ("B", "KiB", "MiB", "GiB", "TiB"):
        if n < 1024: return f"{n:.1f} {u}"
        n /= 1024
    return f"{n:.1f} PiB"

def dir_size(p: Path) -> int:
    if p.is_file(): return p.stat().st_size
    if p.is_dir():  return sum(f.stat().st_size for f in p.rglob("*") if f.is_file())
    return 0

def safe(name: str) -> str:
    if not NAME_RE.match(name): raise HTTPException(400, "invalid scan name")
    return name

def iso(ts): return datetime.datetime.fromtimestamp(ts).isoformat(timespec="seconds")

def collect_scan_names():
    names = set()
    if BAGS.is_dir():    names |= {p.name for p in BAGS.iterdir() if p.is_dir()}
    if MAPS.is_dir():    names |= {p.stem for p in MAPS.glob("*.pcd")}
    if CONFIGS.is_dir(): names |= {p.stem for p in CONFIGS.glob("*.yaml")}
    if NOTES.is_dir():   names |= {p.stem for p in NOTES.glob("*.json")}
    if HEALTH.is_dir():  names |= {p.stem for p in HEALTH.glob("*.jsonl")}
    return sorted(names, reverse=True)

def clean_location(location: str) -> str:
    """Make a user-typed location safe to embed in a scan name, keeping accents.

    The location becomes part of the scan name, and every read endpoint runs that
    name through safe()/NAME_RE — so anything not cleaned here produces a scan the
    API can create but never serve (HTTP 400 on pcd/config/notes). Letters and
    digits survive as typed ("Résidence" stays "Résidence"); spaces, punctuation
    and path separators collapse to "-". "_" is folded too since it separates the
    date/location/run fields.
    """
    s = re.sub(r"[\W_]+", "-", location).strip("-")
    return s or "apartment"

def make_scan_name(location: str | None) -> str:
    date = datetime.date.today().isoformat()
    if not location:                       # sticky: reuse last location
        if SCAN_STATE.exists():
            parts = SCAN_STATE.read_text().strip().split("_")
            location = "_".join(parts[1:-1]) if len(parts) >= 3 else "apartment"
        else:
            location = "apartment"
    location = clean_location(location)
    last = 0
    if BAGS.is_dir():
        for p in BAGS.glob(f"{date}_{location}_*"):
            m = re.search(r"_(\d+)$", p.name)
            if m: last = max(last, int(m.group(1)))
    return f"{date}_{location}_{last + 1:02d}"

def voxel_indices(xyz: np.ndarray, voxel: float) -> np.ndarray:
    """Return indices of one representative point per occupied voxel."""
    if len(xyz) == 0: return np.arange(0)
    keys = np.floor(xyz / voxel).astype(np.int64)
    _, idx = np.unique(keys, axis=0, return_index=True)
    return idx

def pack_cloud(xyz: np.ndarray, refl: np.ndarray,
               tag: np.ndarray, line: np.ndarray) -> bytes:
    """Serialize a cloud into a USC1 binary frame (see module docstring)."""
    n = xyz.shape[0]
    rec = np.zeros(n, dtype=CLOUD_DTYPE)
    rec["x"], rec["y"], rec["z"] = xyz[:, 0], xyz[:, 1], xyz[:, 2]
    rec["refl"] = np.clip(np.rint(refl), 0, 255).astype(np.uint8)
    rec["tag"]  = tag.astype(np.uint8, copy=False)
    rec["line"] = line.astype(np.uint8, copy=False)
    return CLOUD_MAGIC + struct.pack("<I", n) + rec.tobytes()

def pack_trajectory(points) -> bytes:
    """Serialize a list of (x,y,z) path points into a UTR1 binary frame."""
    arr = np.asarray(points, dtype="<f4").reshape(-1, 3)
    return TRAJ_MAGIC + struct.pack("<I", arr.shape[0]) + arr.tobytes()

# ----------------------------- shared state -----------------------------
class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.running = False
        self.scan = None
        self.started_at = None
        self.procs: dict[str, subprocess.Popen] = {}
        self.latest_cloud: bytes | None = None    # packed binary frame
        self.latest_pose: dict | None = None
        self.last_cloud_t = 0.0
        self.last_odom_t = 0.0
        self.trajectory: list[tuple[float, float, float]] = []  # /Odometry path, this scan
        self.traj_last: tuple[float, float, float] | None = None
        # Monotonic message counters; HealthMonitor diffs them into rates (Hz).
        self.cloud_count = 0
        self.odom_count = 0
        self.last_cloud_pts = 0      # points in the most recent (downsampled) frame
S = State()

# ----------------------------- system health -----------------------------
def _read(p, default=None):
    """Read a sysfs/procfs file, tolerating EAGAIN and vanished nodes."""
    try:
        return Path(p).read_text().strip()
    except Exception:
        return default

def _read_int(p):
    try:
        return int(_read(p))
    except (TypeError, ValueError):
        return None

class HealthMonitor:
    """Samples Jetson load / thermal / power and appends them to a per-scan log.

    Sampling (1 Hz) is deliberately decoupled from logging (0.2 Hz): CPU% is a delta
    and needs a short window to mean anything, but a chart only needs a point every
    few seconds. The live endpoint serves the last cached sample, so it never touches
    the filesystem on the request path.

    The log is append-only through a handle held open for the whole scan, so each
    write costs the same whether the scan is one minute or five hours old. It is
    never read back and rewritten. JSONL (not a JSON array) so that a scan cut short
    by a dead battery still parses up to its last complete line.
    """

    def __init__(self):
        self.lock = threading.Lock()
        self.latest: dict = {}
        self._prev_cpu = None            # (busy, total) jiffies
        self._prev_counts = (0, 0, 0.0)  # (cloud_count, odom_count, t)
        self._fh = None
        self._start = 0.0
        self._last_write = 0.0
        self._last_flush = 0.0
        self._probe()

    # ---- one-time hardware probing ----
    def _probe(self):
        self.zones: dict[str, list[str]] = {}
        for z in sorted(glob.glob(THERMAL_GLOB)):
            zt = _read(f"{z}/type")
            if zt not in ZONE_MAP:
                continue
            if _read_int(f"{z}/temp") is None:      # cv*-thermal on Orin Nano: EAGAIN
                continue
            self.zones.setdefault(ZONE_MAP[zt], []).append(f"{z}/temp")

        self.gpu_load = next((p for p in GPU_LOAD_PATHS if _read_int(p) is not None), None)

        self.rails: list[tuple[str, str, str]] = []     # (name, volt path, curr path)
        for h in sorted(glob.glob(INA_GLOB)):
            for i in (1, 2, 3):
                name = RAIL_MAP.get(_read(f"{h}/in{i}_label"))
                if name and _read_int(f"{h}/in{i}_input") is not None:
                    self.rails.append((name, f"{h}/in{i}_input", f"{h}/curr{i}_input"))

        self.cores = os.cpu_count() or 1
        mx = _read_int("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")
        self.cpu_max_mhz = round(mx / 1000) if mx else None
        self.mem_total_mb = round(self._meminfo().get("MemTotal", 0) / 1024) or None
        self.power_mode = self._power_mode()

    @staticmethod
    def _power_mode():
        """Active nvpmodel profile, resolved to its name — without needing sudo."""
        m = re.search(r"pmode:\s*(\d+)", _read(NVPMODEL_STATUS, "") or "")
        if not m:
            return None
        pid = int(m.group(1))
        nm = re.search(rf"POWER_MODEL\s+ID=0*{pid}\s+NAME=(\S+)", _read(NVPMODEL_CONF, "") or "")
        return nm.group(1) if nm else f"mode {pid}"

    # ---- individual metrics ----
    @staticmethod
    def _meminfo() -> dict:
        out = {}
        try:
            with open("/proc/meminfo") as f:
                for line in f:
                    k, _, v = line.partition(":")
                    if k in ("MemTotal", "MemAvailable"):
                        out[k] = float(v.strip().split()[0])     # kB
                        if len(out) == 2:
                            break
        except Exception:
            pass
        return out

    def _cpu_percent(self):
        """Aggregate busy% since the previous sample (None on the very first call)."""
        try:
            with open("/proc/stat") as f:
                parts = f.readline().split()
        except Exception:
            return None
        if len(parts) < 8 or parts[0] != "cpu":
            return None
        v = [int(x) for x in parts[1:8]]
        total = sum(v)
        busy = total - (v[3] + v[4])            # minus idle + iowait
        prev, self._prev_cpu = self._prev_cpu, (busy, total)
        if prev is None:
            return None
        db, dt = busy - prev[0], total - prev[1]
        return round(100.0 * db / dt, 1) if dt > 0 else None

    def _cpu_mhz(self):
        fs = [v for v in (_read_int(f"/sys/devices/system/cpu/cpu{c}/cpufreq/scaling_cur_freq")
                          for c in range(self.cores)) if v]
        return round(sum(fs) / len(fs) / 1000) if fs else None

    def sample(self, now: float) -> dict:
        d = {"cpu": self._cpu_percent(), "cpu_f": self._cpu_mhz()}

        if self.gpu_load:
            g = _read_int(self.gpu_load)
            d["gpu"] = round(g / 10.0, 1) if g is not None else None    # per-mille -> %

        mi = self._meminfo()
        if mi.get("MemTotal"):
            d["mem"] = round((mi["MemTotal"] - mi.get("MemAvailable", 0)) / 1024)   # used MB

        for name, paths in self.zones.items():
            vals = [v for v in (_read_int(p) for p in paths) if v is not None]
            d[f"t_{name}"] = round(max(vals) / 1000.0, 1) if vals else None

        for name, vp, cp in self.rails:
            mv, ma = _read_int(vp), _read_int(cp)
            d[f"w_{name}"] = round(mv * ma / 1e6, 2) if None not in (mv, ma) else None

        try:                                    # statvfs is O(1) — never walk the bag dir
            st = os.statvfs(DATA_ROOT)
            d["disk_free"] = round(st.f_bavail * st.f_frsize / 2**30, 1)            # GiB
        except Exception:
            pass

        with S.lock:
            cc, oc = S.cloud_count, S.odom_count
            d["pts"] = S.last_cloud_pts
            d["traj_n"] = len(S.trajectory)
            d["odom_ok"] = (now - S.last_odom_t) < 2.0
            d["cloud_ok"] = (now - S.last_cloud_t) < 2.0
        pc, po, pt = self._prev_counts
        dt = now - pt
        if pt and dt > 0:
            d["cloud_hz"] = round((cc - pc) / dt, 1)
            d["odom_hz"] = round((oc - po) / dt, 1)
        self._prev_counts = (cc, oc, now)
        return d

    # ---- per-scan log ----
    def open_log(self, name: str, started_at: float):
        self.close_log()
        try:
            HEALTH.mkdir(parents=True, exist_ok=True)
            fh = open(HEALTH / f"{name}.jsonl", "a", buffering=1 << 16)
            # Self-describing header: the client reads axis bounds and the available
            # series from here instead of hard-coding a schema it can't yet know.
            fh.write(json.dumps({
                "type": "header", "v": 1, "scan": name,
                "started_at": iso(started_at), "hostname": socket.gethostname(),
                "interval_s": HEALTH_WRITE_S,
                "static": {"cpu_cores": self.cores, "cpu_max_mhz": self.cpu_max_mhz,
                           "mem_total_mb": self.mem_total_mb, "power_mode": self.power_mode,
                           "zones": sorted(self.zones), "rails": [r[0] for r in self.rails]},
            }) + "\n")
            self._fh, self._start = fh, started_at
            self._last_write = self._last_flush = 0.0
        except Exception:
            self._fh = None

    def close_log(self):
        fh, self._fh = self._fh, None
        if fh:
            try:
                fh.flush(); fh.close()
            except Exception:
                pass

    def _maybe_write(self, snap: dict, now: float):
        if not self._fh or (now - self._last_write) < HEALTH_WRITE_S:
            return
        self._last_write = now
        rec = {"t": round(now - self._start, 1)}
        rec.update({k: v for k, v in snap.items() if v is not None})
        try:
            self._fh.write(json.dumps(rec, separators=(",", ":")) + "\n")
            if (now - self._last_flush) >= HEALTH_FLUSH_S:
                self._fh.flush(); self._last_flush = now
        except Exception:
            pass

    def run(self):
        while True:
            now = time.time()
            try:
                snap = self.sample(now)
                with self.lock:
                    self.latest = dict(snap, at=iso(now))
                self._maybe_write(snap, now)
            except Exception:
                pass
            time.sleep(HEALTH_SAMPLE_S)

H = HealthMonitor()

# ----------------------------- ROS node -----------------------------
class PreviewNode(Node):
    def __init__(self):
        super().__init__("underscanner_preview")
        self._fields_logged = False
        self.create_subscription(PointCloud2, TOPIC_CLOUD, self.on_cloud, 10)
        self.create_subscription(Odometry,   TOPIC_ODOM,  self.on_odom, 10)

    def on_cloud(self, msg):
        present = {f.name for f in msg.fields}
        if not self._fields_logged:
            # one-time: reveals whether tag / line survive to /cloud_registered
            self.get_logger().info(f"{TOPIC_CLOUD} point fields: {sorted(present)}")
            self._fields_logged = True

        if not {"x", "y", "z"} <= present:
            return
        want = [f for f in ("x", "y", "z", "intensity", "tag", "line") if f in present]
        try:
            rec = point_cloud2.read_points(msg, field_names=tuple(want), skip_nans=True)
        except Exception:
            return
        n = len(rec)
        if n == 0:
            return

        xyz  = np.stack([rec["x"], rec["y"], rec["z"]], axis=-1).astype(np.float32)
        refl = rec["intensity"].astype(np.float32) if "intensity" in want else np.zeros(n, np.float32)
        tag  = rec["tag"].astype(np.uint8)  if "tag"  in want else np.zeros(n, np.uint8)
        line = rec["line"].astype(np.uint8) if "line" in want else np.zeros(n, np.uint8)

        idx = voxel_indices(xyz, VOXEL)
        packed = pack_cloud(xyz[idx], refl[idx], tag[idx], line[idx])
        with S.lock:
            S.latest_cloud = packed
            S.last_cloud_t = time.time()
            S.cloud_count += 1
            S.last_cloud_pts = int(len(idx))

    def on_odom(self, msg):
        p, q = msg.pose.pose.position, msg.pose.pose.orientation
        now = time.time()
        with S.lock:
            S.latest_pose = {"type":"pose","x":p.x,"y":p.y,"z":p.z,
                             "qx":q.x,"qy":q.y,"qz":q.z,"qw":q.w,"t":now}
            S.last_odom_t = now
            S.odom_count += 1
            # Accumulate the path while a scan is running, dedup'd by min step.
            if S.running:
                pt = (p.x, p.y, p.z)
                last = S.traj_last
                if last is None or ((pt[0]-last[0])**2 + (pt[1]-last[1])**2 +
                                    (pt[2]-last[2])**2) >= TRAJ_MIN_STEP**2:
                    S.trajectory.append(pt)
                    S.traj_last = pt

# ----------------------------- process control -----------------------------
def spawn(name, cmd):
    S.procs[name] = subprocess.Popen(cmd, start_new_session=True,
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

def stop_proc(name, sig=signal.SIGINT, timeout=8):
    p = S.procs.get(name)
    if not p: return
    try:
        os.killpg(os.getpgid(p.pid), sig)
        p.wait(timeout=timeout)
    except Exception:
        try: os.killpg(os.getpgid(p.pid), signal.SIGKILL)
        except Exception: pass
    S.procs.pop(name, None)

# ============================ FILE API (Phase 1) ============================
@app.get("/status")
def status():
    return {"status":"ready","hostname":socket.gethostname(),
            "time":datetime.datetime.now().isoformat(timespec="seconds"),
            "data_root":str(DATA_ROOT),"version":app.version}

@app.get("/scans")
def list_scans():
    out = []
    for name in collect_scan_names():
        bag, pcd = BAGS / name, MAPS / f"{name}.pcd"
        cfg, note = CONFIGS / f"{name}.yaml", NOTES / f"{name}.json"
        # to be able to search through the text of notes
        note_text = ""
        if note.exists():
            try:
                ndd = json.loads(note.read_text())
                note_text = " ".join(str(v) for k, v in ndd.items() if not k.startswith("_"))
            except Exception:
                pass

        parts = name.split("_"); bs = dir_size(bag); ps = pcd.stat().st_size if pcd.exists() else 0
        traj = TRAJ / f"{name}.traj"
        hlog = HEALTH / f"{name}.jsonl"
        hs = hlog.stat().st_size if hlog.exists() else 0
        out.append({"name":name,"date":parts[0],"location":"_".join(parts[1:-1]),"run":parts[-1],
                    "bag":{"present":bag.exists(),"size_bytes":bs,"size_human":human(bs)},
                    "pcd":{"present":pcd.exists(),"size_bytes":ps,"size_human":human(ps)},
                    "config":{"present":cfg.exists()},"notes":{"present":note.exists(), "text": note_text},
                    "trajectory":{"present":traj.exists()},
                    "health":{"present":hlog.exists(),"size_bytes":hs,"size_human":human(hs)}})
    return {"scans": out}

@app.get("/scans/{name}/pcd")
def download_pcd(name: str):
    safe(name); pcd = MAPS / f"{name}.pcd"
    if not pcd.exists(): raise HTTPException(404, "no pcd for this scan")
    return FileResponse(pcd, media_type="application/octet-stream", filename=f"{name}.pcd")

@app.get("/scans/{name}/trajectory")
def download_trajectory(name: str):
    """UTR1 binary LiDAR path. Serves the saved file, or the in-progress path from memory
    if this scan is currently running. 404 when there is nothing to return."""
    safe(name)
    f = TRAJ / f"{name}.traj"
    if f.exists():
        return FileResponse(f, media_type="application/octet-stream", filename=f"{name}.traj")
    with S.lock:
        data = pack_trajectory(list(S.trajectory)) \
            if (S.running and S.scan == name and S.trajectory) else None
    if data is None:
        raise HTTPException(404, "no trajectory for this scan")
    return Response(content=data, media_type="application/octet-stream")

@app.get("/scans/{name}/health")
def download_health(name: str):
    """Per-scan health log, JSON Lines: one header record then one sample per line."""
    safe(name)
    f = HEALTH / f"{name}.jsonl"
    if not f.exists(): raise HTTPException(404, "no health log for this scan")
    return FileResponse(f, media_type="application/x-ndjson", filename=f"{name}.jsonl")

@app.get("/scans/{name}/config", response_class=PlainTextResponse)
def get_config(name: str):
    safe(name); cfg = CONFIGS / f"{name}.yaml"
    if not cfg.exists(): raise HTTPException(404, "no config snapshot")
    return cfg.read_text()

@app.get("/scans/{name}/notes")
def get_notes(name: str):
    safe(name); f = NOTES / f"{name}.json"
    return json.loads(f.read_text()) if f.exists() else {}

@app.put("/scans/{name}/notes")
async def put_notes(name: str, request: Request):
    safe(name); NOTES.mkdir(parents=True, exist_ok=True)
    data = await request.json(); data["_updated"] = datetime.datetime.now().isoformat(timespec="seconds")
    (NOTES / f"{name}.json").write_text(json.dumps(data, indent=2))
    return {"ok": True}

# ------------------- live preview protocol introspection -------------------
@app.get("/preview/format")
def preview_format():
    """Self-describing spec of the USC1 binary cloud frame for the client."""
    return {
        "magic": "USC1",
        "endianness": "little",
        "header_bytes": 8,
        "header": [
            {"name": "magic", "type": "char[4]", "value": "USC1"},
            {"name": "point_count", "type": "uint32"},
        ],
        "record_stride_bytes": CLOUD_STRIDE,
        "record": [
            {"name": "x", "type": "float32", "offset": 0},
            {"name": "y", "type": "float32", "offset": 4},
            {"name": "z", "type": "float32", "offset": 8},
            {"name": "reflectivity", "type": "uint8", "offset": 12, "range": "0-255",
             "note": "0-150 diffuse 0-100%, 151-255 (near) total reflection / wet / specular"},
            {"name": "tag", "type": "uint8", "offset": 13, "note": "Livox tag byte, 0 if unavailable"},
            {"name": "line", "type": "uint8", "offset": 14, "note": "laser id, 0 if unavailable"},
            {"name": "_reserved", "type": "uint8", "offset": 15},
        ],
    }

# ====================== ORCHESTRATION (Phase 2) ======================
@app.post("/scan/start")
async def scan_start(request: Request):
    with S.lock:
        if S.running: raise HTTPException(409, "a scan is already running")
    location = None
    try:
        body = await request.json(); location = (body or {}).get("location")
    except Exception:
        pass
    for d in (BAGS, MAPS, CONFIGS, NOTES, TRAJ, HEALTH): d.mkdir(parents=True, exist_ok=True)
    name = make_scan_name(location); SCAN_STATE.write_text(name)

    spawn("driver", LIDAR_LAUNCH); time.sleep(3.0)         # let the driver come up
    slam_cmd = list(SLAM_LAUNCH_BASE) #+ [f"map_file_path:={MAPS}/{name}.pcd"]
    if SLAM_RVIZ_ARG: slam_cmd.append(SLAM_RVIZ_ARG)
    spawn("slam", slam_cmd); time.sleep(2.0)               # let SLAM init
    spawn("bag", ["ros2","bag","record",TOPIC_LIDAR,TOPIC_IMU,"-o",str(BAGS / name)])

    with S.lock:
        S.running, S.scan, S.started_at = True, name, time.time()
        S.trajectory, S.traj_last = [], None
        started_at = S.started_at
    H.open_log(name, started_at)
    return {"scan":name,"running":True,"started_at":iso(started_at)}

@app.post("/scan/stop")
def scan_stop():
    with S.lock:
        if not S.running: raise HTTPException(409, "no scan running")
        name = S.scan
    try: subprocess.run(MAP_SAVE_CMD, timeout=30)          # save while SLAM alive
    except Exception: pass

    # wait for the .pcd write to finish before killing SLAM (avoid truncation)
    if LASTSCAN_PCD.exists():
        last = -1
        for _ in range(20):                                # up to ~10s
            sz = LASTSCAN_PCD.stat().st_size
            if sz > 0 and sz == last: break                # size stable two checks running
            last = sz; time.sleep(0.5)

    stop_proc("bag",    signal.SIGINT)                     # flush bag first
    stop_proc("slam",   signal.SIGINT)
    stop_proc("driver", signal.SIGINT)

    # SLAM is dead now, so moving the file is unambiguously safe
    try:
        if LASTSCAN_PCD.exists():
            LASTSCAN_PCD.replace(MAPS / f"{name}.pcd")     # atomic move within /data
    except Exception: pass

    try:
        if FASTLIO_CONFIG.exists():
            (CONFIGS / f"{name}.yaml").write_text(FASTLIO_CONFIG.read_text())
    except Exception: pass

    # Persist the accumulated LiDAR path for the saved-scan viewer.
    try:
        with S.lock:
            pts = list(S.trajectory)
        if pts:
            TRAJ.mkdir(parents=True, exist_ok=True)
            (TRAJ / f"{name}.traj").write_bytes(pack_trajectory(pts))
    except Exception: pass

    H.close_log()                                          # flush + close the health log

    with S.lock:
        S.running, S.scan, S.started_at = False, None, None
        S.latest_cloud, S.latest_pose = None, None
        S.trajectory, S.traj_last = [], None

    pcd, bag = MAPS / f"{name}.pcd", BAGS / name
    return {"scan":name,"running":False,
            "pcd":{"present":pcd.exists(),"size_human":human(dir_size(pcd))},
            "bag":{"present":bag.exists(),"size_human":human(dir_size(bag))}}

@app.get("/scan/status")
def scan_status():
    now = time.time()
    with S.lock:
        return {"running":S.running,"scan":S.scan,
                "started_at":iso(S.started_at) if S.started_at else None,
                "elapsed_s":round(now - S.started_at,1) if S.started_at else 0,
                "odom_ok":(now - S.last_odom_t) < 2.0,
                "cloud_ok":(now - S.last_cloud_t) < 2.0}


# ------------------------------- SYSTEM -------------------------------
@app.get("/system/health")
def system_health():
    """Latest cached telemetry sample — the sampler thread does the I/O, not this handler.

    Keys are omitted when the board doesn't expose them, so the client must treat every
    field as optional (see HealthMonitor._probe).
    """
    with H.lock:
        snap = dict(H.latest)
    snap["static"] = {"cpu_cores": H.cores, "cpu_max_mhz": H.cpu_max_mhz,
                      "mem_total_mb": H.mem_total_mb, "power_mode": H.power_mode,
                      "zones": sorted(H.zones), "rails": [r[0] for r in H.rails]}
    return snap

@app.post("/system/shutdown")
def system_shutdown():
    with S.lock:
        if S.running:
            raise HTTPException(409, "stop the scan before shutting down")
    subprocess.Popen(["sudo", "/usr/sbin/shutdown", "-h", "now"])
    return {"ok": True, "message": "shutting down"}



# ====================== LIVE PREVIEW WEBSOCKET ======================
clients: set[WebSocket] = set()

@app.websocket("/ws/preview")
async def ws_preview(ws: WebSocket):
    await ws.accept(); clients.add(ws)
    try:
        while True: await asyncio.sleep(3600)              # broadcaster does the sending
    except WebSocketDisconnect:
        pass
    finally:
        clients.discard(ws)

async def broadcaster():
    cloud_interval = 1.0 / CLOUD_HZ
    last_cloud_send = 0.0; last_pose_t = 0.0
    while True:
        await asyncio.sleep(1.0 / POSE_HZ)
        if not clients: continue
        now = time.time()
        with S.lock:
            pose, cloud = S.latest_pose, S.latest_cloud
        if pose and pose["t"] != last_pose_t:
            last_pose_t = pose["t"]; txt = json.dumps(pose)
            for ws in list(clients):
                try: await ws.send_text(txt)
                except Exception: clients.discard(ws)
        if cloud and (now - last_cloud_send) >= cloud_interval:
            last_cloud_send = now
            for ws in list(clients):
                try: await ws.send_bytes(cloud)
                except Exception: clients.discard(ws)

@app.on_event("startup")
async def _startup():
    rclpy.init()
    node = PreviewNode()
    exe = MultiThreadedExecutor(); exe.add_node(node)
    threading.Thread(target=exe.spin, daemon=True).start()
    threading.Thread(target=H.run, daemon=True).start()
    asyncio.create_task(broadcaster())

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
