# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

TheUnderScannerApp is the Android control surface for a DIY handheld LiDAR cave scanner.
A Jetson on the device runs the SLAM and stores each scan's artifacts; the phone connects
to it over WiFi (the phone is the hotspot, the Jetson joins it) and is the device's only
screen. The app browses scans, downloads point clouds, views config, edits notes, and
visualizes `.pcd` files in a custom 3D OpenGL viewer.

Current version: `v0.1.0` (Early development stage)

**Scope: Phase 1 (file browsing) + Phase 2 (scan control + live preview) are done.** The app
can browse/download/view scans, and start/stop a scan with a live accumulating 3D preview.

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

A FastAPI service runs at `http://<jetson-ip>:8000`. The base URL is editable in the app's
Settings (the hotspot-assigned IP changes between sessions); default is in
`SettingsRepository.DEFAULT_BASE_URL`.

| Method | Path | Purpose | Returns |
|---|---|---|---|
| GET | `/status` | Readiness heartbeat | `{status, hostname, time, data_root, version}` |
| GET | `/scans` | List all scans + metadata | `{scans: [ScanInfo]}` (newest first) |
| GET | `/scans/{name}/pcd` | Download point cloud | binary `application/octet-stream` |
| GET | `/scans/{name}/trajectory` | Download LiDAR path (`UTR1`) | binary `application/octet-stream`; **404** if none |
| GET | `/scans/{name}/config` | Read config (read-only) | `text/plain` (YAML) |
| GET | `/scans/{name}/notes` | Read notes form | JSON object (`{}` if none) |
| PUT | `/scans/{name}/notes` | Save notes form | `{ok: true}` |
| GET | `/scans/{name}/health` | Per-scan health log | JSONL body; **404** if none |
| GET | `/system/health` | Live Jetson telemetry | latest cached sample + `static` |
| POST | `/scan/start` | Start a scan; body `{location}` | `{scan, running, started_at}`; **409** if running |
| POST | `/scan/stop` | Stop + save | `{scan, running:false, pcd, bag}`; **409** if none |
| GET | `/scan/status` | Poll scan state | `{running, scan, started_at, elapsed_s, odom_ok, cloud_ok}` |
| WS | `/ws/preview` | Live preview stream | binary cloud frames + JSON pose |
| POST | `/system/shutdown` | Power the Jetson off | `{ok}`; **409** if a scan is running |

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
as a normalized `GL_UNSIGNED_BYTE` vec4 attribute (zero-copy). `tag` is bit-packed Livox noise
info (bits 1-0 spatial, bits 3-2 return-intensity; 0/1/2/3 = normal/high/medium/low-confidence
noise) and is often all-zero with the current FAST-LIO (harmless). Text message = JSON pose
`{type:"pose", x, y, z, ...}` (~10 Hz). `GET /preview/format` also returns this layout as JSON
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

- **ScanModels.kt** — `ScanInfo`, `ScanArtifact`, `StatusInfo`, and the `ConnectionState`
  sealed type (Connecting / Connected / Offline).

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
  (→ Settings), pull-to-refresh, one row per scan (bag size, PCD Download/Open + progress,
  Config view, Notes edit). The top bar also has **search** (fuzzy: case-insensitive, separator-
  tolerant so `cave-x`/`cave_x`/`cave x` match; scans name/location/date/notes text) and a
  **sort** menu (date / name both directions, plus PCD size and bag size descending; persisted).
  Both are client-side and work offline. Hosts the read-only **Config** dialog and the **Notes**
  form dialog (`site, issues, free`; full-width via `usePlatformDefaultWidth = false`, with a
  centred *Notes / date / scan-name* header, the name marquee-scrolling rather than truncating).
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
  load / power / SLAM health, all sharing the same x domain so a feature sits at the same
  horizontal position across a swipe. A page whose series are all absent from the log is dropped
  entirely, and `odom_ok`/`cloud_ok` dropouts render as shaded bands behind the rate lines. All
  axis text is composed with `Text`, never measured inside the Canvas.

- **SettingsScreen.kt** — edit/normalize the Jetson base URL; shows live connection state.

- **PCDViewerScreen.kt** — the 3D viewer wrapper: **no top bar** (the GL surface is
  full-screen; Android's back gesture handles leaving), just the **viewer options cluster**
  (`ViewerControls.kt`). The camera is gesture-only (see the OpenGL viewer section); there is
  no camera-style / control-mode / auto-level menu. The **Repères** and **Projection** toggle
  states are read from / written to `SettingsRepository` (`viewerHelpers`,
  `viewerOrthographic`), so they persist across viewer sessions and app restarts.

- **ViewerControls.kt** — two clusters, both reused by `PCDViewerScreen` and `ActiveScanScreen`,
  laid out as a bottom-right `Row` (`ColorModeCluster` left of `ViewerOptionsCluster`):
  - `ViewerOptionsCluster`: an icon-only access FAB toggling a panel of child actions (pressing a
    plain child auto-collapses it). Children top→bottom: optional **auto-orbit** (tap-toggle +
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
  banner; secondary **SSH** and destructive **Power OFF** actions) and `ActiveScanScreen`
  - **Starting a scan blocks for 5+ s server-side** (`app.py` sleeps 3 s after spawning the Livox
    driver and 2 s after SLAM, so each is up before the bag recorder starts). The Control Room
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
  `USC1` layout), with voxel dedup (0.1 m) and a point cap, so a long scan stays renderable.
  Producer appends with absolute puts (carrying all four attribute bytes through the dedup) and
  tracks running world bounds; the GL thread reads `[0, pointCount)` lock-free via a float view
  (positions) + the byte buffer (attributes).
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
  records** drawn zero-copy: `a_Position` = 3 floats at offset 0, `a_Attribs` = 4 normalized
  `GL_UNSIGNED_BYTE` at offset 12, stride 16. The PCD parser reads a binary `.pcd` (8 floats/pt;
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

**Live scan (Phase 2):** Control Room → New Scan (`POST /scan/start`) → Active Scan opens the
`/ws/preview` WebSocket; binary frames accumulate into `PreviewCloud` (rendered live), pose
updates the position marker, the HUD reflects `/scan/status`. **Stop & Save**
(`POST /scan/stop`) closes the socket, shows the pcd/bag summary, and the finished scan appears
in the Scan Library. Leaving the Active screen closes the socket but the scan keeps running on
the Jetson; re-entering reconnects; a dropped socket auto-reconnects with backoff.

## Network Configuration

- Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `MANAGE_EXTERNAL_STORAGE` (All-files access,
  so scans can be written to public `Documents/UnderScanner/Scans`).
- `res/xml/network_security_config.xml` permits cleartext HTTP to any host (dev app; the
  Jetson is plain HTTP on the hotspot and its IP changes).

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
- When the preview hits its point cap it stops adding new voxels — consider coarsening or
  dropping oldest instead.
- An offline notes-edit queue (editing currently requires a connection).
