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
"""
import os, re, json, socket, struct, time, signal, threading, datetime, subprocess, asyncio
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
NAME_RE  = re.compile(r"^\d{4}-\d{2}-\d{2}_[A-Za-z0-9\-]+_\d{2}$")

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

app = FastAPI(title="Underscanner Backend", version="2.1")
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
    return sorted(names, reverse=True)

def make_scan_name(location: str | None) -> str:
    date = datetime.date.today().isoformat()
    if not location:                       # sticky: reuse last location
        if SCAN_STATE.exists():
            parts = SCAN_STATE.read_text().strip().split("_")
            location = "_".join(parts[1:-1]) if len(parts) >= 3 else "apartment"
        else:
            location = "apartment"
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
S = State()

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

    def on_odom(self, msg):
        p, q = msg.pose.pose.position, msg.pose.pose.orientation
        now = time.time()
        with S.lock:
            S.latest_pose = {"type":"pose","x":p.x,"y":p.y,"z":p.z,
                             "qx":q.x,"qy":q.y,"qz":q.z,"qw":q.w,"t":now}
            S.last_odom_t = now
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
        out.append({"name":name,"date":parts[0],"location":"_".join(parts[1:-1]),"run":parts[-1],
                    "bag":{"present":bag.exists(),"size_bytes":bs,"size_human":human(bs)},
                    "pcd":{"present":pcd.exists(),"size_bytes":ps,"size_human":human(ps)},
                    "config":{"present":cfg.exists()},"notes":{"present":note.exists(), "text": note_text},
                    "trajectory":{"present":traj.exists()}})
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
    for d in (BAGS, MAPS, CONFIGS, NOTES, TRAJ): d.mkdir(parents=True, exist_ok=True)
    name = make_scan_name(location); SCAN_STATE.write_text(name)

    spawn("driver", LIDAR_LAUNCH); time.sleep(3.0)         # let the driver come up
    slam_cmd = list(SLAM_LAUNCH_BASE) #+ [f"map_file_path:={MAPS}/{name}.pcd"]
    if SLAM_RVIZ_ARG: slam_cmd.append(SLAM_RVIZ_ARG)
    spawn("slam", slam_cmd); time.sleep(2.0)               # let SLAM init
    spawn("bag", ["ros2","bag","record",TOPIC_LIDAR,TOPIC_IMU,"-o",str(BAGS / name)])

    with S.lock:
        S.running, S.scan, S.started_at = True, name, time.time()
        S.trajectory, S.traj_last = [], None
    return {"scan":name,"running":True,"started_at":iso(S.started_at)}

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
    asyncio.create_task(broadcaster())

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
