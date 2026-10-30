# Claude Code Task — Underscanner App: Control Room buttons + quick fixes

---

## 0. How to proceed

Explore the relevant parts of the project first and report findings + a short plan before
coding: the **"Salle de Contrôle" (Control Room)** screen (currently only a "Nouveau scan"
button), the **Scan Library** screen and its top toolbar, the **live preview HUD** (the
top-left point counter), the networking layer, and the settings store (Jetson base URL).
**Match existing conventions.** All of this is additive — do not remove existing features.

The backend already supports everything needed (see endpoints noted per task).

---

## TASK A — Power OFF button (Control Room)

Add a **Power OFF** button to the Salle de Contrôle screen, alongside "Nouveau scan".

- Calls `POST /system/shutdown` on the Jetson.
- **Only enabled when connected AND no scan is running.** (The backend also guards this and
  returns **409** if a scan is running — handle that response by telling the user to stop the
  scan first.)
- Requires a **confirmation dialog** before sending: explain that the Jetson will power down
  and that **the app cannot turn it back on — physical access is needed to restart it.**
- After a successful call, the connection will drop as the Jetson halts. Show a clear
  terminal state ("Jetson is shutting down…") and move the connection indicator to
  disconnected. Do not treat the lost connection as an error in this case.
- Style it as a deliberate/destructive action (visually distinct from "Nouveau scan", e.g.
  secondary placement, warning color), so it isn't tapped by reflex.

## TASK B — SSH helper button (Control Room)

Add an **SSH** button to the Salle de Contrôle screen. **Do NOT build an embedded terminal
emulator.** Build a lightweight helper that makes connecting with a real SSH app (Termius) easy.

On tap, open a small dialog/sheet showing:
- **Connection details:** host = the configured Jetson IP/host (reuse the Phase 1 setting),
  port 22, and a **username** field (store in settings, default `orin4slam`).
- A **"Copy SSH command"** action that copies `ssh <user>@<host>` to the clipboard.
- An **"Open in SSH app"** action that fires an `Intent` with an `ssh://<user>@<host>` URI to
  launch an installed SSH client (e.g. Termius). If no app handles it, fall back gracefully:
  a toast/snackbar suggesting the user install an SSH client (e.g. Termius), and keep the
  copy-command option available.

Keep it simple and robust — its only job is to hand off to a proper SSH client with the right
details prefilled.

## TASK C — Quick fix: live preview point counter stuck at 0

In the live preview HUD, the **point counter in the top-left stays at 0** even though the
preview is clearly accumulating points and rendering. Diagnose and fix it.

- The counter should reflect the **current accumulated preview point count** and update as
  binary cloud frames arrive and are added to the renderer buffer.
- Likely causes to check: the HUD reads a value that's never updated after decode; the count
  is updated on a background/GL thread but the UI state isn't notified on the main thread; or
  the counter is bound to the wrong source (e.g. a per-frame variable that's reset, or the
  raw socket rather than the post-dedup accumulated buffer).
- Fix so the displayed number tracks the actual points being rendered (post client-side
  voxel-dedup), updating smoothly during the scan.

## TASK D — Quick fix: sort control in Scan Library toolbar

Add a **sort** control to the Scan Library top toolbar:
- Sort by **Date** (newest-first / oldest-first) and by **Name** (alphanumeric A–Z / Z–A).
- Default = newest-first (current behavior).
- Persist the user's chosen sort across app launches.
- Sorting operates on the currently displayed list and works offline (on cached data).

## TASK E — Quick fix: search in Scan Library toolbar

Add a **search** affordance (magnifying-glass icon) to the Scan Library top toolbar that
opens a search field and filters the list live as the user types.

- **Non-strict / fuzzy matching:** case-insensitive, partial substring matches, and tolerant
  of separators (treat `-`, `_`, and spaces as interchangeable so "caveX", "cave-x",
  "cave x" all match).
- **Search scope:** scan `name`, `location`, `date`, **and notes text**. The backend now
  includes notes text in each scan entry as `notes.text` (a string) in the `/scans` response —
  search across that too.
- Filtering is purely client-side and works offline against cached data.
- Combine sensibly with the sort control (search filters, sort orders the filtered result).

---

## OUT OF SCOPE

- No embedded SSH terminal emulator (Task B is a launcher/helper only).
- No changes to scan control, live streaming protocol, or Phase 1 file features beyond the
  toolbar additions described.
- No battery/hardware/power-button work.

## ACCEPTANCE CRITERIA

- **A:** Power OFF appears in Salle de Contrôle, is disabled during a scan / when
  disconnected, confirms before sending, calls `/system/shutdown`, handles 409, and shows a
  clean shutting-down state when the connection drops.
- **B:** SSH button opens a dialog with host/user/port, copies `ssh user@host`, and launches
  an installed SSH app via intent (graceful fallback if none); username is editable and
  persisted. No embedded terminal.
- **C:** During live preview the top-left point counter shows a non-zero, updating count that
  matches the rendered accumulated cloud.
- **D:** Scan Library can be sorted by date and by name (both directions); choice persists.
- **E:** Scan Library search filters live, fuzzily, across name/location/date/notes text, and
  works offline.
- All existing Phase 1 + Phase 2 features remain intact.

---

Start by exploring the relevant screens and giving me your findings + a brief plan
(especially your diagnosis of the stuck point counter in Task C) before coding.
