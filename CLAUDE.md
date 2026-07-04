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

A FastAPI service runs at `http://<jetson-ip>:8000`. The base URL is editable in the app's
Settings (the hotspot-assigned IP changes between sessions); default is in
`SettingsRepository.DEFAULT_BASE_URL`.

| Method | Path | Purpose | Returns |
|---|---|---|---|
| GET | `/status` | Readiness heartbeat | `{status, hostname, time, data_root, version}` |
| GET | `/scans` | List all scans + metadata | `{scans: [ScanInfo]}` (newest first) |
| GET | `/scans/{name}/pcd` | Download point cloud | binary `application/octet-stream` |
| GET | `/scans/{name}/config` | Read config (read-only) | `text/plain` (YAML) |
| GET | `/scans/{name}/notes` | Read notes form | JSON object (`{}` if none) |
| PUT | `/scans/{name}/notes` | Save notes form | `{ok: true}` |
| POST | `/scan/start` | Start a scan; body `{location}` | `{scan, running, started_at}`; **409** if running |
| POST | `/scan/stop` | Stop + save | `{scan, running:false, pcd, bag}`; **409** if none |
| GET | `/scan/status` | Poll scan state | `{running, scan, started_at, elapsed_s, odom_ok, cloud_ok}` |
| WS | `/ws/preview` | Live preview stream | binary cloud frames + JSON pose |
| POST | `/system/shutdown` | Power the Jetson off | `{ok}`; **409** if a scan is running |

`ScanInfo` carries `name, date, location, run` plus artifact sub-objects `bag`, `pcd`
(`{present, size_bytes, size_human}`), `config`, and `notes` (`{present, ...}`). For search,
`ScanInfo.notesText` flattens whatever note content the list embeds (`flattenJsonText`); since the
list endpoint typically reports only note *presence*, the ViewModel also fetches each scan's notes
once from `/scans/{name}/notes` and merges the text into the searchable list (cached per session).

**WebSocket `ws://<jetson>/ws/preview`:** binary message = one frame: little-endian `uint32`
N then `N×(x,y,z,intensity)` float32 (16 bytes/point, world frame, accumulate). Text message =
JSON pose `{type:"pose", x, y, z, ...}` (~10 Hz).

### Key Components

- **MainActivity.kt** — entry point; `AppNavHost` starts at `mainMenu` and wires
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
  live in `getExternalFilesDir("Scans")` (this is also where the OpenGL viewer reads from);
  the last `/scans` JSON is cached to `filesDir/scans_cache.json`.

- **SettingsRepository.kt** — persists and normalizes the editable Jetson base URL
  (SharedPreferences `underscanner_settings`).

- **ScanLibraryScreen.kt** — the single Scan Library screen: connection indicator + address
  (→ Settings), pull-to-refresh, one row per scan (bag size, PCD Download/Open + progress,
  Config view, Notes edit). The top bar also has **search** (fuzzy: case-insensitive, separator-
  tolerant so `cave-x`/`cave_x`/`cave x` match; scans name/location/date/notes text) and a
  **sort** menu (date / name both directions, plus PCD size and bag size descending; persisted).
  Both are client-side and work offline. Hosts the read-only **Config** dialog and the **Notes**
  form dialog (`site, issues, free`). Legacy `conditions` / `estimated_length_m` fields are no
  longer shown or written (existing values stay in the Jetson's JSON, just unused).

- **SettingsScreen.kt** — edit/normalize the Jetson base URL; shows live connection state.

- **PCDViewerScreen.kt** — the 3D viewer wrapper: **no top bar** (the GL surface is
  full-screen; Android's back gesture handles leaving), just the **viewer options cluster**
  (`ViewerControls.kt`). The camera is gesture-only (see the OpenGL viewer section); there is
  no camera-style / control-mode / auto-level menu. The **Repères** and **Projection** toggle
  states are read from / written to `SettingsRepository` (`viewerHelpers`,
  `viewerOrthographic`), so they persist across viewer sessions and app restarts.

- **ViewerControls.kt** — `ViewerOptionsCluster`: an icon-only access FAB toggling a panel of
  child actions (pressing any child auto-collapses it). Children: **Tout afficher** (frame-all),
  **Repères** (axis-ruler always-on toggle), **Projection** (perspective ⇄ orthographic toggle);
  toggles are tinted when active. Caller owns the toggle state and pushes it to the GL view.
  Reused by both `PCDViewerScreen` and `ActiveScanScreen`. New future view options go here.

#### Phase 2 — scan control + live preview
- **ScanControlViewModel.kt** (`AndroidViewModel`) — polls `/status` + `/scan/status`,
  start/stop scan, sticky last-location, elapsed timer, and owns the live-preview state.
  Shared between Control Room and Active Scan so preview/state survive navigation; never
  stops the scan implicitly.
- **ScanControlScreens.kt** — `ControlRoomScreen` (New Scan + sticky location, resume-running
  banner; secondary **SSH** and destructive **Power OFF** actions) and `ActiveScanScreen`
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
- **PreviewCloud.kt** — accumulates streamed frames into one pre-allocated direct
  `FloatBuffer` with voxel dedup (0.1 m) and a point cap, so a long scan stays renderable.
  Producer appends with absolute puts; the GL thread reads `[0, pointCount)` lock-free.
- **PreviewStreamManager.kt** — owns the `/ws/preview` WebSocket: decodes little-endian
  binary frames off the main thread, applies pose, and auto-reconnects with backoff while
  `/scan/status` says a scan is running.
- The **same renderer** draws the preview: `MyGLRenderer`/`MyGLSurfaceView` have a `liveMode`
  that reads `previewSource: PreviewCloud` each frame and draws a pose marker; the orbit
  camera is shared with the file viewer.

#### OpenGL viewer + orbit camera
- **MyGLSurfaceView.kt / MyGLRenderer.kt** — OpenGL ES 2.0 point-cloud renderer (depth-tested
  `GL_POINTS`). The PCD parser reads a binary `.pcd` (8 floats/point; renders x,y,z) from
  `LocalScanStorage` and computes the cloud's bounding box for Frame-All. The vertex/draw
  pipeline is unchanged from the original; only the camera was rebuilt.
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
   `getExternalFilesDir("Scans")/<name>.pcd` with progress; on success the row switches to
   **Open**.
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

- Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`.
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
- Use the streamed `intensity` (4th float) for per-point coloring (shader currently uses a
  single uniform color; intensity is parsed but ignored).
- When the preview hits its point cap it stops adding new voxels — consider coarsening or
  dropping oldest instead.
- An offline notes-edit queue (editing currently requires a connection).
