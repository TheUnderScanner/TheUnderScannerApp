# Claude Code Task — Underscanner Android App: Scan Control + Live Preview (Phase 2)

---

## 0. Read this first — how to proceed

Before writing anything, **re-explore the project** (it has changed since Phase 1) and report:
- the current navigation structure and where the **Scan Library** screen (Phase 1) lives,
- how the **OpenGL PCD viewer** loads vertices and renders — you will **reuse this same
  renderer** to draw the live preview, so understand its vertex-buffer input path,
- the existing networking layer (Retrofit/OkHttp) and settings (Jetson base URL).

Then propose a short plan before implementing. **Match existing conventions.**

**Keep:** everything from Phase 1 (Scan Library, downloads, notes, config view) and the OpenGL
viewer. **This phase is additive** — do not remove Phase 1 features.

## 1. Context

Phase 1 (file browsing) is done and working. This phase adds the ability to **start and stop a
scan from the phone** and watch a **live 3D preview** while scanning — the phone is the
device's only screen, so this replaces the monitor + RViz that were used on the bench.

## 2. Backend contract (already built on the Jetson)

Base URL is the configurable Jetson address from Phase 1. New endpoints:

| Method | Path | Purpose | Returns |
|---|---|---|---|
| POST | `/scan/start` | Start a scan. JSON body `{"location": "..."}` (optional) | `{scan, running, started_at}`; **409** if already running |
| POST | `/scan/stop` | Stop + save map/bag/config | `{scan, running:false, pcd, bag}`; **409** if none running |
| GET | `/scan/status` | Poll scan state | `{running, scan, started_at, elapsed_s, odom_ok, cloud_ok}` |
| WS | `/ws/preview` | Live preview stream | binary cloud frames + JSON pose (below) |

**WebSocket `ws://<jetson>/ws/preview`:**
- **Binary message = one preview cloud frame.** Little-endian: a `uint32` point count `N`,
  followed by `N × 4` `float32` values = `(x, y, z, intensity)` per point, 16 bytes/point.
  World-frame points; **accumulate successive frames** to build the preview.
- **Text message = JSON.** `{"type":"pose","x","y","z","qx","qy","qz","qw","t"}` — current
  sensor pose in world frame, ~10 Hz.

## 3. Goal of this phase

Redo a "Main Menu type UI", simple big btns. Are available for now : Scan Library and Lidar Control Room. It's here that user can begin a new scan, see a live accumulating 3D point cloud
with the current sensor position as they walk, and stop+save — after which the finished scan
appears in the library (Phase 1) ready to download. All without a monitor on the Jetson.

## 4. UX spec

**Entry point:** clear "Scan Library" & "Lidar Control Room" btns in new main menu.
In Lidar Control Room, user can start a new scan with clear btn "New Scan"
enabled only when connected (`/status` healthy).

**Start screen / sheet:**
- A `location` text field, **prefilled with the last-used location** (sticky), editable.
- A **Start** button → `POST /scan/start` with `{location}`. On 409, show "a scan is already
  running" and route to the active-scan screen.
- Hint that leaving location blank reuses the last one.

**Active-scan screen (the centerpiece):**
- **Live 3D preview** filling most of the screen, rendered by the **reused OpenGL viewer**:
  - Open the WebSocket on entry. Decode binary frames per §2 and feed points into the
    renderer's vertex buffer.
  - **Accumulate** frames into a single growing preview cloud (the stream sends per-frame
    points, not the whole map). To bound memory, **voxel-dedup on the client** (hash points to
    a grid, ~0.1 m) and cap total preview points (e.g. 300–500k); when capped, drop oldest or
    coarsen. This keeps a long scan renderable on a phone.
  - Use `intensity` (4th float) for point coloring if the viewer supports it; else ignore.
  - Apply the **pose** messages: at minimum show the current position (e.g. a marker/axis at
    x,y,z); optionally let the camera follow it. Camera should also be free-orbitable by the
    user (reuse existing camera controls).
- **HUD overlay:** scan name, a running **elapsed timer** (from `started_at`), live health
  indicators driven by `/scan/status` polling and/or stream liveness (`odom_ok`, `cloud_ok`,
  e.g. "receiving data" vs "no data — move the sensor"), and an approximate preview point count.
- **Primary action: "Stop & Save"** → `POST /scan/stop`. Show a progress/working state (the
  backend saves the map + flushes the bag, which takes a few seconds). On success, close the
  WebSocket and show a short **post-scan summary** (scan name, pcd size, bag size from the
  response), then return to the Scan Library with the new scan present.
- Handle the case where the user backgrounds/leaves: the scan keeps running on the Jetson
  (it's server-side). Re-entering should reconnect the WebSocket and resume the preview; use
  `/scan/status` to restore elapsed/running state. **Never** let leaving the screen silently
  stop the scan.

**Reconnection:** if the WebSocket drops mid-scan, auto-reconnect with backoff and keep the
accumulated preview; show a transient "reconnecting" state. Use `/scan/status` as the source of
truth for whether a scan is still running.

## 5. Notes form timing (small tie-in to Phase 1)

After Stop & Save, optionally offer "Add notes" that jumps straight to the Phase 1 notes form
for the just-finished scan (the user fills the field-survey form at the end of a scan). Reuse
the existing notes UI; don't rebuild it.

## 6. Technical notes

- **WebSocket client:** use OkHttp's WebSocket (already in the project) or the project's
  existing socket lib. Binary frames arrive as `ByteString`/`byte[]`; parse little-endian
  (`ByteBuffer.order(LITTLE_ENDIAN)`): read `int` count, then `count*4` floats.
- **Threading:** decode and dedup off the main thread; hand prepared vertex data to the GL
  thread. Don't block the UI on frame parsing.
- **Renderer reuse:** the live preview and the saved-PCD view should share one renderer/vertex
  pipeline. Live mode just feeds it a continuously updated buffer instead of a one-shot file.
- **Lifecycle:** open WS in onStart/onResume of the active-scan screen, close on leaving
  *that screen* (not on app background if a scan is active — reconnect instead). Stopping the
  scan is an explicit user action only.

## 7. OUT OF SCOPE — do not build

- No changes to power/battery/hardware anything.
- No multi-scan concurrency (one active scan; the backend enforces 409).
- No editing or re-running of bags; no offline scan-start queue.
- No new bag-download feature (still PCD-only, from Phase 1).
- Don't reimplement the OpenGL renderer — reuse it.

## 8. Acceptance criteria

- "New Scan" with a location starts a scan (`/scan/start`), and the app transitions to the
  active-scan screen.
- While scanning and moving the sensor, the live preview accumulates a recognizable 3D cloud in
  the reused viewer, the elapsed timer runs, health indicators reflect `odom_ok`/`cloud_ok`,
  and the current position updates from pose messages.
- The preview stays bounded/renderable over a multi-minute scan (client dedup + cap working).
- "Stop & Save" stops the scan, shows the summary with pcd/bag sizes, and the finished scan
  appears in the Scan Library, downloadable.
- Leaving and returning to the active-scan screen reconnects and resumes; a dropped WS
  auto-reconnects; the scan is never stopped except by explicit user action.
- Phase 1 features remain fully intact.

---

Start by re-exploring the project and giving me your findings + a brief implementation plan
(especially how you'll feed the live stream into the existing OpenGL renderer) before coding.
