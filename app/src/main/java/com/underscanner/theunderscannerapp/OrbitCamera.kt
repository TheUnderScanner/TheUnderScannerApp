package com.underscanner.theunderscannerapp

import android.opengl.Matrix
import kotlin.math.*

/**
 * Single-state orbit camera for the point-cloud viewer (saved PCD + live preview).
 *
 * The camera is described by ONE coherent state — a [pivot] it looks at, a [distance]
 * from that pivot, and orbit angles [yawDeg]/[pitchDeg]. The eye is derived as
 * `pivot + distance * dir(yaw, pitch)` and the camera always looks at the pivot with a
 * fixed [worldUp] (no roll, horizon always level). Pitch is clamped near ±90° so the
 * view never reaches the singularity and never flips/mirrors.
 *
 * FPV-ready: because the eye is `pivot ± distance·dir`, a future FPV mode is simply
 * "distance → ~0 and drive the pivot" — no separate camera, no rewrite of this state.
 *
 * All speeds (dolly/pan) and the near/far clip planes scale with the current distance so
 * the same gesture works from nose-to-wall up to whole-cave. Direct manipulation
 * (orbit/pan/dolly) is applied instantly (no smoothing); only [animateTo]/[frame] ease.
 */
class OrbitCamera {

    // World up. Flip to (0,0,1) if the SLAM/world frame turns out to be Z-up.
    private val worldUp = floatArrayOf(0f, 0f, 1f)

    // --- Coherent camera state ---
    val pivot = floatArrayOf(0f, 0f, 0f)
    var distance = 20f
    var yawDeg = 0f
    var pitchDeg = 20f
    var fovY = 60f

    /** Perspective (false) or orthographic (true) projection. Same FOV-matched zoom either way. */
    var orthographic = false

    // --- Scene bounds (drive frame-all + adaptive clip planes) ---
    private val sceneCenter = floatArrayOf(0f, 0f, 0f)
    var sceneRadius = 50f
        private set

    fun setScene(cx: Float, cy: Float, cz: Float, radius: Float) {
        sceneCenter[0] = cx; sceneCenter[1] = cy; sceneCenter[2] = cz
        sceneRadius = max(radius, 0.1f)
    }

    companion object {
        const val MIN_PITCH = -89f
        const val MAX_PITCH = 89f
        // Floor used for distance-adaptive speeds so motion never stalls near the pivot.
        const val MIN_SCALE = 0.05f
        // Closest the eye may dolly toward the pivot: a hard stop kept > 0 so the camera
        // never reaches / crosses the pivot (which would flip the view and the controls).
        const val MIN_DISTANCE = 0.1f
        const val DOLLY_SENS = 0.005f
        const val ORBIT_DEG_PER_PX = 0.3f
    }

    /** Magnitude of the eye-to-pivot distance, floored so adaptive speeds never hit 0. */
    fun camDist(): Float = max(abs(distance), MIN_SCALE)

    // --- Scratch (avoid per-frame allocation) ---
    private val tmpDir = FloatArray(3)
    private val tmpEye = FloatArray(3)

    // Z-up spherical: yaw rotates around +Z, pitch is elevation above the XY plane.
    // (Singularity is at pitch = ±90°, guarded by the ±89° clamp — not at the default view.)
    private fun dir(out: FloatArray) {
        val y = Math.toRadians(yawDeg.toDouble())
        val p = Math.toRadians(pitchDeg.toDouble())
        out[0] = (cos(p) * cos(y)).toFloat()
        out[1] = (cos(p) * sin(y)).toFloat()
        out[2] = sin(p).toFloat()
    }

    fun eye(out: FloatArray) {
        dir(tmpDir)
        out[0] = pivot[0] + distance * tmpDir[0]
        out[1] = pivot[1] + distance * tmpDir[1]
        out[2] = pivot[2] + distance * tmpDir[2]
    }

    fun viewMatrix(out: FloatArray) {
        eye(tmpEye)
        Matrix.setLookAtM(
            out, 0,
            tmpEye[0], tmpEye[1], tmpEye[2],
            pivot[0], pivot[1], pivot[2],
            worldUp[0], worldUp[1], worldUp[2]
        )
    }

    fun projectionMatrix(out: FloatArray, aspect: Float) {
        val cd = camDist()
        if (orthographic) {
            // Ortho half-height matched to the perspective FOV at the pivot plane, so toggling
            // projection keeps the cloud roughly the same on-screen size. Depth range brackets
            // the whole scene on both sides of the pivot (allows passing through).
            val halfH = cd * tan(Math.toRadians(fovY / 2.0)).toFloat()
            val halfW = halfH * aspect
            val ext = cd + 8f * sceneRadius
            Matrix.orthoM(out, 0, -halfW, halfW, -halfH, halfH, -ext, ext)
        } else {
            // Near/far tied to distance + scene size: close points stay visible nose-to-wall
            // and the whole cave never clips when pulled far back, with bounded z-precision.
            val near = max(cd * 0.02f, 0.02f)
            val far = (cd + 8f * sceneRadius) * 1.5f
            Matrix.perspectiveM(out, 0, fovY, aspect, near, max(far, near * 10f))
        }
    }

    // --- Direct manipulation (instant, no smoothing) ---

    /** Move the pivot directly (camera follows rigidly: eye = pivot + distance·dir). */
    fun setPivot(x: Float, y: Float, z: Float) {
        cancelAnim()
        pivot[0] = x; pivot[1] = y; pivot[2] = z
    }

    fun orbit(dxPx: Float, dyPx: Float) {
        cancelAnim()
        yawDeg -= dxPx * ORBIT_DEG_PER_PX
        if (yawDeg >= 360f || yawDeg <= -360f) yawDeg %= 360f
        pitchDeg = (pitchDeg + dyPx * ORBIT_DEG_PER_PX).coerceIn(MIN_PITCH, MAX_PITCH)
    }

    /**
     * Automatic "show" orbit: advance the yaw by [degPerSec] over [dtMs] milliseconds,
     * spinning the camera around the pivot without touching pitch/distance. Unlike direct
     * manipulation this does NOT cancel an in-flight frame-all animation, so the two can
     * compose (the cloud keeps spinning while it flies to frame).
     */
    fun autoOrbit(degPerSec: Float, dtMs: Float) {
        if (degPerSec == 0f) return
        yawDeg -= degPerSec * dtMs / 1000f
        if (yawDeg >= 360f || yawDeg <= -360f) yawDeg %= 360f
    }

    /** [spreadDeltaPx] > 0 (fingers spreading) zooms in (decreases distance). Clamped at [MIN_DISTANCE] so it stops just short of the pivot. */
    fun dolly(spreadDeltaPx: Float) {
        cancelAnim()
        distance = (distance - spreadDeltaPx * camDist() * DOLLY_SENS).coerceAtLeast(MIN_DISTANCE)
    }

    /**
     * Two-finger drag moves the pivot (and rig). Left/right slides along the camera's
     * screen-aligned right axis; up/down *walks along the cave floor* — fingers down = forward
     * (into the view), fingers up = backward — using the view direction projected onto the
     * ground plane (⊥ worldUp), so tilt never tips you into the floor. Speed scales with distance.
     */
    fun pan(dxPx: Float, dyPx: Float, viewportH: Int) {
        cancelAnim()
        eye(tmpEye)
        var fx = pivot[0] - tmpEye[0]; var fy = pivot[1] - tmpEye[1]; var fz = pivot[2] - tmpEye[2]
        val fl = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-6f)
        fx /= fl; fy /= fl; fz /= fl
        // right = forward × up
        var rx = fy * worldUp[2] - fz * worldUp[1]
        var ry = fz * worldUp[0] - fx * worldUp[2]
        var rz = fx * worldUp[1] - fy * worldUp[0]
        val rl = sqrt(rx * rx + ry * ry + rz * rz).coerceAtLeast(1e-6f)
        rx /= rl; ry /= rl; rz /= rl
        // horizontal forward = view direction with its worldUp component removed
        val fDotUp = fx * worldUp[0] + fy * worldUp[1] + fz * worldUp[2]
        var hx = fx - fDotUp * worldUp[0]
        var hy = fy - fDotUp * worldUp[1]
        var hz = fz - fDotUp * worldUp[2]
        val hl = sqrt(hx * hx + hy * hy + hz * hz).coerceAtLeast(1e-6f)
        hx /= hl; hy /= hl; hz /= hl

        val worldPerPx = (2f * camDist() * tan(Math.toRadians(fovY / 2.0)).toFloat()) /
            viewportH.coerceAtLeast(1)
        val mx = -dxPx * worldPerPx   // left/right, camera-relative
        val mf = dyPx * worldPerPx    // fingers down (dy>0) → forward along the ground
        pivot[0] += rx * mx + hx * mf
        pivot[1] += ry * mx + hy * mf
        pivot[2] += rz * mx + hz * mf
    }

    /** Slide the pivot along the world-up axis. Fingers down (dyPx > 0) raises it. */
    fun moveVertical(dyPx: Float, viewportH: Int) {
        cancelAnim()
        val worldPerPx = (2f * camDist() * tan(Math.toRadians(fovY / 2.0)).toFloat()) /
            viewportH.coerceAtLeast(1)
        val amt = dyPx * worldPerPx
        pivot[0] += worldUp[0] * amt
        pivot[1] += worldUp[1] * amt
        pivot[2] += worldUp[2] * amt
    }

    /** Frame the whole scene (pivot → center, distance → fit). */
    fun frame(aspect: Float, animate: Boolean) {
        val halfV = Math.toRadians(fovY / 2.0)
        val halfH = atan(tan(halfV) * aspect)
        val minHalf = min(halfV, halfH).coerceAtLeast(1e-3)
        val fit = (sceneRadius / sin(minHalf)).toFloat() * 1.1f
        if (animate) {
            animateTo(sceneCenter[0], sceneCenter[1], sceneCenter[2], fit, 320f)
        } else {
            cancelAnim()
            pivot[0] = sceneCenter[0]; pivot[1] = sceneCenter[1]; pivot[2] = sceneCenter[2]
            distance = fit
        }
    }

    // --- Discrete animated jumps (teleport / frame-all) ---

    private var animating = false
    private var animElapsed = 0f
    private var animDuration = 0f
    private val animStartPivot = FloatArray(3)
    private val animEndPivot = FloatArray(3)
    private var animStartDist = 0f
    private var animEndDist = 0f
    private var animDist = false

    /** Fly the pivot (and optionally distance) to a target with a short ease-out. */
    fun animateTo(px: Float, py: Float, pz: Float, targetDistance: Float?, durationMs: Float) {
        animStartPivot[0] = pivot[0]; animStartPivot[1] = pivot[1]; animStartPivot[2] = pivot[2]
        animEndPivot[0] = px; animEndPivot[1] = py; animEndPivot[2] = pz
        animDist = targetDistance != null
        animStartDist = distance
        animEndDist = targetDistance ?: distance
        animElapsed = 0f
        animDuration = durationMs
        animating = true
    }

    private fun cancelAnim() { animating = false }

    /** Advance any in-flight animation. [dtMs] is the frame delta in milliseconds. */
    fun update(dtMs: Float) {
        if (!animating) return
        animElapsed += dtMs
        val t = (animElapsed / animDuration).coerceIn(0f, 1f)
        val e = 1f - (1f - t) * (1f - t) * (1f - t) // ease-out cubic
        pivot[0] = animStartPivot[0] + (animEndPivot[0] - animStartPivot[0]) * e
        pivot[1] = animStartPivot[1] + (animEndPivot[1] - animStartPivot[1]) * e
        pivot[2] = animStartPivot[2] + (animEndPivot[2] - animStartPivot[2]) * e
        if (animDist) distance = animStartDist + (animEndDist - animStartDist) * e
        if (t >= 1f) animating = false
    }
}
