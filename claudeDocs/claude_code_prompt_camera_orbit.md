# Claude Code Task — Underscanner App: Rebuild Camera System (Orbit mode)


---

## 0. How to proceed

Explore the existing OpenGL point-cloud rendering and camera code first, and report findings
+ a short plan before coding. In particular identify: the renderer and how it receives
vertices, the current camera/view-projection matrix code, and all current touch/gesture
handling for the camera.

**Then SCRAP the entire existing camera system** — every current camera control, gesture
handler, and view/projection setup tied to navigation. All current attempts are unsatisfactory
and we are rebuilding from the ground up, not modifying them. Keep the **renderer / vertex
pipeline itself** (how points are drawn) — only the camera and its controls are being replaced.

This is **Phase 1 of the camera rebuild: ORBIT MODE ONLY.** FPV and other modes come later and
must NOT be built now. But architect the camera cleanly so FPV can be added later without
another rewrite (see §2).

This camera is used both for viewing saved `.pcd` files and for the live preview — the same
camera system must serve both.

## 1. Context

This is a handheld LiDAR cave scanner's app. Users explore point clouds of caves — which are
enclosed, tube-like, have no collision (points aren't solid), and span a huge scale range
(centimeter wall detail up to a whole cave tens/hundreds of meters across). The camera must
move intuitively across that entire range and reach any position and any viewing angle.

## 2. Camera model (architecture — important)

Model the camera with a single coherent state so modes are not separate cameras:

- **pivot**: a 3D point in world space (what the camera looks at / orbits)
- **distance**: scalar, camera's distance from the pivot along the view direction
- **yaw, pitch**: orbit angles around the pivot

Camera position is derived from `pivot + distance + (yaw, pitch)`; the camera always looks at
the pivot in orbit mode. Build it so a future FPV mode is just "distance → 0 / pivot glued to
camera" — i.e. don't hardcode assumptions that prevent that later. Do not implement FPV now.

**Roll is omitted entirely** — there is no roll control and the horizon stays level.

## 3. Orbit mode — exact gesture spec

**One-finger drag → orbit (rotate around the pivot).**
- Horizontal drag = yaw: rotates the camera around the pivot left/right. **Fully continuous /
  wraps infinitely** (no limit, no snapping).
- Vertical drag = pitch: tips the camera over/under the pivot. **Option A — clamp the pitch**
  to approximately **±89°** so it never reaches the vertical singularity. The result must be
  **flip-free and continuous right up to the clamp** — no view inversion, no mirroring, no
  "reverse clipping," and the horizon stays level throughout. The clamp is a soft stop near
  vertical, not a glitch.
- The camera always keeps looking at the pivot; distance is unchanged by this gesture.
- Tracks the finger **1:1 with no smoothing/damping** — must feel glued to the finger.

**Pinch → dolly (change distance).**
- Spread fingers = increase distance (camera retreats from pivot). Pinch = decrease distance,
  and the camera is allowed to pass **through** the pivot and out the far side (no clamp at 0
  that blocks going through — this is how the user reaches inside-the-rock / pull-way-back views).
- This is **physical camera movement along the view axis, NOT FOV zoom.** FOV stays constant.
- **Distance range must be huge** (e.g. from a few centimeters to well beyond the cloud's full
  extent — on the order of 1000:1+), so the user can go from nose-to-wall to whole-cave.
- Tracks the pinch 1:1, no smoothing.

**Two-finger drag → pan (translate the pivot).**
- Moves the pivot (and camera with it) parallel to the screen plane — view angle and distance
  unchanged, the whole rig slides. This repositions *what* is being orbited.
- Tracks 1:1, no smoothing.

**Double-tap-then-drag → depth-resolved teleport (relocate the pivot in depth).**
- Gesture: a **double-tap where the second tap's finger stays down and then drags** (use a
  normal double-tap time/distance window). This is deliberately distinct from one-finger orbit
  drag so the two never get confused.
- On the double-tap, cast a ray from the camera through the tapped screen point and show a
  **visible helper ray + a target marker** in the scene at a default depth along the ray.
- Dragging the still-down finger slides the marker **nearer/farther along the ray**. Map finger
  travel to depth **logarithmically** (so the full huge scale range is reachable without the
  first millimeter of travel rocketing across the whole cave). The marker must be **visibly
  sliding through the cloud** so the user can see where they'll land (depth perception in sparse
  points is otherwise poor).
- On release, set the **pivot to the marker's 3D position** (keep current distance and angles),
  with a **short animated fly** to it (see §4).
- If the user double-taps but does **not** drag (just lifts), do nothing / cancel — a teleport
  always requires the deliberate depth-drag.

**Frame-All button (UI control, not a gesture) → refit the whole cloud.**
- Computes the cloud's bounding box (once on load / when the cloud changes), sets the pivot to
  its center and the distance to fit the entire cloud on screen, with a level view.
- This is the "I'm lost / show me everything" escape hatch — must always be available.
- Animated (short).

## 4. Feel rules (critical — this is what makes it good vs. clumsy)

- **Adaptive speed:** ALL translation and dolly speeds scale with the current `distance`
  (e.g. `speed ∝ distance`). Close to the pivot → fine/slow; far → fast/sweeping. Without this
  the same gesture is unusable at one end of the scale range. Pan speed scales the same way.
- **Adaptive near/far clip planes:** tie the near (and far) clip planes to `distance` so close
  points don't vanish when nose-to-wall and the whole cave doesn't clip when far out. No
  z-fighting across the range.
- **No damping on direct manipulation:** drag, pan, and pinch track the finger 1:1, instantly.
  Do **not** add inertia/easing to these — it must feel responsive, never laggy or heavy.
- **Animate only discrete jumps:** teleport and Frame-All get a short ease-out animation
  (~0.25–0.4 s). Nothing else animates.
- **Projection:** perspective for now (constant FOV). (An orthographic toggle may be added
  later; don't build it now.)

## 5. OUT OF SCOPE — do not build

- No FPV mode (architect for it per §2, but do not implement it).
- No roll control of any kind.
- No single-tap-to-fly / single-tap pick action.
- No orthographic toggle, no EDL/shading changes, no trajectory/path features, no gyro.
- No changes to the renderer/vertex pipeline beyond what's needed to drive the camera.

## 6. Acceptance criteria

- The old camera system is fully removed; the new orbit camera drives both the saved-PCD view
  and the live preview.
- One-finger drag orbits smoothly; yaw wraps infinitely; pitch is clamped near ±89° and is
  **flip-free and level** through the whole range (no inversion/mirroring at the limits).
- Pinch dollies through space (not FOV), can pass through the pivot, and spans a very large
  distance range (nose-to-wall up to whole-cave).
- Two-finger drag pans the pivot; view angle/distance unchanged.
- Double-tap-then-drag shows the helper ray + sliding marker, resolves depth logarithmically,
  and on release flies the pivot to the chosen 3D point; double-tap without drag does nothing.
- Frame-All refits the entire cloud.
- Movement/dolly/pan speeds and clip planes scale with distance; direct gestures feel glued to
  the finger (no laggy damping); only teleport and Frame-All animate.

---

Start by exploring the current rendering + camera/gesture code and giving me your findings + a
brief plan (including how you'll structure the camera state so FPV can be added later without a
rewrite) before coding.
