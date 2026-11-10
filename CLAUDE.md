# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

TheUnderScannerApp is the Android control surface for a DIY handheld LiDAR cave scanner.
A Jetson on the device runs the SLAM and stores each scan's artifacts; the phone connects
to it over WiFi (the **Jetson runs its own access point**, NetworkManager hotspot, and the
phone joins it — this used to be the other way round) and is the device's only screen.
Consequence: the Jetson has no internet, hence **no NTP — its clock drifts** (it was ~9 days
behind on 2026-10-29). Fix: the phone hands the backend its time and the backend keeps
`offset = client − time.time()`, applied by `iso()` / `wall_now()` to every user-facing
timestamp (`started_at`, health header, notes `_updated`, `/status` time; exposed as
`clock_offset_s`). The phone sends it **on connect** via `POST /clock {client_time_ms}`
(`ClockSync.kt`, called from both ViewModels' `/status` polls: syncs when `/status` reports
offset 0 — never synced since the backend started — and re-syncs every 10 min; attempts are
rate-limited to one per 30 s, and a backend without `clock_offset_s` is skipped). `POST /scan/start`
still carries `client_time_ms` + local `client_date` too; the scan is named with `client_date`.
Durations keep the raw clock. The system clock is deliberately **not** set
(would need sudo, and a jump under running ROS nodes is risky), so bag stamps / file mtimes
stay on the Jetson clock. The offset lives in memory: after a backend restart it's 0 until
the phone's next `/status` poll re-syncs it.
**Backend deploys:** `UnderScannerAPI/deploy-backend.ps1` deploys the committed HEAD blobs of
every file in its `$Files` list (`app.py`, `slamcfg.py`, `usbcopy.py` — add new backend modules there). It
refuses if a Jetson file isn't found in that path's git history (= edited on the Jetson); a file
absent on the Jetson is accepted as new. **Backend tests:** `slamcfg.py` is pure Python + PyYAML
(no ROS): `python -m unittest discover -s UnderScannerAPI/tests` (needs `pip install pyyaml`;
the Jetson has PyYAML 5.4.1 / Python 3.10, so stay within those APIs). The app browses scans, downloads point clouds, views config, edits notes, and
visualizes `.pcd` files in a custom 3D OpenGL viewer.

Current version: `v0.1.0` (Early development stage)

**Scope: Phase 1 (file browsing) + Phase 2 (scan control + live preview) are done.** The app
can browse/download/view scans, and start/stop a scan with a live accumulating 3D preview.
**SLAM config profiles** are done too: per-algo YAML profiles stored on the Jetson, edited as
text from Settings (server-validated), chosen per scan with optional one-off overrides; each scan
archives the exact config it ran (`configs/<scan>.yaml` + `.json`). Only FAST-LIO2 is
registered; the registry (`slamcfg.py`) is ready for more algos.

## Build System

### Requirements
- **Java 17 JVM** (Required - configured in gradle.properties)
  - Location: `C:\Program Files\Eclipse Adoptium\jdk-17.0.3.7-hotspot`
  - Automatically configured via `org.gradle.java.home` in gradle.properties
- Android SDK with API level 30+ (minSdk = 30, targetSdk = 35, compileSdk = 35)
- Gradle 8.x
- ADB (Android Debug Bridge) for device deployment

### Standard Deployment Procedure

```powershell
# Build, uninstall old version, and install new version (recommended)
.\deploy.ps1
```

This script sets up Java 17, checks for a connected device, builds the debug APK,
uninstalls the old version, and installs the new one.

### Alternative Build Commands

```bash
.\build-with-java17.ps1        # Build with Java 17 (manual)
./gradlew assembleDebug        # Debug APK (requires Java 17 in PATH)
./gradlew assembleRelease      # Release APK
./gradlew installDebug         # Install debug build
./gradlew test                 # Unit tests
./gradlew clean                # Clean
./gradlew lint                 # Lint
```

## Architecture

### Pattern: MVVM with Jetpack Compose

- **UI Layer**: Jetpack Compose (Material 3), no XML layouts (except resources).
- **State Management**: Compose `State` / `mutableStateOf` exposed from the ViewModel.
- **Navigation**: Jetpack Navigation Compose.
- **Concurrency**: Kotlin Coroutines, all network/IO on `Dispatchers.IO`.
- **Networking**: OkHttp client against the Jetson FastAPI backend.

### The Jetson backend (the contract)

find the UnderScannerAPI/app.py

A FastAPI service runs at `http://10.42.0.1:8000` (the Jetson is its access point's gateway, so
the address is fixed). The base URL stays editable in the app's Settings; default is in
`SettingsRepository.DEFAULT_BASE_URL`.

| Method | Path | Purpose | Returns |
|---|---|---|---|
| GET | `/status` | Readiness heartbeat | `{status, hostname, time, clock_offset_s, data_root, version}` |
| GET | `/scans` | List all scans + metadata | `{scans: [ScanInfo]}` (newest first) |
| GET | `/scans/{name}/pcd` | Download point cloud | binary `application/octet-stream` |
| GET | `/scans/{name}/trajectory` | Download LiDAR path (`UTR1`) | binary `application/octet-stream`; **404** if none |
| GET | `/scans/{name}/config` | Read config (read-only) | `text/plain` (YAML) |
| GET | `/scans/{name}/notes` | Read notes form | JSON object (`{}` if none) |
| PUT | `/scans/{name}/notes` | Save notes form | `{ok: true}` |
| POST | `/scans/{name}/rename` | Rename; body `{date, location, run}` (each optional) | `{ok, name}`; **409** running / name taken, **400** invalid, **404** unknown |
| GET | `/scans/{name}/health` | Per-scan health log | JSONL body; **404** if none |
| GET | `/system/health` | Live Jetson telemetry | latest cached sample + `static` |
| POST | `/clock` | Sync wall-clock offset; body `{client_time_ms}` | `{ok, time, clock_offset_s}`; **400** if implausible |
| POST | `/scan/start` | Start a scan; body `{location, client_time_ms, client_date, algo?, profile?, overrides?}` | `{scan, running, started_at, slam}`; **422** bad profile/overrides (nothing spawned); **409** if running/starting or 99 runs taken; **500** + `detail` if a stage dies during warm-up |
| POST | `/scan/stop` | Stop + save | `{scan, running:false, pcd, bag, slam}`; **409** if none |
| GET | `/scan/status` | Poll scan state | `{running, starting, scan, started_at, elapsed_s, odom_ok, cloud_ok, slam}` |
| GET | `/slam/algos` | SLAM algo registry | `{default, algos:[{id, label, description, forced, live, default_profile}]}` |
| GET/PUT | `/slam/defaults` | Default algo + profile per algo | `{algo, profiles:{algo: name}}`; PUT `{algo?, profiles?}` or `{algo?, profile}` |
| GET | `/slam/{algo}/profiles` | Profile list | `{algo, default, profiles:[{name, readonly, is_default, updated}]}` |
| GET/PUT/DELETE | `/slam/{algo}/profiles/{name}` | Profile YAML (`text/plain`) | PUT validates + normalizes → `{ok, name, text, issues}`; **422** invalid; **403** `usine`; DELETE **409** if default |
| POST | `/slam/{algo}/profiles/{name}/copy` · `/rename` | body `{to}` | **409** name taken, **403** `usine` |
| POST | `/slam/{algo}/validate` | Dry run; body `{yaml? \| profile?, overrides?}` | always 200: `{ok, issues:[{level,msg,line,where}], normalized?, effective?, overrides?}` |
| GET | `/usb/devices` | USB sticks on the Jetson + copy job | `{devices:[{device, disk, name, label, model, vendor, size, fstype, fsver, mounted, mountpoint, free, max_file}], transfer}` |
| POST | `/usb/mount` | body `{device}` (must be a USB filesystem) | the device; **404** unknown, **500** udisks error |
| POST | `/usb/eject` | body `{device?}`: sync + unmount + power-off | `{ok, device, name}`; **409** during a copy |
| GET | `/scans/{name}/usb` | Copy dry run | `{ok, problems, already, bytes_total, bytes_needed, …, device}` |
| POST | `/scans/{name}/usb` | Copy the whole scan to the mounted stick | transfer snapshot; **409** scan running / copy running / no stick; **422** `{detail:{msg, problems}}` (FAT32 file > 4 GiB, no space, read-only) |
| GET/DELETE | `/usb/transfer` | Copy progress / cancel | `{state: idle\|copying\|syncing\|done\|error\|cancelled, scan, label, bytes_done, bytes_total, percent, rate_bps, eta_s, error}` |

`/scan/start` and `/system/shutdown` answer **423** while a USB copy runs (not 409: the app reads a
409 from `/scan/start` as "a scan is already running → resume"). Shutdown ejects mounted sticks first.
`/scans` rows carry `usb: {present, stick}` when a stick is mounted (null otherwise).

**USB stick copy (`usbcopy.py`).** Sticks plugged into the Jetson are listed with `lsblk -J`
(only filesystems on `tran=usb` disks — internal disks can't be mounted through the API) and
mounted through **udisks2** (`udisksctl … --no-user-interaction`, under `/media/orin4slam/<LABEL>`,
owned by the backend user — no sudo). That needs a one-time polkit grant: polkit is **0.105** on
the Jetson, so it is a `.pkla` file, versioned in `UnderScannerAPI/system/50-underscanner-usb.pkla`
(removable drives only; install steps in `UnderScannerAPI/ReadMe.md`). A copy = one rsync run of
every artifact (`scan_artifacts()`), `-rtR --modify-window=1 --no-inc-recursive --info=progress2`:
`-R` keeps `underscanner/bags/…`, `underscanner/maps/…` on the stick (Jetson layout), `-rt` not
`-a` (FAT stores no owner/perms), `--modify-window=1` (FAT mtimes are 2 s; without it everything
is re-copied). `plan()` refuses up front — and says why — a FAT32 file > 4 GiB or more data than
the free space (files already there with the same size don't count). `Transfer` runs rsync in a
thread, parses progress2, then `sync`s ("syncing") before "done"; cancel = SIGTERM. One copy at a
time; copying is **copy, not move** (deleting from the Jetson is a later, separate feature).
`list_devices()` never raises (no lsblk = no stick) since `/scans` calls it.
| WS | `/ws/preview` | Live preview stream | binary cloud frames + JSON pose / `hello`; client → `client_stats` |
| POST | `/system/shutdown` | Power the Jetson off | `{ok}`; **409** if a scan is running or starting |

**Scan start (`scan_start`)** is `async` and its warm-ups (driver 3 s, SLAM 2 s, bag 1 s) are
`await asyncio.sleep`, so the event loop — preview broadcaster, WebSocket, `/status` polls —
keeps running during the ~6 s start. Concurrency is guarded by `S.starting`, claimed in the same
lock block as the `S.running` check (no `await` between them). After each warm-up `_launch`
checks the process is still alive (`poll()`); any failure raises, and `_abort_start` stops
whatever was spawned **in a thread** and only then clears `S.starting`, so a retry can't start a
second driver next to a dying one. The app surfaces the 500's `detail` text.
**SLAM algos + config profiles (`slamcfg.py`, wired in `app.py`).** A registry (`ALGOS`, one
`SlamAlgo` per algo: launch-on-a-config-file, preview cloud/odom topics, map-save command, `.pcd`
output, imposed keys, warm-up) — only `fastlio2` today; `scan_start`/`scan_stop` and
`PreviewNode.use_topics` only use that interface, so a new algo is a new registry entry. Profiles
live **on the Jetson**: `DATA_ROOT/slam/<algo>/<profile>.yaml` + `slam/defaults.json`;
`usine.yaml` is snapshotted once from `FASTLIO_CONFIG` at startup and is read-only (it's the
reference every profile is validated against: valid YAML, no duplicate/unknown key, no type
change — int→float is normalized since ROS rejects an int for a `double` param — list lengths
kept; errors carry the line). Imposed keys (FAST-LIO: `map_file_path`=`.lastscan.pcd`,
`pcd_save.pcd_save_en`, `pcd_save.interval=-1`, `common.lid/imu_topic`,
`publish.scan_publish_en`) are rewritten whatever the profile says. Per-scan **overrides** =
`key: value` lines, bench `--set` semantics (leaf name or dotted suffix, must exist and be
unique). At **start**: profile + overrides + imposed keys → `configs/<scan>.yaml` (built by
patching the profile *text* so comments survive; a header comment names algo/profile/overrides
but never the scan, so a rename keeps it true) and `configs/<scan>.json` = `{algo, profile,
overrides, sha256, …}`; FAST-LIO is launched with `config_path:=configs config_file:=<scan>.yaml`.
Stop no longer copies any config. `/scans` rows carry `slam` (that meta, or `null` for pre-profile
scans whose `.yaml` was copied from `mid360.yaml` at stop — on this Jetson that file is what ran,
the workspace being `--symlink-install`). Rename moves the `.json` too. Tests:
`UnderScannerAPI/tests/` (`test_slamcfg.py`, and `test_app_slam.py` = the endpoints end-to-end with
ROS/processes faked; needs `fastapi httpx numpy`, skipped otherwise).
**Run numbers** (`make_scan_name`): next = max run for that `date_location` across *every*
artifact kind (`collect_scan_names()`), not only bags — a scan whose bag dir was deleted still
owns its pcd/notes/health. Past run 99 it refuses (409): `NAME_RE` wants exactly 2 digits, so
run 100 would be a scan the API could create but never serve.

**System health (`HealthMonitor` in `app.py`):** the backend samples Jetson load/thermal/power
at 1 Hz from unprivileged sysfs/procfs and appends a line to `health/{scan}.jsonl` every 5 s
while a scan runs. Sensor paths are probed **once at startup** and cached — on the Orin Nano the
`cv0/1/2-thermal` zones exist but return `EAGAIN`, so they're dropped at probe time. Watts are
computed as V×I from the INA3221 rails (`VDD_IN`, `VDD_CPU_GPU_CV`, `VDD_SOC`); this kernel has
no `power*_input` node. Log format: line 1 is a header (`static` carries `cpu_max_mhz`,
`mem_total_mb`, `power_mode`, available `zones`/`rails`), every later line is one sample keyed
`t` (seconds since start). **Every field except `t` is optional** — read available series from
the header, never a hard-coded list. It's JSONL rather than a JSON array so a scan cut short by
a dead battery still parses up to its last complete line, and it's written through a handle held
open for the whole scan so append cost never grows with scan length. Bag size is deliberately
*not* logged (`dir_size()` walks the directory); `disk_free` via `statvfs` is the O(1) proxy.
App side: `SystemHealth` (live) and `HealthLog`/`HealthSample` (parsed log) in `ScanModels.kt`
address series by backend key, so a new metric charts without an app change.
**Preview-link + phone KPIs** are logged too: `pts_raw` (cloud points before the preview voxel
filter), `ws_clients`, `ws_kbps`, `ws_send_ms` (slowest cloud send in the log interval — rising =
WiFi back-pressure; both only while a phone watches), and `ph_*` = the phone's own render KPIs
(`fps`, `frame_max_ms`, `upload_kbps`, `rx_kbps`, `chunks`, `pts`, `voxel`). The phone sends
those as `{"type":"client_stats",...}` text on `/ws/preview` **only after** the server's
`{"type":"hello","accepts":["client_stats"]}` — an older backend never reads the socket, so
unsolicited messages would fill its receive queue and stall the connection. Keys are whitelisted
server-side (`CLIENT_STATS_KEYS`).

`ScanInfo` carries `name, date, location, run` plus artifact sub-objects `bag`, `pcd`
(`{present, size_bytes, size_human}`), `config`, and `notes` (`{present, ...}`). For search,
`ScanInfo.notesText` flattens whatever note content the list embeds (`flattenJsonText`); since the
list endpoint typically reports only note *presence*, the ViewModel also fetches each scan's notes
once from `/scans/{name}/notes` and merges the text into the searchable list (cached per session).

**WebSocket `ws://<jetson>/ws/preview`:** binary message = one `USC1` frame (little-endian):
an 8-byte header — 4-byte ASCII magic `"USC1"` (version tag; a frame that doesn't start with
it is dropped) + `uint32` point_count — then point_count **16-byte interleaved records**:
`x,y,z` float32 (offsets 0/4/8) then `reflectivity, tag, line, reserved` uint8 (offsets 12–15).
World frame, accumulate. The 16-byte stride is 4-aligned so the 4 trailing bytes bind directly
as a normalized `GL_UNSIGNED_BYTE` vec4 attribute (records are uploaded verbatim into VBOs). `tag` is bit-packed Livox noise
info (bits 1-0 spatial, bits 3-2 return-intensity; 0/1/2/3 = normal/high/medium/low-confidence
noise) and is often all-zero with the current FAST-LIO (harmless). Text message = JSON pose
`{type:"pose", x, y, z, ...}` (~10 Hz), plus one `{type:"hello", accepts:["client_stats"]}` right
after connect. The socket is otherwise server → phone; the only client → server message is
`client_stats` (every 2 s, only once invited by `hello` — see System health). `GET /preview/format` also returns this layout as JSON
(not consumed by the app; the binary layout above is authoritative).

**Trajectory `UTR1` (LiDAR path):** the backend records `/Odometry` positions (min-step dedup)
while a scan runs, writes `trajectories/{name}.traj` on stop, and serves it at
`/scans/{name}/trajectory` (a running scan's in-progress path is served from memory if the file
doesn't exist yet; `/scans` reports `trajectory.present`). Format is little-endian: 8-byte header
(4-byte magic `"UTR1"` + `uint32` count) then count × `x,y,z` float32 (12 B each). Live, the app
builds the same path itself from the pose stream (no fetch needed); for a saved scan the `.traj`
is downloaded next to the `.pcd` (`ScanApiClient.downloadTrajectory`, best-effort) and both are
drawn as a yellow polyline (`Trajectory.kt` + `MyGLRenderer.drawTrajectory`, `GL_LINE_STRIP`).

### Key Components

- **MainActivity.kt** — hides Android's **status bar** app-wide (`hideStatusBar()`, re-asserted
  in `onWindowFocusChanged` since the system restores bars after dialogs/app switches); a swipe
  from the top still reveals it transiently so phone battery stays checkable.
  `decorFitsSystemWindows` is deliberately left at its default — hiding the bar already zeroes
  its inset, and going edge-to-edge would push content under the nav bar on every screen.
  Also the entry point; `AppNavHost` starts at `mainMenu` and wires
  `scanLibrary`, `settings`, `pcdViewer/{fileName}`, `controlRoom`, `activeScan`. Two shared
  hoisted ViewModels: `ScanLibraryViewModel` (Phase 1) and `ScanControlViewModel` (Phase 2).

- **MainMenuScreen.kt** — big-button menu: Scan Library and Lidar Control Room.

- **ScanModels.kt** — `ScanInfo`, `ScanArtifact`, `StatusInfo` (incl. `clockOffsetS`), and the
  `ConnectionState` sealed type (Connecting / Connected / Offline). SLAM side: `SlamMeta` (a
  scan's algo/profile/overrides/sha — on `ScanInfo.slam`, `ScanStatus.slam`, `StartResult.slam`;
  null for pre-profile scans; overrides sorted by key since org.json key order differs between
  Android and the JVM), `SlamAlgos`/`SlamAlgoInfo`, `SlamProfileInfo` (`FACTORY = "usine"`),
  `SlamValidation`, `SlamIssue`, and `SlamConfigException` (a 422 with line-numbered issues).
  Parsing is unit-tested in `SlamModelsTest` (the JVM tests use the real `org.json` as a
  test-only dependency; Android's is a stub there).

- **ScanApiClient.kt** SLAM calls — `getSlamAlgos`, `getSlamProfiles`, `get/put/delete/copy/
  renameSlamProfile`, `setSlamDefaults`, `validateSlam`, and `startScan(location, algo, profile,
  overrides)`. URLs are built with `HttpUrl.addPathSegment` (profile names may hold accents or
  dots). `bodyOrThrow` maps errors: a 422 with `detail.issues` → `SlamConfigException`, otherwise
  an `IOException` carrying the backend's `detail`.

- **ClockSync.kt** — process-wide `object` that pushes the phone's time to `POST /clock` (see
  the clock note at the top).

- **ScanApiClient.kt** — OkHttp wrapper for the contract above. `downloadPcd()` streams to
  disk with progress, honors coroutine cancellation, and deletes partial files on failure.

- **ScanLibraryViewModel.kt** (`AndroidViewModel`) — polls `/status` (every 3s while the
  screen is foregrounded) to drive the connection indicator; holds one unified scan list
  (server list, falling back to an offline file cache, plus any locally-downloaded scan
  absent from the server list, flagged `localOnly`); manages per-scan download progress;
  proxies config/notes requests. **"Downloaded" = the `.pcd` exists in local storage**, not
  a server flag.

- **LocalScanStorage.kt** — single source of truth for on-device files: downloaded `.pcd`s
  live in the **public** `Documents/UnderScanner/Scans` folder
  (`Environment.DIRECTORY_DOCUMENTS/UnderScanner/Scans`, i.e. *Internal storage/Documents/…*, so
  they're browsable in the Files app and over USB; this is also where the OpenGL viewer reads from);
  the last `/scans` JSON is cached to `filesDir/scans_cache.json`. Writing to the public folder
  needs **All-files access** (`MANAGE_EXTERNAL_STORAGE`), prompted for at startup by
  `MainActivity.StoragePermissionPrompt`; `migrateLegacyScans()` moves scans from the old
  app-private `getExternalFilesDir("Scans")` location on first grant/launch.

- **SettingsRepository.kt** — persists and normalizes the editable Jetson base URL
  (SharedPreferences `underscanner_settings`).

- **ScanLibraryScreen.kt** — the single Scan Library screen: connection indicator + address
  (→ Settings), pull-to-refresh (its indicator shows **only for a user pull**; automatic
  refreshes are silent, and the screen forces `animateToHidden()` once nothing refreshes —
  material3 1.3.0's indicator could stay parked, spinning, after rapid `isRefreshing` flips until
  the next touch), one row per scan (bag size, PCD Download/Open + progress,
  Config view, Notes edit). The top bar also has **search** (fuzzy: case-insensitive, separator-
  tolerant so `cave-x`/`cave_x`/`cave x` match; scans name/location/date/notes text) and a
  **sort** menu (date / name both directions, plus PCD size and bag size descending; persisted).
  Both are client-side and work offline. Hosts the read-only **Config** dialog (headed by
  `SlamProvenance`: algo · profile, the per-scan overrides and the config's sha256, or "ancien
  format" for scans whose config was copied at stop) and the **Notes**
  form dialog (`site, issues, free`; full-width via `usePlatformDefaultWidth = false`, with a
  centred *Notes / date / scan-name* header, the name marquee-scrolling rather than truncating).
  A **pencil** (left of the health-chart icon; server scans only, enabled only while connected
  and not mid-download) opens the **Rename** dialog: date (text + `DatePickerDialog`), location,
  run, with a live preview of the final name. The backend renames every artifact
  (`scan_artifacts()`: bag dir, pcd, config, notes, traj, health) with rollback on failure,
  retitles the health-log header, moves `.current_scan` if it pointed there, and refuses the
  running scan; the bag dir's inner files keep their old names (rosbag2's `metadata.yaml`
  references them relatively). The phone then moves its downloaded files
  (`LocalScanStorage.renameScan`), re-keys the notes-search cache and refetches the list.
  Legacy `conditions` / `estimated_length_m` fields are no
  longer shown or written (existing values stay in the Jetson's JSON, just unused).
  The `openNotes` route argument is consumed once via a **`rememberSaveable`** flag — with a
  plain `remember`, navigating to the viewer or the charts disposes this composable while the
  back-stack entry keeps the argument, so returning re-opened the notes form every time.

- **HealthChartScreen.kt** — post-scan health charts, reached from a scan row's timeline icon
  (`onOpenHealth`; the log is fetched on demand if it isn't local yet, then works offline).
  A **"fake landscape" page**: the activity is locked to portrait and the whole content is
  rotated 90° clockwise, so the user turns the phone *counter-clockwise* and the time axis gets
  the screen's long edge. The rotated container **must** use `requiredSize(width = maxHeight,
  height = maxWidth)` — plain `size()` is still bounded by the parent's portrait constraints and
  gets coerced straight back into a portrait-shaped box. One chart fills the screen at a time and
  a `HorizontalPager` swipes between them (gestures rotate with the content, so a swipe reads as
  horizontal once the phone is turned). A full-screen-wide page made the default half-page snap
  threshold a very long drag, so the swipe felt dead: `PagerDefaults.flingBehavior` drops
  `snapPositionalThreshold` to `0.15f`. The bottom bar also carries **prev/next arrows and
  tappable dots**, so chart switching never depends on the gesture at all. Pages: temperature /
  load / power / SLAM health, then the live-view pages (phone fps / worst frame / preview points
  with a voxel-step footer / preview data rates / WiFi send time), all sharing the same x domain so a feature sits at the same
  horizontal position across a swipe. A page whose series are all absent from the log is dropped
  entirely, and `odom_ok`/`cloud_ok` dropouts render as shaded bands behind the rate lines. All
  axis text is composed with `Text`, never measured inside the Canvas.

- **SettingsScreen.kt** — edit/normalize the Jetson base URL; shows live connection state (it
  starts the library VM's `/status` poll itself — that loop is **reference-counted** like the
  Control Room's, since Library ↔ Settings are briefly both alive during a transition; before,
  Settings only showed whatever the Library last polled, e.g. "Connexion…" forever when opened
  from the main menu); hosts
  the **SLAM** section (`SlamSettingsSection`: default algo, default profile, → profile list).
  Scrollable.

- **SlamViewModel.kt** — third NavHost-hoisted VM (Settings, profile screens, and the Control
  Room scan form): algos, the selected algo's profiles, list actions (default / copy / rename /
  delete, each reporting a message), editor calls (load / validate / save) and
  `validateForScan`. Keeps no copy of the profiles: everything goes through `/slam`, so it needs
  the Jetson. `nameProblem()` = a light client check of profile names (backend is authoritative).

- **UsbViewModel.kt** — fourth NavHost-hoisted VM: USB sticks on the Jetson + the copy job.
  Polls `/usb/devices` (1 s during a copy, else 3 s), reference-counted between the Library and
  the Control Room. `supported = false` on a 404 (older backend) hides the USB UI.
  `visibleTransfer` only surfaces a *finished* job if this app saw it run (the backend keeps the
  last result forever). Errors carry the backend's French `detail` (`HttpStatusException`).

- **UsbUi.kt** — `UsbSendIcon` (USB plug + arrow), `UsbCard` (Control Room: sticks with
  name/model/size/fs, **Monter** / **Éjecter** → "tu peux retirer la clé", FAT32 4 GB note, the
  running copy), `UsbTransferBar` (the horizontal bar + % · rate · time left + Annuler, on the scan
  card), `UsbCopyDialog` (Library: size, what's already on the stick, free space, and the
  backend's refusal reasons; *Copier* disabled when the plan isn't ok). In the Library each server
  scan has the USB button (icon buttons are 40 dp so five fit on a phone card); a scan already on
  the mounted stick shows "Sur <clé> ✓". The Control Room's *Nouveau scan* is disabled during a copy.

- **SlamScreens.kt** — `SlamProfilesScreen` (route `slamProfiles`: one card per profile, `usine`
  locked, default badge, per-row menu, FAB = new profile as a copy of `usine`) and
  `SlamProfileEditorScreen` (route `slamEditor/{algo}/{name}`, args `Uri.encode`d).
  The editor is text-only by design (≈40 nested keys, different per algo): `CodeEditor` =
  `BasicTextField`, monospace, line-number gutter sharing one vertical scroll and one line
  height with the text, no soft wrap (horizontal scroll), validation issues painted as
  whole-line backgrounds (`LineMarks` VisualTransformation) and listed below (tap = jump to the
  line). **Guard:** *Enregistrer* is enabled only when the current text is exactly the one the
  server last validated OK (`validatedText == text`); any keystroke invalidates it, and the PUT
  re-validates anyway. `usine` is read-only (duplicate it). *Réinitialiser depuis l'usine* only
  replaces the editor buffer. Back with unsaved changes asks first.

- **PCDViewerScreen.kt** — the 3D viewer wrapper: **no top bar** (the GL surface is
  full-screen; Android's back gesture handles leaving), just the **viewer options cluster**
  (`ViewerControls.kt`). The camera is gesture-only (see the OpenGL viewer section); there is
  no camera-style / control-mode / auto-level menu. The **Repères** and **Projection** toggle
  states are read from / written to `SettingsRepository` (`viewerHelpers`,
  `viewerOrthographic`), so they persist across viewer sessions and app restarts.

- **ViewerControls.kt** — two clusters, both reused by `PCDViewerScreen` and `ActiveScanScreen`,
  laid out as a bottom-right `Row` (`ColorModeCluster` left of `ViewerOptionsCluster`):
  - `ViewerOptionsCluster`: an icon-only access FAB toggling a panel of child actions (pressing a
    plain child auto-collapses it). Children top→bottom: optional **Suivre la position**
    (live only, `MyLocation` icon: magnetises the orbit pivot to the sensor pose — see
    "Follow" under Gestures), optional **auto-orbit** (tap-toggle +
    long-press speed slider), **Filtre bruit** (`NoiseFilterFab`: 3-stop tag noise filter — tap
    cycles Off/Conservateur/Agressif, long-press reveals a 3-detent vertical slider), **Tout
    afficher** (frame-all), **Repères** (axis-ruler always-on), **Projection** (perspective ⇄
    orthographic), and **Trajectoire** (yellow LiDAR-path polyline; shown only when a path is
    available — always in live, and in the Library viewer only if the scan's `.traj` was
    downloaded). Toggles tinted when active.
  - `ColorModeCluster`: the coloring selector. Its expanded column is **persistent** (a mode tap
    does not collapse it — the user closes it explicitly). Modes: **Uniforme / Intensité / Hauteur
    (Z) / Distance (au capteur) / Tag**; the active mode's FAB is tinted. Intensity carries a
    double-thumb `RangeSlider` (left of its FAB) bounding the reflectivity window — points outside
    clamp to the extreme colormap colors. Coloring/filter mapping happens in the shader from the
    packed per-point attributes, so switching modes is a uniform change (no buffer rebuild).
  - Caller owns all state and pushes it to the GL view. In the Library viewer the color mode +
    reflectivity window + noise filter persist via `SettingsRepository`; in Active Scan they are
    session-only. New future view options go here.

#### Phase 2 — scan control + live preview
- **ScanControlViewModel.kt** (`AndroidViewModel`) — polls `/status` + `/scan/status`,
  start/stop scan, sticky last-location, elapsed timer, and owns the live-preview state.
  Shared between Control Room and Active Scan so preview/state survive navigation; never
  stops the scan implicitly.
- **ScanControlScreens.kt** — `ControlRoomScreen` (New Scan + sticky location, resume-running
  banner showing the running scan's algo · profile · overrides; secondary **SSH** and destructive
  **Power OFF** actions) and `ActiveScanScreen`
  - **Start dialog** (`StartScanDialog`): location, SLAM algo (picker only if >1) + profile,
    both prefilled with the Jetson's defaults (`SlamViewModel`), and a collapsible
    *Modifications pour ce scan* field (`key: value` lines). *Démarrer* first calls
    `/slam/{algo}/validate` (stored profile + overrides) and only starts when it passes; errors
    stay listed in the dialog with their line. Overrides are **not** remembered between scans
    (one-off trials; lasting changes belong in a profile). Against a backend without `/slam`
    the SLAM fields are hidden and the scan starts with the Jetson's own config.
  - **Starting a scan takes ~6 s server-side** (`app.py` waits 3 s after spawning the Livox
    driver, 2 s after SLAM and 1 s after the bag recorder, checking each is still alive; the
    waits are non-blocking, so polls keep answering meanwhile). The Control Room
    therefore shows a `StartingScanCard` with stage text timed to that real sequence, and the
    New Scan button becomes a disabled spinner — the VM's `phase == Starting` guard already
    blocked a double `POST /scan/start`, but the button used to still look tappable.
  - **Stopping is the slowest thing in the app** (`/scan/stop` can take ~60 s worst case:
    `map_save` up to 30 s, the `.pcd` size-stability wait up to 10 s, then SIGINT of
    bag/SLAM/driver at up to 8 s each). `StoppingScanOverlay` covers the Active screen with a
    scrim that **consumes pointer events** — the live 3D view underneath would otherwise read
    as a still-running scan — plus stage text on the same real sequence, and a `BackHandler`
    that swallows Back until it resolves.
  (full-screen live preview, HUD: name/elapsed/odom·cloud health/point count/link state,
  **Stop & Save** → summary → optional jump to the notes form).
  - **Power OFF** confirms, then `POST /system/shutdown`; disabled while disconnected or a scan
    runs (backend also returns 409 → "stop the scan first"). On success the VM enters a terminal
    `shuttingDown` state — the lost link is shown gracefully, not as an error.
  - **SSH** is a *handoff helper only* (no embedded terminal): a dialog with host/port 22/editable
    persisted username (default `orin4slam`), "Copy `ssh user@host`", and "Open in SSH app"
    (`ssh://` intent, graceful toast fallback if no client is installed).
- The Control Room ↔ Active Scan poll loop is **reference-counted** (`startPolling`/`stopPolling`)
  so the navigation transition (both screens briefly alive) can't cancel the shared job. The
  live point counter / "receiving" indicator run on a **separate UI ticker** tied to
  `attachPreview`/`detachPreview`, decoupled from network polling, so they track the accumulating
  `PreviewCloud` directly.
- **PreviewCloud.kt** — accumulates streamed frames into one pre-allocated direct `ByteBuffer`
  of **16-byte interleaved records** (xyz float + reflectivity/tag/line/reserved bytes — the
  `USC1` layout), with voxel dedup (0.1 m, `LongHashSet` — primitive open addressing, 8 B/slot
  instead of ~60 B for a boxed `HashSet<Long>`) and a 3M point cap, so a long scan stays
  renderable. At the cap it **coarsens in place** (see TODOs). Producer appends with absolute
  puts and tracks running world bounds; every mutation holds the object's monitor, and the GL
  thread takes the same monitor while uploading. `generation` is bumped whenever `[0, pointCount)`
  is rewritten (`coarsen`/`clear`) so the GL side knows to re-upload everything.
- **PreviewStreamManager.kt** — owns the `/ws/preview` WebSocket: validates the `USC1` magic and
  decodes frames off the main thread (handing the record block straight to `PreviewCloud`),
  applies pose, and auto-reconnects with backoff while `/scan/status` says a scan is running.
- The **same renderer** draws the preview: `MyGLRenderer`/`MyGLSurfaceView` have a `liveMode`
  that reads `previewSource: PreviewCloud` each frame and draws a pose marker (also fed to the
  distance-mode `u_Sensor`); the orbit camera and the coloring pipeline are shared with the file
  viewer.

#### OpenGL viewer + orbit camera
- **MyGLSurfaceView.kt / MyGLRenderer.kt** — OpenGL ES 2.0 point-cloud renderer (depth-tested
  `GL_POINTS`). Both the saved-file buffer and the live `PreviewCloud` are **interleaved 16-byte
  records**: `a_Position` = 3 floats at offset 0, `a_Attribs` = 4 normalized `GL_UNSIGNED_BYTE`
  at offset 12, stride 16. They are drawn from **chunked VBOs** (`PointChunks.kt`, 65 536 points
  = 1 MB per VBO), not client-side arrays: full chunks are never touched again, only the tail
  chunk takes new points. One big VBO would be wrong on Mali (Pixel 8 = Mali-G715): a partial
  `glBufferSubData` on a buffer still in flight copy-on-writes ("ghosts") the *whole* buffer.
  Uploads happen before any draw in the frame (Mali prefers updates batched ahead of draws).
  The CPU copy is kept: `GLSurfaceView` doesn't preserve the EGL context on pause, so
  `onSurfaceCreated` calls `chunks.invalidate()` and everything re-uploads. `PointChunks.draw`
  unbinds `GL_ARRAY_BUFFER` after — the overlays (circles, ruler, trajectory) still use client
  arrays, where a bound VBO would turn their pointers into offsets. The renderer also feeds
  `RenderStats` (fps, worst frame, upload rate), forwarded into the health log as `ph_*`.
  **Loop-closure SLAM** (not FAST-LIO today) would need either a backend `reset` + corrected map
  (→ `clear()` → full re-upload, works as is) or chunks aligned to SLAM submaps with a per-chunk
  model matrix (a correction = updating matrices, no re-upload). The PCD parser reads a binary `.pcd` (8 floats/pt;
  the 4th float = Livox intensity → the reflectivity byte, tag/line = 0) and computes the cloud's
  bounding box for Frame-All. **Coloring** (`ColorMode` uniform `u_ColorMode`) and the **tag noise
  filter** (`u_NoiseFilter`, discard in the fragment shader) are done entirely in the shaders from
  the packed attributes — mode/threshold changes are uniform updates, never a buffer rebuild. The
  intensity colormap is a hard-coded turbo approximation with a reflectivity low/high window
  (`u_ReflBounds`); height/distance normalize against the cloud bounds / sensor pose. Marker
  circles and the axis ruler force `u_ColorMode=0`/`u_NoiseFilter=0` so overlays stay solid-color.
- **OrbitCamera.kt** — the single-state camera (the camera rebuild). State is one coherent set:
  `pivot`, `distance`, `yawDeg`, `pitchDeg` (no roll). Eye = `pivot + distance·dir` where `dir`
  is **Z-up spherical** (yaw around +Z, pitch = elevation; singularity only at ±90°, guarded by
  the ±89° clamp). It always looks at the pivot with a fixed `worldUp` (currently **Z** — flip
  the constant if the frame changes). Dolly/pan speeds and the near/far clip planes all scale
  with `distance`. `orthographic` flag switches `projectionMatrix` to an FOV-matched ortho frustum.
  **FPV-ready**: a future FPV mode is just "distance → ~0, drive the pivot" — do not split this
  into separate cameras.
- **Gestures** (all 1:1, no smoothing; handled in `MyGLSurfaceView`, forwarded to the renderer
  via `queueEvent` so camera mutation stays on the GL thread):
  - one-finger drag → **orbit** (yaw wraps; pitch clamps ±89°) — unless the viewer's **control-mode
    toggle** (`MyGLSurfaceView.setPanMode`) is in pan mode, in which case one-finger drag **pans the
    pivot** instead (same behavior as a two-finger pan). The toggle is a bottom-left sub-button in
    `PCDViewerScreen` (orbit icon ⇄ move icon), session-only (not persisted);
  - two-finger pinch → **dolly** (physical move along the view axis, not FOV; clamped at
    `OrbitCamera.MIN_DISTANCE` so it stops just short of the pivot and never flips the view);
  - two-finger drag → **pan**: left/right slides along the camera's screen-right; up/down **walks
    the pivot along the cave floor** (view direction projected onto the ground plane — fingers
    down = forward), so tilt never drives you into the floor;
  - **double-tap-then-drag → live Z-slide**: the still-down finger's vertical drag slides the
    pivot along world-up (Z) in real time (`OrbitCamera.moveVertical`); double-tap without a drag
    does nothing. (The old depth-ray teleport was removed.)
  - Only **Frame-All** ("Tout afficher") animates (~0.3 s ease-out); it refits the whole cloud.
  - **Follow** (live toggle, session-only): each frame `OrbitCamera.followPivot` eases the
    pivot toward the pose marker (exponential, `FOLLOW_TAU_MS` = 250 ms, frame-rate independent).
    Only the pivot is driven — yaw/pitch/distance stay the user's, so orbit and pinch are free.
    Pan / Z-slide still move the pivot: the magnet holds off while they run
    (`lastPivotGestureMs`) and pulls back 200 ms after the finger goes still. It yields to a
    Frame-All animation (which then reads as "zoom out around me"). The magnet's own motion
    re-baselines `prevPivot`, so it does **not** light the axis ruler while walking.
- **AxisRuler.kt** — yellow 3-axis "ruler" (lines through the pivot + meter tick marks at fixed
  world graduations, so a moving pivot visibly slides through them). Step adapts to zoom
  (1 m → 10 m → 100 m → 1 km…) to bound the tick count. **Decoupled from gestures**: the renderer
  detects pivot motion frame-to-frame and shows the ruler (0.7 s fade-out), so anything that moves
  the pivot lights it up. The **Repères** toggle forces it always-on.

### Data Flow

1. App starts on `ScanLibraryScreen`. The ViewModel loads the cached list, polls `/status`,
   and on first successful contact fetches `/scans` (also written to the offline cache).
2. Pull-to-refresh re-fetches `/scans`. Offline, the cached list still renders.
3. **Download**: tapping Download streams `/scans/{name}/pcd` to
   `Documents/UnderScanner/Scans/<name>.pcd` (public storage) with progress; on success the row
   switches to **Open**.
4. **Open**: navigates to `pcdViewer/<name>.pcd`; `MyGLRenderer` parses and renders the
   local file. Works offline once downloaded.
5. **Config**: `GET /scans/{name}/config` shown read-only (monospaced, copyable).
6. **Notes**: `GET` populates the form; **Save** does `PUT` with the form as a JSON object.

**Live scan (Phase 2):** Control Room → New Scan (validate algo/profile/overrides, then
`POST /scan/start`; the Jetson writes `configs/<scan>.yaml` and runs SLAM on it) → Active Scan opens the
`/ws/preview` WebSocket; binary frames accumulate into `PreviewCloud` (rendered live), pose
updates the position marker, the HUD reflects `/scan/status`. **Stop & Save**
(`POST /scan/stop`) closes the socket, shows the pcd/bag summary, and the finished scan appears
in the Scan Library. Leaving the Active screen closes the socket but the scan keeps running on
the Jetson; re-entering reconnects; a dropped socket auto-reconnects with backoff.

## Network Configuration

- Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `MANAGE_EXTERNAL_STORAGE` (All-files access,
  so scans can be written to public `Documents/UnderScanner/Scans`).
- `res/xml/network_security_config.xml` permits cleartext HTTP to any host (dev app; the
  Jetson is plain HTTP on its own access point).

## Out of Scope (future phases — do not build here)

- Multi-scan concurrency (one active scan; the backend enforces 409).
- Bag downloading (bag size is display-only).
- Editing/re-running bags; offline scan-start queue.
- Auth/login.

## Known TODOs and Future Work

- **Camera FPV mode** (Phase 2 of the camera rebuild): `OrbitCamera` is architected for it
  ("distance → ~0, pivot glued to camera") — add it on top of the existing state, don't fork
  a second camera. No roll.
- Per-point coloring (intensity/height/tag) + the tag noise filter + the LiDAR-path trajectory
  polyline are **done** (see the OpenGL viewer / trajectory sections). Possible follow-ups: a
  calibrated (distance-normalized) intensity; a correct **distance-to-sensor** color mode (the
  enum/shader branch exist but the button is hidden — it currently renders as a gradient around
  the pivot, which is wrong); and persisting `tag` into the saved `.pcd` (the saved file carries
  reflectivity but not tag/line).
- When the preview hits its 3M point cap, `PreviewCloud` **coarsens in place** (voxel size
  doubled, re-dedup + compaction, down to ~60 % of the cap; HUD shows `· voxel X m`). Before
  this the cap silently froze the cloud mid-scan while the pose/trajectory kept moving. Only
  past a 1.6 m voxel does it drop new voxels (`(max)`). Observed growth ≈ 3 000 new 0.1 m
  voxels/s (1M reached in ~5–6 min). The 3M cap is untested on-device: check the `ph_fps` /
  `ph_frame_max_ms` charts after a long scan, and drop `capacity` to 2M if they sag.
- Possible follow-up: per-chunk bounding boxes → frustum culling (temporal chunks are
  spatially compact when walking a cave).
- An offline notes-edit queue (editing currently requires a connection).
