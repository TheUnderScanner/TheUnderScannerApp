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
  * Right after connect the server sends {"type":"hello","accepts":["client_stats"]}.
    Only then may the client send {"type":"client_stats",...} text messages (phone
    render KPIs → ph_* keys of the health log, see below). An older server never
    read the socket, so a client must stay silent without the hello.
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
     "pts":4210,"pts_raw":19840,"traj_n":812,
     "ws_clients":1,"ws_kbps":262.4,"ws_send_ms":1.8,
     "ph_fps":59.8,"ph_frame_max_ms":33.0,"ph_upload_kbps":210.5,"ph_rx_kbps":258.0,
     "ph_chunks":12,"ph_pts":781234,"ph_voxel":0.1}

  * ws_* is the preview link as the Jetson sees it (ws_send_ms = slowest cloud send
    in the sample window; a climbing value is WiFi back-pressure). ph_* are the phone's
    own render KPIs, sent as {"type":"client_stats",...} on /ws/preview after the
    server's {"type":"hello","accepts":["client_stats"]}; absent when no phone is
    watching the live view.

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

import slamcfg as sc      # SLAM algo registry + config profiles (pure Python, unit-tested)
import usbcopy as usb     # USB stick listing / mounting / scan copy (pure Python, unit-tested)

# ----------------------------- config -----------------------------
DATA_ROOT = Path(os.environ.get("UNDERSCANNER_DATA", "/data/underscanner"))
BAGS, MAPS, CONFIGS, NOTES = (DATA_ROOT / d for d in ("bags", "maps", "configs", "notes"))
TRAJ = DATA_ROOT / "trajectories"          # per-scan LiDAR path (/Odometry) files
HEALTH = DATA_ROOT / "health"              # per-scan JSONL system-health logs
SCAN_STATE = DATA_ROOT / ".current_scan"
# FAST-LIO's shipped config. Since the SLAM-profiles work it is only the SOURCE of the
# factory profile (slam/fastlio2/usine.yaml, snapshotted once): scans run on their own
# configs/<scan>.yaml, never on this file.
FASTLIO_CONFIG = Path(os.path.expanduser(
    os.environ.get("FASTLIO_CONFIG", "~/ws_fastlio/src/FAST_LIO_ROS2/config/mid360.yaml")))
LASTSCAN_PCD = DATA_ROOT / ".lastscan.pcd"   # where FAST-LIO's map_save writes (imposed key)
SLAM_ROOT = DATA_ROOT / "slam"               # profiles: slam/defaults.json, slam/<algo>/<profile>.yaml

LIDAR_LAUNCH     = ["ros2", "launch", "livox_ros_driver2", "msg_MID360_launch.py"]
TOPIC_LIDAR, TOPIC_IMU = "/livox/lidar", "/livox/imu"

# ---- SLAM algorithms (see slamcfg.py). scan_start/stop only use the SlamAlgo interface:
# add an algorithm here, not in the orchestration code.
ALGOS = [
    sc.make_fastlio2(factory_source=FASTLIO_CONFIG, pcd_out=LASTSCAN_PCD,
                     lidar_topic=TOPIC_LIDAR, imu_topic=TOPIC_IMU),
]
ALGO_BY_ID = {a.id: a for a in ALGOS}
PROFILES = sc.ProfileStore(SLAM_ROOT, ALGOS, log=lambda m: print(m, flush=True))

USB = usb.Transfer()      # the (single) scan -> USB stick copy job

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

app = FastAPI(title="Underscanner Backend", version="2.3")
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

# ---- wall clock ----
# The Jetson runs its own access point: no internet, no NTP, so its clock drifts (by days).
# The phone sends its own time on connect (POST /clock) and again with POST /scan/start;
# we keep the difference and apply it
# to every user-facing timestamp (scan names, started_at, health header, notes). Durations
# (elapsed, rates, health "t") keep using the raw clock, where the offset cancels out.
# The system clock itself is never touched: no sudo, and no clock jump under running ROS.
CLOCK_MIN_YEAR, CLOCK_MAX_YEAR = 2024, 2100   # sanity bounds for a client-sent time
_clock = {"offset": 0.0}

def set_clock_from_client(client_ms) -> bool:
    """Learn the phone-vs-Jetson clock offset from a client epoch in milliseconds.
    Returns False (offset untouched) for anything that isn't a plausible epoch."""
    if not isinstance(client_ms, (int, float)) or isinstance(client_ms, bool):
        return False
    try:
        year = datetime.datetime.fromtimestamp(client_ms / 1000).year
    except (OverflowError, OSError, ValueError):
        return False
    if not CLOCK_MIN_YEAR <= year <= CLOCK_MAX_YEAR:
        return False
    _clock["offset"] = client_ms / 1000 - time.time()
    return True

def wall_now() -> datetime.datetime:
    return datetime.datetime.fromtimestamp(time.time() + _clock["offset"])

def iso(ts): return datetime.datetime.fromtimestamp(ts + _clock["offset"]).isoformat(timespec="seconds")

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

DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")

def make_scan_name(location: str | None, client_date: str | None = None) -> str:
    # The phone's *local* date wins (it is what the user reads); else the corrected clock.
    date = client_date if isinstance(client_date, str) and DATE_RE.match(client_date) \
        else wall_now().date().isoformat()
    if not location:                       # sticky: reuse last location
        if SCAN_STATE.exists():
            parts = SCAN_STATE.read_text().strip().split("_")
            location = "_".join(parts[1:-1]) if len(parts) >= 3 else "apartment"
        else:
            location = "apartment"
    location = clean_location(location)
    # Next run = highest run of this date+location across EVERY artifact kind, not just
    # bags: a scan whose bag dir is gone (deleted to free space, failed recorder) still
    # owns its pcd/notes/health, and reusing its number would merge two scans' files.
    prefix = f"{date}_{location}_"
    runs = [int(n[len(prefix):]) for n in collect_scan_names()
            if n.startswith(prefix) and re.fullmatch(r"[0-9]+", n[len(prefix):])]
    run = max(runs, default=0) + 1
    if run > 99:      # NAME_RE wants exactly 2 digits: run 100 would be a scan we can't serve
        raise HTTPException(409, f"99 scans déjà pour {date} / {location} : change le lieu")
    return f"{prefix}{run:02d}"

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
        # True from the moment a /scan/start claims the device until it is running (or its
        # failed launch is fully cleaned up). Guards against a second start interleaving
        # with the warm-up sleeps, now that those no longer block the event loop.
        self.starting = False
        self.scan = None
        self.algo: sc.SlamAlgo | None = None    # algo of the running scan (stop needs its save)
        self.slam: dict | None = None           # {algo, profile, overrides} of the running scan
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
        self.last_cloud_raw = 0      # points in that frame before the preview voxel filter
        # Preview link KPIs: bytes pushed over /ws/preview (monotonic, diffed into kB/s)
        # and the slowest single cloud send since the last sample — a send that blocks is
        # TCP back-pressure, i.e. the WiFi can't keep up.
        self.ws_bytes = 0
        self.ws_send_ms_max = 0.0
        # Latest phone-side render KPIs, sent by the app as "client_stats" on the socket.
        self.client_stats: dict = {}
        self.client_stats_t = 0.0
S = State()

# Phone KPIs accepted from "client_stats" messages; logged as ph_<key>. Whitelisted so a
# client can't write arbitrary keys into the health log.
CLIENT_STATS_KEYS = ("fps", "frame_max_ms", "upload_kbps", "rx_kbps", "chunks", "pts", "voxel")
CLIENT_STATS_MAX_AGE = 5.0   # s; older than this and the phone is considered gone

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
        self._prev_counts = (0, 0, 0, 0.0)  # (cloud_count, odom_count, ws_bytes, t)
        self._send_ms_acc = 0.0             # worst ws send since the last log line
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
            cc, oc, wb = S.cloud_count, S.odom_count, S.ws_bytes
            d["pts"] = S.last_cloud_pts
            d["pts_raw"] = S.last_cloud_raw
            d["traj_n"] = len(S.trajectory)
            d["odom_ok"] = (now - S.last_odom_t) < 2.0
            d["cloud_ok"] = (now - S.last_cloud_t) < 2.0
            send_ms, S.ws_send_ms_max = S.ws_send_ms_max, 0.0
            cs = S.client_stats if (now - S.client_stats_t) < CLIENT_STATS_MAX_AGE else {}
        # Link KPIs only while a phone is watching, so scans without a live view don't
        # grow flat-zero charts.
        watching = len(clients) > 0
        d["ws_clients"] = len(clients)
        # Hold the max across samples until it is logged, so a 5 s log line reports the
        # worst send of its whole interval, not just of the last 1 s sample.
        self._send_ms_acc = max(self._send_ms_acc, send_ms) if self._fh else send_ms
        if watching:
            d["ws_send_ms"] = round(self._send_ms_acc, 1)
        for k, v in cs.items():
            d[f"ph_{k}"] = v
        pc, po, pw, pt = self._prev_counts
        dt = now - pt
        if pt and dt > 0:
            d["cloud_hz"] = round((cc - pc) / dt, 1)
            d["odom_hz"] = round((oc - po) / dt, 1)
            if watching:
                d["ws_kbps"] = round((wb - pw) / 1024 / dt, 1)
        self._prev_counts = (cc, oc, wb, now)
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
                "clock_offset_s": round(_clock["offset"], 1),
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
        self._send_ms_acc = 0.0
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
    """Feeds the live preview from the running algo's cloud + pose topics (from its
    SlamAlgo entry). Starts on the default algo's topics; scan_start re-points it."""
    def __init__(self, algo: sc.SlamAlgo):
        super().__init__("underscanner_preview")
        self._fields_logged = False
        self._topics = (None, None)
        self._subs = []
        self._sub_lock = threading.Lock()
        self.use_topics(algo.cloud_topic, algo.odom_topic)

    def use_topics(self, cloud: str, odom: str):
        """(Re)subscribe when an algo publishes elsewhere. A no-op for the same topics."""
        with self._sub_lock:
            if (cloud, odom) == self._topics:
                return
            for s in self._subs:
                self.destroy_subscription(s)
            self._subs = [self.create_subscription(PointCloud2, cloud, self.on_cloud, 10),
                          self.create_subscription(Odometry, odom, self.on_odom, 10)]
            self._topics = (cloud, odom)
            self._fields_logged = False
            self.get_logger().info(f"preview topics: cloud={cloud} odom={odom}")

    def on_cloud(self, msg):
        present = {f.name for f in msg.fields}
        if not self._fields_logged:
            # one-time: reveals whether tag / line survive to the cloud topic
            self.get_logger().info(f"{self._topics[0]} point fields: {sorted(present)}")
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
            S.last_cloud_raw = int(n)

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
            "time":wall_now().isoformat(timespec="seconds"),
            "clock_offset_s":round(_clock["offset"], 1),
            "data_root":str(DATA_ROOT),"version":app.version}

@app.post("/clock")
async def set_clock(request: Request):
    """Sync the wall-clock offset to the phone: body {client_time_ms}. The app calls this as
    soon as it connects (and again whenever /status reports offset 0, i.e. after a backend
    restart), so timestamps are right before the first scan. /scan/start still carries the
    same field. Only the in-memory offset changes; the system clock is never touched."""
    try:
        body = (await request.json()) or {}
    except Exception:
        body = {}
    if not isinstance(body, dict) or not set_clock_from_client(body.get("client_time_ms")):
        raise HTTPException(400, "client_time_ms invalide")
    return {"ok": True, "time": wall_now().isoformat(timespec="seconds"),
            "clock_offset_s": round(_clock["offset"], 1)}

SLAM_META_KEYS = ("algo", "algo_label", "profile", "overrides", "sha256")

def scan_slam_meta(name: str) -> dict | None:
    """configs/<scan>.json, written at scan start: which algo/profile/overrides produced
    configs/<scan>.yaml (+ its sha256). None for scans from before SLAM profiles, whose
    .yaml was copied from FAST-LIO's mid360.yaml at stop."""
    f = CONFIGS / f"{name}.json"
    try:
        d = json.loads(f.read_text(encoding="utf-8"))
        return {k: d.get(k) for k in SLAM_META_KEYS} if isinstance(d, dict) else None
    except Exception:
        return None

@app.get("/scans")
def list_scans():
    out = []
    # With a stick mounted, each row says whether the scan is already on it (same sizes).
    stick = next((d for d in usb.list_devices() if d.mountpoint), None)
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
                    "health":{"present":hlog.exists(),"size_bytes":hs,"size_human":human(hs)},
                    "slam":scan_slam_meta(name),
                    "usb":{"present":usb.is_on_stick(DATA_ROOT, scan_artifacts(name), stick.mountpoint),
                           "stick":stick.name} if stick else None})
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
    data = await request.json(); data["_updated"] = wall_now().isoformat(timespec="seconds")
    (NOTES / f"{name}.json").write_text(json.dumps(data, indent=2))
    return {"ok": True}

def scan_artifacts(name: str) -> list[Path]:
    """Every on-disk artifact of a scan, keyed by its name. Keep in sync with
    collect_scan_names() and scan_start/stop() — rename moves exactly this list.
    configs/<scan>.json (SLAM meta) is renamed with the rest; the effective .yaml's header
    comment never names the scan, so its sha256 stays valid across a rename."""
    return [BAGS / name, MAPS / f"{name}.pcd", CONFIGS / f"{name}.yaml",
            CONFIGS / f"{name}.json",
            NOTES / f"{name}.json", TRAJ / f"{name}.traj", HEALTH / f"{name}.jsonl"]

def _retitle_health_header(path: Path, new_name: str):
    """Point the health log's header record at the new name (best effort)."""
    try:
        lines = path.read_text().splitlines(keepends=True)
        if not lines:
            return
        head = json.loads(lines[0])
        if head.get("type") != "header":
            return
        head["scan"] = new_name
        lines[0] = json.dumps(head) + "\n"
        tmp = path.with_suffix(".jsonl.tmp")
        tmp.write_text("".join(lines))
        tmp.replace(path)
    except Exception:
        pass

@app.post("/scans/{name}/rename")
async def rename_scan(name: str, request: Request):
    """Rename a scan: body {date, location, run}, each optional (defaults: current value).

    Moves every artifact (bag dir, pcd, config, notes, trajectory, health log) in one go
    and rolls back the ones already moved if any step fails. The bag directory is renamed
    as a whole; the files inside keep their old names, which rosbag2's metadata.yaml
    references relatively, so the bag stays readable. 409 for the running scan or when the
    target name is taken."""
    safe(name)
    if not any(p.exists() for p in scan_artifacts(name)):
        raise HTTPException(404, "scan introuvable")
    try:
        body = (await request.json()) or {}
    except Exception:
        body = {}
    parts = name.split("_")
    date = str(body.get("date", parts[0])).strip()
    location = str(body.get("location", "_".join(parts[1:-1])))
    run = str(body.get("run", parts[-1])).strip()

    try:
        if not DATE_RE.match(date): raise ValueError
        datetime.date.fromisoformat(date)              # rejects 2026-02-31
    except ValueError:
        raise HTTPException(400, "date invalide (AAAA-MM-JJ)")
    if not re.search(r"[^\W_]", location):          # nothing clean_location would keep
        raise HTTPException(400, "lieu vide")
    location = clean_location(location)             # same cleaning as at scan start
    if not run.isdigit() or not 1 <= int(run) <= 99:
        raise HTTPException(400, "run invalide (1 à 99)")
    new = f"{date}_{location}_{int(run):02d}"
    if not NAME_RE.match(new):
        raise HTTPException(400, "nom invalide")
    if new == name:
        return {"ok": True, "name": name}

    with S.lock:
        if S.running and S.scan == name:
            raise HTTPException(409, "scan en cours : arrête-le avant de le renommer")
    if new in collect_scan_names():
        raise HTTPException(409, f"le nom {new} existe déjà")

    moves = [(src, dst) for src, dst in zip(scan_artifacts(name), scan_artifacts(new)) if src.exists()]
    if not moves:
        raise HTTPException(404, "scan introuvable")
    done = []
    try:
        for src, dst in moves:
            if dst.exists():
                raise FileExistsError(dst)
            src.rename(dst)                         # same filesystem (/data): atomic
            done.append((src, dst))
    except Exception as e:
        for src, dst in reversed(done):
            try: dst.rename(src)
            except Exception: pass
        raise HTTPException(500, f"renommage annulé : {e}")

    _retitle_health_header(HEALTH / f"{new}.jsonl", new)
    try:                                            # sticky location follows the rename
        if SCAN_STATE.exists() and SCAN_STATE.read_text().strip() == name:
            SCAN_STATE.write_text(new)
    except Exception:
        pass
    return {"ok": True, "name": new}

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

# ===================== SLAM ALGOS + CONFIG PROFILES =====================
# Profiles live on the Jetson (slam/<algo>/<profile>.yaml); the phone only edits them
# through these endpoints. Every write is validated against the factory config first
# (slamcfg.check_profile). Error bodies: {"detail": {"msg", "issues": [{level, msg, line,
# where}]}} for 422, a plain string detail otherwise.
def _slam_errors(fn, *args, **kwargs):
    """Run a ProfileStore / slamcfg call, mapping its exceptions to HTTP errors."""
    try:
        return fn(*args, **kwargs)
    except sc.ConfigError as e:
        raise HTTPException(422, {"msg": str(e), "issues": [i.as_dict() for i in e.issues]})
    except sc.NotFound as e:
        raise HTTPException(404, str(e))
    except sc.Conflict as e:
        raise HTTPException(409, str(e))
    except sc.Forbidden as e:
        raise HTTPException(403, str(e))
    except sc.BadRequest as e:
        raise HTTPException(400, str(e))

async def _json_body(request: Request) -> dict:
    try:
        body = await request.json()
    except Exception:
        body = None
    if not isinstance(body, dict):
        raise HTTPException(400, "corps JSON (objet) attendu")
    return body

def overrides_text(v) -> str:
    """Per-scan overrides as "key: value" lines. Accepts that text, a list of lines, or a
    {key: value} object (bench --set style)."""
    if v is None:
        return ""
    if isinstance(v, str):
        return v
    if isinstance(v, list) and all(isinstance(x, str) for x in v):
        return "\n".join(v)
    if isinstance(v, dict):
        try:
            return "\n".join(f"{k}: {sc.fmt_value(val)}" for k, val in v.items())
        except TypeError as e:
            raise HTTPException(400, f"overrides : {e}")
    raise HTTPException(400, "overrides : texte « clé: valeur » attendu")

@app.get("/slam/algos")
def slam_algos():
    d = PROFILES.defaults()
    return {"default": d["algo"],
            "algos": [dict(a.describe(), default_profile=d["profiles"].get(a.id, sc.FACTORY))
                      for a in ALGOS]}

@app.get("/slam/defaults")
def slam_defaults_get():
    return PROFILES.defaults()

@app.put("/slam/defaults")
async def slam_defaults_put(request: Request):
    """Body {algo?, profiles?: {algo: profile}} — or {algo, profile} for one algo."""
    body = await _json_body(request)
    profiles = body.get("profiles")
    if profiles is None and body.get("profile") is not None:
        profiles = {body.get("algo") or PROFILES.defaults()["algo"]: body["profile"]}
    if profiles is not None and not isinstance(profiles, dict):
        raise HTTPException(400, "profiles : objet {algo: profil} attendu")
    return _slam_errors(PROFILES.set_defaults, algo=body.get("algo"), profiles=profiles)

@app.get("/slam/{algo}/profiles")
def slam_profiles(algo: str):
    lst = _slam_errors(PROFILES.list, algo)
    return {"algo": algo, "default": PROFILES.defaults()["profiles"].get(algo),
            "profiles": [{"name": p["name"], "readonly": p["readonly"],
                          "is_default": p["is_default"], "updated": iso(p["mtime"])}
                         for p in lst]}

@app.get("/slam/{algo}/profiles/{name}", response_class=PlainTextResponse)
def slam_profile_get(algo: str, name: str):
    return _slam_errors(PROFILES.read, algo, name)

@app.put("/slam/{algo}/profiles/{name}")
async def slam_profile_put(algo: str, name: str, request: Request):
    """Body = the profile's YAML text (text/plain). Validated, normalized, then written;
    creates the profile if it doesn't exist. 422 with line-numbered issues if invalid."""
    try:
        text = (await request.body()).decode("utf-8")
    except UnicodeDecodeError:
        raise HTTPException(400, "texte UTF-8 attendu")
    normalized, warnings = _slam_errors(PROFILES.save, algo, name, text)
    return {"ok": True, "name": name, "text": normalized,
            "issues": [w.as_dict() for w in warnings]}

@app.delete("/slam/{algo}/profiles/{name}")
def slam_profile_delete(algo: str, name: str):
    _slam_errors(PROFILES.delete, algo, name)
    return {"ok": True}

@app.post("/slam/{algo}/profiles/{name}/copy")
async def slam_profile_copy(algo: str, name: str, request: Request):
    to = (await _json_body(request)).get("to")
    _slam_errors(PROFILES.copy, algo, name, to)
    return {"ok": True, "name": to}

@app.post("/slam/{algo}/profiles/{name}/rename")
async def slam_profile_rename(algo: str, name: str, request: Request):
    to = (await _json_body(request)).get("to")
    _slam_errors(PROFILES.rename, algo, name, to)
    return {"ok": True, "name": to}

@app.post("/slam/{algo}/validate")
async def slam_validate(algo: str, request: Request):
    """Dry run, never writes. Body {yaml?, profile?, overrides?}: checks `yaml` (editor
    text) or else the stored `profile` (default profile if neither), plus the overrides.
    Always 200 for a validation outcome: {ok, issues, normalized?, effective?, overrides?}.
    `effective` is the config a scan would run with (without the header comment)."""
    body = await _json_body(request)
    a = _slam_errors(PROFILES.algo, algo)
    factory = _slam_errors(PROFILES.factory_text, algo)
    text = body.get("yaml")
    if text is None:
        _, _, text = _slam_errors(PROFILES.resolve, algo, body.get("profile"))
    if not isinstance(text, str):
        raise HTTPException(400, "yaml : texte attendu")
    ovr = overrides_text(body.get("overrides"))
    try:
        eff = sc.build_effective(a, factory, text, ovr)
        normalized, _ = sc.check_profile(a, factory, text)
    except sc.ConfigError as e:
        return {"ok": False, "issues": [i.as_dict() for i in e.issues]}
    return {"ok": True, "issues": [w.as_dict() for w in eff.warnings],
            "normalized": normalized, "effective": eff.text, "overrides": eff.overrides}

# ====================== ORCHESTRATION (Phase 2) ======================
DRIVER_WARMUP_S = 3.0   # let the Livox driver come up before SLAM subscribes
BAG_WARMUP_S    = 1.0   # only to catch a recorder that dies at once (e.g. output dir exists)
# (the SLAM warm-up is per algo: SlamAlgo.warmup_s)

async def _launch(name: str, cmd: list[str], warmup_s: float):
    """Spawn a stage, wait for it to settle WITHOUT blocking the event loop (the preview
    broadcaster, /status polls and the WebSocket keep running), then make sure it is still
    alive: a `ros2 launch` that exits during warm-up (missing package, bad arg) would
    otherwise leave a "running" scan that records nothing."""
    spawn(name, cmd)
    await asyncio.sleep(warmup_s)
    rc = S.procs[name].poll()
    if rc is not None:
        raise RuntimeError(f"« {name} » s'est arrêté au démarrage (code {rc})")

def _abort_start(name: str | None = None):
    """Undo a failed start: stop whatever was spawned, newest first, and drop the configs
    written for a scan that never recorded (else it would list as a config-only scan).
    Runs in a thread (stop_proc waits up to 8 s per process) and releases the start claim
    only once the processes are gone, so a retry can't launch a second driver next to a
    dying one."""
    try:
        for p in ("bag", "slam", "driver"):
            stop_proc(p, signal.SIGINT)
        if name and not (BAGS / name).exists():
            for f in (CONFIGS / f"{name}.yaml", CONFIGS / f"{name}.json"):
                try: f.unlink()
                except FileNotFoundError: pass
    finally:
        with S.lock:
            S.starting = False

def write_scan_config(name: str, algo: sc.SlamAlgo, profile: str, profile_text: str,
                      ovr_text: str) -> tuple[Path, dict]:
    """Build the effective config (profile + overrides + imposed keys), write it as
    configs/<scan>.yaml — the exact file the algo is launched on — and its provenance as
    configs/<scan>.json. Raises sc.ConfigError before writing anything if invalid."""
    def header(applied: dict) -> list[str]:
        # Never the scan name: a rename would make the comment stale (and the sha is of
        # this exact text). Plain YAML comments: the ROS parameter parser skips them.
        ovr = ", ".join(f"{k}={sc.fmt_value(v)}" for k, v in applied.items()) or "aucun"
        return [f"UnderScanner - config effective : algo {algo.id}, profil {profile}",
                f"overrides : {ovr}",
                "cles imposees par le backend : " + ", ".join(algo.forced)]
    eff = sc.build_effective(algo, PROFILES.factory_text(algo.id), profile_text, ovr_text,
                             header=header)
    cfg = CONFIGS / f"{name}.yaml"
    cfg.write_text(eff.text, encoding="utf-8")
    meta = {"v": 1, "algo": algo.id, "algo_label": algo.label, "profile": profile,
            "overrides": eff.overrides, "overrides_text": ovr_text,
            "sha256": eff.sha256, "preserved_layout": eff.preserved,
            "warnings": [w.as_dict() for w in eff.warnings],
            "created_at": wall_now().isoformat(timespec="seconds")}
    (CONFIGS / f"{name}.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2),
                                          encoding="utf-8")
    return cfg, meta

@app.post("/scan/start")
async def scan_start(request: Request):
    """Body {location?, client_time_ms?, client_date?, algo?, profile?, overrides?}; the
    SLAM fields default to slam/defaults.json. The effective config is written to
    configs/<scan>.yaml and the algo is launched ON THAT FILE, so the archived config is
    exactly what ran. 422 {detail: {msg, issues}} when the profile/overrides are invalid
    (nothing is launched)."""
    try:
        body = (await request.json()) or {}
    except Exception:
        body = {}
    if not isinstance(body, dict):
        body = {}

    # A USB copy and a recording would fight over the NVMe: refuse. 423, not 409 — the app
    # reads any 409 here as "a scan is already running, resume it".
    if USB.active:
        raise HTTPException(423, "transfert USB en cours : attends la fin ou annule-le")

    # Resolve + validate the SLAM config before claiming the device: a typo in the
    # overrides costs nothing and never spawns a process.
    algo, profile, profile_text = _slam_errors(PROFILES.resolve, body.get("algo"), body.get("profile"))
    ovr_text = overrides_text(body.get("overrides"))
    _slam_errors(sc.build_effective, algo, _slam_errors(PROFILES.factory_text, algo.id),
                 profile_text, ovr_text)

    # Check-and-claim with no await in between, so two starts can't both get past it.
    with S.lock:
        if S.running:  raise HTTPException(409, "a scan is already running")
        if S.starting: raise HTTPException(409, "a scan is already starting")
        S.starting = True

    launched = False
    name = None
    try:
        set_clock_from_client(body.get("client_time_ms"))
        for d in (BAGS, MAPS, CONFIGS, NOTES, TRAJ, HEALTH): d.mkdir(parents=True, exist_ok=True)
        name = make_scan_name(body.get("location"), body.get("client_date"))
        cfg, meta = _slam_errors(write_scan_config, name, algo, profile, profile_text, ovr_text)
        SCAN_STATE.write_text(name)
        if PREVIEW_NODE is not None:
            PREVIEW_NODE.use_topics(algo.cloud_topic, algo.odom_topic)

        await _launch("driver", LIDAR_LAUNCH, DRIVER_WARMUP_S)
        await _launch("slam", algo.launch_cmd(cfg), algo.warmup_s)
        await _launch("bag", ["ros2","bag","record",TOPIC_LIDAR,TOPIC_IMU,"-o",str(BAGS / name)],
                      BAG_WARMUP_S)

        slam = {k: meta[k] for k in ("algo", "profile", "overrides")}
        with S.lock:
            S.running, S.starting = True, False
            S.scan, S.started_at = name, time.time()
            S.algo, S.slam = algo, slam
            S.trajectory, S.traj_last = [], None
            started_at = S.started_at
        launched = True
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(500, f"échec du démarrage : {e}")
    finally:
        # Also covers cancellation. Cleanup runs off the event loop; S.starting stays
        # True until it finishes.
        if not launched:
            threading.Thread(target=_abort_start, args=(name,), daemon=True).start()

    H.open_log(name, started_at)
    return {"scan":name,"running":True,"started_at":iso(started_at),"slam":slam}

@app.post("/scan/stop")
def scan_stop():
    with S.lock:
        if not S.running: raise HTTPException(409, "no scan running")
        name = S.scan
        algo = S.algo or ALGOS[0]
    out = algo.pcd_out
    try: subprocess.run(list(algo.save_cmd), timeout=30)   # save while SLAM alive
    except Exception: pass

    # wait for the .pcd write to finish before killing SLAM (avoid truncation)
    if out.exists():
        last = -1
        for _ in range(20):                                # up to ~10s
            sz = out.stat().st_size
            if sz > 0 and sz == last: break                # size stable two checks running
            last = sz; time.sleep(0.5)

    stop_proc("bag",    signal.SIGINT)                     # flush bag first
    stop_proc("slam",   signal.SIGINT)
    stop_proc("driver", signal.SIGINT)

    # SLAM is dead now, so moving the file is unambiguously safe
    try:
        if out.exists():
            out.replace(MAPS / f"{name}.pcd")              # atomic move within /data
    except Exception: pass
    # The config is NOT copied here any more: configs/<scan>.yaml was written at start
    # and is the very file the algo ran on.

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
        slam = S.slam
        S.running, S.scan, S.started_at = False, None, None
        S.algo, S.slam = None, None
        S.latest_cloud, S.latest_pose = None, None
        S.trajectory, S.traj_last = [], None

    pcd, bag = MAPS / f"{name}.pcd", BAGS / name
    return {"scan":name,"running":False,
            "pcd":{"present":pcd.exists(),"size_human":human(dir_size(pcd))},
            "bag":{"present":bag.exists(),"size_human":human(dir_size(bag))},
            "slam":slam}

@app.get("/scan/status")
def scan_status():
    now = time.time()
    with S.lock:
        return {"running":S.running,"starting":S.starting,"scan":S.scan,
                "started_at":iso(S.started_at) if S.started_at else None,
                "elapsed_s":round(now - S.started_at,1) if S.started_at else 0,
                "odom_ok":(now - S.last_odom_t) < 2.0,
                "cloud_ok":(now - S.last_cloud_t) < 2.0,
                "slam":S.slam}


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
        if S.running or S.starting:
            raise HTTPException(409, "stop the scan before shutting down")
    if USB.active:
        raise HTTPException(423, "transfert USB en cours : attends la fin ou annule-le")
    for d in usb.list_devices():                 # flush + unmount sticks before power is cut
        if d.mountpoint:
            try: usb.eject(d)
            except usb.UsbError: pass
    subprocess.Popen(["sudo", "/usr/sbin/shutdown", "-h", "now"])
    return {"ok": True, "message": "shutting down"}


# ------------------------------- USB STICK -------------------------------
# A stick plugged into the Jetson, mounted through udisks2 (no sudo; one-time polkit grant:
# UnderScannerAPI/system/50-underscanner-usb.pkla). One scan copy at a time, run by rsync in
# the background (usbcopy.Transfer); the app polls GET /usb/transfer for the progress bar.
# Refusals that the user must read come back as {"detail": "<French message>"}.
def _usb_pick(device: str | None) -> usb.UsbDevice:
    """The stick to act on: [device] if given, else the only mounted one."""
    devs = usb.list_devices()
    if device:
        d = next((x for x in devs if x.device == device), None)
        if d is None:
            raise HTTPException(404, "clé introuvable (débranchée ?)")
        return d
    mounted = [x for x in devs if x.mountpoint]
    if not mounted:
        raise HTTPException(409, "aucune clé USB montée : monte-la depuis la salle de contrôle")
    if len(mounted) > 1:
        raise HTTPException(409, "plusieurs clés montées : précise laquelle")
    return mounted[0]

def _usb_errors(fn, *args):
    try:
        return fn(*args)
    except usb.UsbError as e:
        raise HTTPException(500, str(e))

@app.get("/usb/devices")
def usb_devices():
    return {"devices": [d.as_dict() for d in usb.list_devices()], "transfer": USB.snapshot()}

@app.post("/usb/mount")
async def usb_mount(request: Request):
    """Body {device}: mount that stick (it must be a USB filesystem from /usb/devices)."""
    device = (await _json_body(request)).get("device")
    if not isinstance(device, str) or not device:
        raise HTTPException(400, "device attendu (ex. /dev/sda1)")
    d = await asyncio.to_thread(_usb_pick, device)
    await asyncio.to_thread(_usb_errors, usb.mount, d)
    d = await asyncio.to_thread(_usb_pick, device)
    return d.as_dict()

@app.post("/usb/eject")
async def usb_eject(request: Request):
    """Body {device?}: flush, unmount and power off (then the stick can be pulled)."""
    if USB.active:
        raise HTTPException(409, "transfert en cours : attends la fin ou annule-le")
    try:
        device = (await request.json() or {}).get("device")
    except Exception:
        device = None
    d = await asyncio.to_thread(_usb_pick, device)
    await asyncio.to_thread(_usb_errors, usb.eject, d)
    return {"ok": True, "device": d.device, "name": d.name}

@app.get("/scans/{name}/usb")
def scan_usb_plan(name: str, device: str | None = None):
    """Dry run: what copying this scan to the stick involves, and whether it can work
    ({ok, problems, already, bytes_total, bytes_needed, ...} + the stick)."""
    safe(name)
    d = _usb_pick(device)
    return dict(usb.plan(DATA_ROOT, scan_artifacts(name), d).as_dict(), device=d.as_dict())

@app.post("/scans/{name}/usb")
async def scan_usb_copy(name: str, request: Request):
    """Start copying every artifact of the scan to the mounted stick (body {device?}).
    409: scan running / transfer running / no stick; 422 {detail: {msg, problems}} when the
    copy can't work (file > 4 GiB on FAT32, not enough space, read-only stick)."""
    safe(name)
    with S.lock:
        if S.running or S.starting:
            raise HTTPException(409, "un scan est en cours : arrête-le avant de copier")
    if USB.active:
        raise HTTPException(409, "un transfert est déjà en cours")
    try:
        device = (await request.json() or {}).get("device")
    except Exception:
        device = None
    d = await asyncio.to_thread(_usb_pick, device)
    p = await asyncio.to_thread(usb.plan, DATA_ROOT, scan_artifacts(name), d)
    if not p.ok:
        raise HTTPException(422, {"msg": p.problems[0], "problems": p.problems})
    try:
        USB.start(name, d, p, usb.rsync_argv(DATA_ROOT, p.rels, d.mountpoint))
    except usb.UsbError as e:
        raise HTTPException(409, str(e))
    return USB.snapshot()

@app.get("/usb/transfer")
def usb_transfer():
    return USB.snapshot()

@app.delete("/usb/transfer")
def usb_transfer_cancel():
    return {"ok": USB.cancel()}



# ====================== LIVE PREVIEW WEBSOCKET ======================
clients: set[WebSocket] = set()

def _accept_client_stats(raw: str):
    """Store a phone "client_stats" message (whitelisted numeric keys only)."""
    try:
        o = json.loads(raw)
    except ValueError:
        return
    if not isinstance(o, dict) or o.get("type") != "client_stats":
        return
    stats = {}
    for k in CLIENT_STATS_KEYS:
        v = o.get(k)
        if isinstance(v, (int, float)) and not isinstance(v, bool) and v == v:   # v == v: not NaN
            stats[k] = round(float(v), 3)
    with S.lock:
        S.client_stats, S.client_stats_t = stats, time.time()

@app.websocket("/ws/preview")
async def ws_preview(ws: WebSocket):
    await ws.accept()
    try:
        # Tell the app it may send its render KPIs. Clients only talk once invited: an
        # older backend never read this socket, so unsolicited messages would pile up.
        # Sent before joining `clients` so it can't race a broadcaster send.
        await ws.send_text(json.dumps({"type": "hello", "accepts": ["client_stats"]}))
        clients.add(ws)
        while True:                                  # broadcaster does the sending;
            msg = await ws.receive()                  # we only drain what the phone sends
            if msg["type"] == "websocket.disconnect":
                break
            if msg.get("text"):
                _accept_client_stats(msg["text"])
    except (WebSocketDisconnect, RuntimeError):
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
        sent = 0
        if pose and pose["t"] != last_pose_t:
            last_pose_t = pose["t"]; txt = json.dumps(pose)
            for ws in list(clients):
                try: await ws.send_text(txt); sent += len(txt)
                except Exception: clients.discard(ws)
        slowest = 0.0
        if cloud and (now - last_cloud_send) >= cloud_interval:
            last_cloud_send = now
            for ws in list(clients):
                t0 = time.perf_counter()
                try: await ws.send_bytes(cloud); sent += len(cloud)
                except Exception: clients.discard(ws)
                slowest = max(slowest, (time.perf_counter() - t0) * 1000)
        if sent or slowest:
            with S.lock:
                S.ws_bytes += sent
                S.ws_send_ms_max = max(S.ws_send_ms_max, slowest)

PREVIEW_NODE: PreviewNode | None = None

@app.on_event("startup")
async def _startup():
    global PREVIEW_NODE
    try:
        PROFILES.ensure_factories()        # slam/<algo>/usine.yaml, once (never rewritten)
    except Exception as e:
        print(f"[slam] factory snapshot failed: {e}", flush=True)
    rclpy.init()
    d = PROFILES.defaults()
    node = PREVIEW_NODE = PreviewNode(ALGO_BY_ID.get(d["algo"], ALGOS[0]))
    exe = MultiThreadedExecutor(); exe.add_node(node)
    threading.Thread(target=exe.spin, daemon=True).start()
    threading.Thread(target=H.run, daemon=True).start()
    asyncio.create_task(broadcaster())

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
