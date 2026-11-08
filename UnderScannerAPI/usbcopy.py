"""USB stick copy for the Underscanner backend: list sticks, plan a scan copy, run it.

Pure Python (no ROS, no FastAPI), so it is unit-tested on a PC. The system calls are
injectable (lsblk JSON, udisksctl runner, rsync argv) for the tests.

Mounting goes through udisks2 (`udisksctl`), so the backend needs no sudo: a one-time polkit
grant (UnderScannerAPI/system/50-underscanner-usb.pkla) lets user orin4slam mount / unmount /
power off REMOVABLE drives only. Sticks are mounted under /media/orin4slam/<LABEL>, owned by
the backend user.

The copy is one rsync run per scan:
    rsync -rtR --modify-window=1 --no-inc-recursive --info=progress2 \
          DATA_ROOT/./bags/<scan> DATA_ROOT/./maps/<scan>.pcd ... <stick>/underscanner/
  * -R keeps the path after "/./": the stick gets underscanner/bags/..., underscanner/maps/...
    (the same layout as on the Jetson).
  * -rt, not -a: FAT/exFAT store no owner/permissions (-a would error on them).
  * --modify-window=1: FAT stores mtimes to 2 s, without it rsync re-copies everything.
  * progress2 = one overall percentage for the whole scan (with --no-inc-recursive the total is
    known up front).

Before copying, plan() refuses what can't work and says why: a file > 4 GiB on FAT32, or
more data than the stick's free space (files already on the stick with the same size are not
counted: rsync skips them).
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

FAT32_MAX_FILE = 4 * 1024**3 - 1          # bytes; FAT32 can't hold a file of 4 GiB or more
STICK_ROOT = "underscanner"               # folder created at the root of the stick
_os_sync = getattr(os, "sync", lambda: None)   # os.sync is POSIX-only (tests run on Windows)


class UsbError(Exception):
    """Refused / failed USB operation; the message is shown to the user (French)."""


def human(n: float) -> str:
    for u in ("o", "Ko", "Mo", "Go", "To"):
        if n < 1024:
            return f"{n:.1f} {u}" if u != "o" else f"{int(n)} o"
        n /= 1024
    return f"{n:.1f} Po"


# ============================================================================
# devices
# ============================================================================
LSBLK_COLS = "NAME,PATH,TYPE,SIZE,FSTYPE,FSVER,LABEL,MODEL,VENDOR,TRAN,RM,HOTPLUG,MOUNTPOINT,FSAVAIL"


@dataclass
class UsbDevice:
    device: str                 # /dev/sda1 (what gets mounted)
    disk: str                   # /dev/sda  (what gets powered off)
    label: str | None
    model: str | None
    vendor: str | None
    size: int
    fstype: str                 # vfat / exfat / ntfs / ext4 ...
    fsver: str | None           # "FAT32", "FAT16"... (lsblk FSVER)
    mountpoint: str | None
    free: int | None            # bytes, only when mounted

    @property
    def is_fat32(self) -> bool:
        return self.fstype == "vfat"

    @property
    def name(self) -> str:
        return self.label or " ".join(x for x in (self.vendor, self.model) if x) or self.device

    def as_dict(self) -> dict:
        return {"device": self.device, "disk": self.disk, "label": self.label, "name": self.name,
                "model": self.model, "vendor": self.vendor, "size": self.size,
                "size_human": human(self.size), "fstype": self.fstype, "fsver": self.fsver,
                "mounted": self.mountpoint is not None, "mountpoint": self.mountpoint,
                "free": self.free, "free_human": human(self.free) if self.free is not None else None,
                "max_file": FAT32_MAX_FILE if self.is_fat32 else None}


def _int(v) -> int | None:
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


def _s(v) -> str | None:
    return v.strip() or None if isinstance(v, str) else None


def parse_lsblk(text: str) -> list[UsbDevice]:
    """USB filesystems from `lsblk -J -b -o LSBLK_COLS`: partitions (or a partition-less
    disk) with a filesystem, on a disk whose transport is usb. Internal disks never appear,
    so nothing else can be mounted through the API."""
    out: list[UsbDevice] = []
    try:
        devs = json.loads(text).get("blockdevices") or []
    except (ValueError, AttributeError):
        return out
    for d in devs:
        if d.get("type") != "disk" or d.get("tran") != "usb":
            continue
        parts = d.get("children") or [d]          # a stick formatted without a partition table
        for p in parts:
            fs = _s(p.get("fstype"))
            if not fs or fs in ("swap", "LVM2_member", "crypto_LUKS"):
                continue
            mp = _s(p.get("mountpoint"))
            out.append(UsbDevice(
                device=p.get("path"), disk=d.get("path"), label=_s(p.get("label")),
                model=_s(d.get("model")), vendor=_s(d.get("vendor")),
                size=_int(p.get("size")) or 0, fstype=fs, fsver=_s(p.get("fsver")),
                mountpoint=mp, free=_int(p.get("fsavail")) if mp else None))
    return out


def list_devices(run: Callable = subprocess.run) -> list[UsbDevice]:
    """USB sticks currently plugged in. Never raises: no lsblk / a hung lsblk = no stick
    (/scans calls this, and must keep working whatever the USB side does)."""
    try:
        r = run(["lsblk", "-J", "-b", "-o", LSBLK_COLS], capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.SubprocessError):
        return []
    return parse_lsblk(r.stdout) if r.returncode == 0 else []


def _udisks(run: Callable, *args: str) -> str:
    r = run(["udisksctl", *args, "--no-user-interaction"],
            capture_output=True, text=True, timeout=60)
    if r.returncode != 0:
        msg = (r.stderr or r.stdout or "").strip().splitlines()
        detail = msg[-1] if msg else f"code {r.returncode}"
        if "NotAuthorized" in detail or "not authorized" in detail.lower():
            detail = "non autorisé par polkit (voir ReadMe : fichier .pkla)"
        raise UsbError(f"udisksctl {args[0]} : {detail}")
    return r.stdout


def mount(dev: UsbDevice, run: Callable = subprocess.run):
    if dev.mountpoint:
        return
    _udisks(run, "mount", "-b", dev.device)


def eject(dev: UsbDevice, run: Callable = subprocess.run):
    """Flush, unmount, then power the drive off (so it can be pulled safely)."""
    _os_sync()
    if dev.mountpoint:
        _udisks(run, "unmount", "-b", dev.device)
    try:
        _udisks(run, "power-off", "-b", dev.disk)
    except UsbError:
        pass        # unmounted is what matters; some readers refuse power-off


# ============================================================================
# planning
# ============================================================================
def _files(src: Path) -> list[Path]:
    if src.is_file():
        return [src]
    if src.is_dir():
        return sorted(p for p in src.rglob("*") if p.is_file())
    return []


@dataclass
class CopyPlan:
    sources: list[Path]             # existing artifacts (files or dirs) of the scan
    rels: list[str]                 # their path under DATA_ROOT ("bags/<scan>", ...)
    bytes_total: int                # everything the scan weighs
    bytes_needed: int               # what rsync will actually write (not already on the stick)
    files_total: int
    already: bool                   # whole scan already on the stick (same sizes)
    problems: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.problems

    def as_dict(self) -> dict:
        return {"ok": self.ok, "problems": self.problems, "already": self.already,
                "bytes_total": self.bytes_total, "total_human": human(self.bytes_total),
                "bytes_needed": self.bytes_needed, "needed_human": human(self.bytes_needed),
                "files": self.files_total, "artifacts": self.rels}


def plan(data_root: Path, artifacts: list[Path], dev: UsbDevice) -> CopyPlan:
    """What copying these artifacts to [dev] involves, and whether it can work."""
    data_root = Path(data_root)
    sources = [a for a in artifacts if a.exists()]
    rels = [a.relative_to(data_root).as_posix() for a in sources]
    dest = Path(dev.mountpoint) / STICK_ROOT if dev.mountpoint else None
    total = needed = count = 0
    too_big: list[tuple[str, int]] = []
    for src in sources:
        for f in _files(src):
            size = f.stat().st_size
            count += 1
            total += size
            if dest is not None:
                there = dest / f.relative_to(data_root)
                try:
                    if there.stat().st_size == size:
                        continue
                except OSError:
                    pass
            needed += size
            if dev.is_fat32 and size > FAT32_MAX_FILE:
                too_big.append((f.relative_to(data_root).as_posix(), size))
    p = CopyPlan(sources, rels, total, needed, count, already=bool(sources) and needed == 0)
    if not sources:
        p.problems.append("aucun fichier pour ce scan")
    if dest is None:
        p.problems.append(f"la clé {dev.name} n'est pas montée")
    else:
        if not os.access(dev.mountpoint, os.W_OK):
            p.problems.append(f"la clé {dev.name} n'est pas inscriptible (formatée en ext4 ?)")
        for rel, size in too_big:
            p.problems.append(f"{rel} fait {human(size)} : trop gros pour une clé FAT32 "
                              f"(4 Go max par fichier) — reformate-la en exFAT")
        if dev.free is not None and needed > dev.free:
            p.problems.append(f"pas assez de place : {human(needed)} à copier, "
                              f"{human(dev.free)} libres sur {dev.name}")
    return p


def is_on_stick(data_root: Path, artifacts: list[Path], mountpoint: str) -> bool:
    """Every file of the scan is on the stick with the same size."""
    data_root, dest = Path(data_root), Path(mountpoint) / STICK_ROOT
    seen = False
    for src in artifacts:
        for f in _files(src):
            seen = True
            try:
                if (dest / f.relative_to(data_root)).stat().st_size != f.stat().st_size:
                    return False
            except OSError:
                return False
    return seen


def rsync_argv(data_root: Path, rels: list[str], mountpoint: str) -> list[str]:
    root = Path(data_root).as_posix().rstrip("/")
    return (["rsync", "-rtR", "--modify-window=1", "--no-inc-recursive",
             "--info=progress2", "--outbuf=L"]
            + [f"{root}/./{r}" for r in rels]
            + [f"{Path(mountpoint).as_posix().rstrip('/')}/{STICK_ROOT}/"])


# rsync --info=progress2: "  1,234,567  45%   12.34MB/s    0:01:23 (xfr#1, to-chk=3/7)"
_PROGRESS = re.compile(r"^\s*([\d,.']+)\s+(\d{1,3})%")


def parse_progress(line: str) -> tuple[int, int] | None:
    """(bytes transferred, overall percent) from one progress2 line, or None."""
    m = _PROGRESS.match(line)
    if not m:
        return None
    return int(re.sub(r"[,.']", "", m.group(1))), int(m.group(2))


# ============================================================================
# transfer job (one at a time)
# ============================================================================
class Transfer:
    """Runs one rsync in the background and exposes its progress. States:
    idle -> copying -> syncing -> done | error | cancelled."""

    ACTIVE = ("copying", "syncing")

    def __init__(self, popen: Callable = subprocess.Popen, clock: Callable = time.time,
                 sync: Callable | None = None):
        self._popen, self._clock, self._sync = popen, clock, sync or _os_sync
        self._lock = threading.Lock()
        self._proc = None
        self._cancel = False
        self._s: dict = {"state": "idle"}

    @property
    def active(self) -> bool:
        with self._lock:
            return self._s.get("state") in self.ACTIVE

    def snapshot(self) -> dict:
        with self._lock:
            return {k: v for k, v in self._s.items() if not k.startswith("_")}

    def start(self, scan: str, dev: UsbDevice, p: CopyPlan, argv: list[str]):
        with self._lock:
            if self._s.get("state") in self.ACTIVE:
                raise UsbError("un transfert est déjà en cours")
            now = self._clock()
            self._cancel = False
            self._s = {"state": "copying", "scan": scan, "device": dev.device, "label": dev.name,
                       "bytes_done": 0, "bytes_total": p.bytes_needed, "percent": 0,
                       "rate_bps": None, "eta_s": None, "error": None,
                       "started": now, "finished": None}
        Path(dev.mountpoint, STICK_ROOT).mkdir(exist_ok=True)
        try:
            proc = self._popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        except OSError as e:
            self._finish("error", f"rsync introuvable : {e}")
            raise UsbError(f"rsync introuvable : {e}")
        with self._lock:
            self._proc = proc
        threading.Thread(target=self._run, args=(proc,), daemon=True).start()

    def cancel(self) -> bool:
        with self._lock:
            proc = self._proc if self._s.get("state") == "copying" else None
            self._cancel = proc is not None
        if proc is not None:
            try:
                proc.terminate()      # rsync removes its partial temp file on SIGTERM
            except OSError:
                pass
        return proc is not None

    def _update(self, done: int, pct: int):
        now = self._clock()
        with self._lock:
            s = self._s
            prev_t, prev_b = s.get("_t"), s.get("_b")
            if prev_t is not None and now - prev_t >= 0.5:
                inst = (done - prev_b) / (now - prev_t)
                s["rate_bps"] = inst if s["rate_bps"] is None else 0.7 * s["rate_bps"] + 0.3 * inst
                s["_t"], s["_b"] = now, done
            elif prev_t is None:
                s["_t"], s["_b"] = now, done
            s["bytes_done"], s["percent"] = done, max(s["percent"], min(pct, 100))
            rate = s["rate_bps"]
            remaining = max(s["bytes_total"] - done, 0)
            s["eta_s"] = round(remaining / rate) if rate and rate > 0 else None

    def _run(self, proc):
        err_tail: list[str] = []

        def drain_err():
            for raw in iter(proc.stderr.readline, b""):
                err_tail.append(raw.decode("utf-8", "replace").strip())
                del err_tail[:-5]
        t = threading.Thread(target=drain_err, daemon=True)
        t.start()
        buf = b""
        while True:
            chunk = proc.stdout.read1(4096) if hasattr(proc.stdout, "read1") else proc.stdout.read(4096)
            if not chunk:
                break
            buf += chunk
            parts = re.split(rb"[\r\n]", buf)
            buf = parts.pop()
            for line in parts:
                pr = parse_progress(line.decode("utf-8", "replace"))
                if pr:
                    self._update(*pr)
        rc = proc.wait()
        t.join(timeout=2)
        for pipe in (proc.stdout, proc.stderr):
            try:
                pipe.close()
            except OSError:
                pass
        with self._lock:
            self._proc = None
            cancelled = self._cancel
        if cancelled:
            self._finish("cancelled", None)
        elif rc != 0:
            tail = next((l for l in reversed(err_tail) if l), "")
            hint = " (clé retirée ou pleine ?)" if rc in (11, 12, 23, 30) else ""
            self._finish("error", f"rsync code {rc}{hint}" + (f" : {tail}" if tail else ""))
        else:
            with self._lock:
                self._s["state"] = "syncing"     # data in the page cache isn't on the stick yet
            try:
                self._sync()
            except OSError:
                pass
            with self._lock:
                self._s["percent"] = 100
                self._s["bytes_done"] = self._s["bytes_total"]
            self._finish("done", None)

    def _finish(self, state: str, error: str | None):
        with self._lock:
            self._s["state"] = state
            self._s["error"] = error
            self._s["finished"] = self._clock()
            self._s["eta_s"] = None
            self._s.pop("_t", None)
            self._s.pop("_b", None)
