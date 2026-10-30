# Claude Code Task — Underscanner Android App: Scan Library (Phase 1)

Paste this as your task in Claude Code, run from the root of the Android Studio project.

---

## 0. Read this first — how to proceed

Before writing anything, **explore the existing project** and report back what you find:
- the language and UI toolkit in use (Kotlin/Java, Jetpack Compose or XML Views),
- the existing **OpenGL PCD viewer** (find it, understand how it loads and renders a `.pcd`),
- the existing **file-browsing UI** (the part to be replaced),
- networking, navigation, and module structure.

Then propose a short plan before implementing. **Match the project's existing conventions**
unless a part is being replaced.

**Keep:** the OpenGL PCD viewer. It works and is the payoff of this screen — when the user
opens a downloaded scan, it opens in that viewer. Integrate with it; do not rewrite it.

**Scrap:** the current file-browsing UI entirely. And the current server handling. It was a throwaway prototype and does not
match the architecture below. Remove its screens, adapters, models, and any
local/on-Jetson tab split. Replace with the single Scan Library screen described here.

---

## 1. Project context (the bigger picture)

This app controls a DIY handheld LiDAR cave scanner. A Jetson on the device runs the SLAM
and stores each scan's artifacts. The phone connects to the Jetson over WiFi (the phone is
the hotspot; the Jetson joins it) and is the device's only screen and control surface.

This task builds **only the file-browsing half** (Phase 1): browse scans, download point
clouds, view config, edit notes. Live scan control and live 3D preview are **future phases —
do not build them now** (see §8).

## 2. The Jetson backend (already built — this is the contract)

A FastAPI service runs on the Jetson at `http://<jetson-ip>:8000`. The app is a client of it.
For now jetson-ip: 10.75.93.211

| Method | Path | Purpose | Returns |
|---|---|---|---|
| GET | `/status` | Readiness heartbeat | `{status, hostname, time, data_root, version}` |
| GET | `/scans` | List all scans + metadata | `{scans: [ScanInfo]}` (newest first) |
| GET | `/scans/{name}/pcd` | Download point cloud | binary `application/octet-stream` |
| GET | `/scans/{name}/config` | Read config (read-only) | `text/plain` (YAML) |
| GET | `/scans/{name}/notes` | Read notes form | JSON object (`{}` if none) |
| PUT | `/scans/{name}/notes` | Save notes form | `{ok: true}` |

`ScanInfo` shape:
```json
{
  "name": "2026-06-29_apartment_01",
  "date": "2026-06-29",
  "location": "apartment",
  "run": "01",
  "bag":    { "present": true,  "size_bytes": 1288490188, "size_human": "1.2 GiB" },
  "pcd":    { "present": true,  "size_bytes": 41943040,   "size_human": "40.0 MiB" },
  "config": { "present": true },
  "notes":  { "present": false }
}
```

## 3. Goal of this phase

A single **Scan Library** screen: one unified list of every scan known from the Jetson,
that also works offline from a local cache. From it the user can download a scan's `.pcd`
(then open it in the existing viewer), read the config, and fill in a notes form. **One list,
no "local vs Jetson" tabs** — connection state changes which actions are live, not which
list is shown.

## 4. Architecture

- **Networking:** Retrofit + OkHttp (or the project's existing client) against the contract
  in §2. Configurable base URL (see §7). Streaming download for the `.pcd` (it can be tens
  of MB — write to disk as it downloads, show progress, don't buffer in memory).
- **Local cache:** Room (or the project's existing persistence). Cache the last `/scans`
  result so the list renders offline. Persist, per scan, whether its `.pcd` has been
  downloaded to this phone and where.
- **Source-of-truth split:** the Jetson is the source of truth for the *scan list*; the
  phone is the source of truth for *what it has downloaded locally*. "Downloaded" = the file
  exists in the app's local storage, not a server flag.
- **Downloaded PCDs** live in app-scoped storage; opening one launches the existing OpenGL
  viewer with that local file.

## 5. The Scan Library screen — UX spec

**Top bar / connection area:**
- Connection indicator driven by polling `GET /status` (e.g. every few seconds while the
  screen is foregrounded): Connected / Connecting / Offline.
- Shows the current Jetson address; tapping it opens Settings (§7).
- Pull-to-refresh re-fetches `/scans` when connected.

**The list (newest first):** one row per scan, showing:
- `name` (primary), with `date` and `location` legible.
- **Bag size** (`bag.size_human`) — informational; bags are not downloadable in this phase.
- **PCD**: if `pcd.present`, show its size and either a **Download** action or, if already
  downloaded locally, an **Open** action (→ existing viewer) plus a "downloaded" indicator.
- **Notes**: an edit affordance; show whether notes exist (`notes.present` or local edit).
- **Config**: a "view config" affordance, shown when `config.present`.

**Row actions:**
- **Download PCD** (online only): streams `/scans/{name}/pcd` to local storage with a
  progress indicator; on success mark downloaded and switch the action to **Open**.
- **Open PCD** (works offline once downloaded): opens the local `.pcd` in the OpenGL viewer.
- **View Config** (online): fetch `/scans/{name}/config`, show the YAML read-only
  (monospaced, scrollable, copyable). No editing.
- **Edit Notes** (see §6).

**States to handle:** loading, empty (no scans), offline-with-cache, offline-no-cache,
download in progress, download failure (retry), and a scan present locally but absent from
the latest server list (keep showing it as a local/downloaded item).

## 6. Notes form

The notes endpoint stores an **arbitrary JSON object** — the app owns the form definition.
Implement a simple structured form the user fills at the end of a scan. Suggested fields
(adjust as sensible):
- `site` (short text) — site / location description
- `conditions` (short text) — e.g. wet / dry / tight
- `estimated_length_m` (number, optional)
- `issues` (multiline) — anything that went wrong during the scan
- `free` (multiline) — free notes

Behavior: **Edit Notes** does `GET /scans/{name}/notes`, populates the form (empty if `{}`),
and on save does `PUT` with the form serialized to a JSON object. Editing requires
connection in this phase (no offline queue needed yet, but structure the code so one could
be added later). The backend adds its own `_updated` timestamp; don't fight it.

## 7. Connection settings

- A Settings entry for the **Jetson base URL** (host + port, default `http://192.168.43.1:8000`
  or similar — make it obvious it's editable). The hotspot-assigned IP changes, so this must
  be easy to edit. Persist it.
- Validate/normalize input (scheme, trailing slash). Surface clear connection errors.

## 8. OUT OF SCOPE — do not build in this phase

Explicitly do **not** implement (they are later phases and must not be started here):
- Starting/stopping/launching scans from the app (no scan control).
- Live 3D preview / real-time point cloud streaming / live pose.
- Any WebSocket. Phase 1 is plain HTTP request/response only.
- Bag downloading (only PCDs download in this phase; bag size is display-only).
- Auth/login.

If the existing app has stubs for any of the above, leave them untouched or cleanly disabled
— do not expand them.

## 9. Acceptance criteria

- The old file-browsing UI (and any local/Jetson tab split) is gone; one Scan Library screen
  remains.
- The old server handling is gone.
- With the Jetson reachable: the list populates from `/scans`, sizes display, a PCD downloads
  with progress, and opening it renders in the existing OpenGL viewer.
- Config opens read-only; notes load, edit, save, and persist across app restarts.
- With the Jetson unreachable: the list still renders from cache, downloaded scans still open
  in the viewer, and live-only actions are clearly disabled rather than crashing.
- Jetson address is editable in Settings and respected.
- The OpenGL PCD viewer is reused, not rewritten.

---

Start by exploring the project and giving me your findings + a brief implementation plan
before coding.
