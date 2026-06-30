package com.underscanner.theunderscannerapp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Builds a 3-axis "ruler" centered on the pivot: three world-axis-aligned lines (X, Y, Z)
 * passing through the pivot, with tick marks at every graduation. The lines move with the
 * pivot, but graduations sit at fixed world coordinates (multiples of the step), so a moving
 * pivot visibly slides through the ticks — a depth/scale cue for sparse point clouds.
 *
 * This is intentionally decoupled from gestures and from the camera's motion code: callers
 * just hand it the current pivot + camera distance and draw the returned vertices. Whatever
 * moves the pivot (pan, Z-slide, frame-all, a future gesture) gets the ruler for free.
 *
 * Graduation step adapts to zoom (1 m → 10 m → 100 m → 1 km …) so the tick count stays bounded
 * and the lines never stretch to infinity.
 */
object AxisRuler {

    private const val MAX_VERTS = 4096
    private const val TARGET_TICKS = 30     // upper bound on ticks per axis (drives the step)
    private const val EXTENT_FACTOR = 1.5f  // half-length of each line, relative to camera distance

    fun newBuffer(): FloatBuffer =
        ByteBuffer.allocateDirect(MAX_VERTS * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** Graduation spacing in meters for the given camera distance: 1, 10, 100, 1000, … */
    fun stepFor(camDist: Float): Float {
        val ext = camDist * EXTENT_FACTOR
        var step = 1f
        while (2f * ext / step > TARGET_TICKS) step *= 10f
        return step
    }

    /**
     * Fill [buffer] with GL_LINES vertices (xyz triplets) for the ruler and return the vertex
     * count. The buffer's position is left at 0, ready to draw.
     */
    fun build(buffer: FloatBuffer, pivot: FloatArray, camDist: Float): Int {
        buffer.position(0)
        var n = 0
        val ext = camDist * EXTENT_FACTOR
        val step = stepFor(camDist)
        val tickLen = step * 0.12f
        val v = FloatArray(3)

        fun emit() {
            if (n + 1 > MAX_VERTS) return
            buffer.put(v[0]); buffer.put(v[1]); buffer.put(v[2]); n++
        }

        for (axis in 0..2) {
            val center = pivot[axis]
            val lo = center - ext
            val hi = center + ext

            // Main axis line through the pivot.
            v[0] = pivot[0]; v[1] = pivot[1]; v[2] = pivot[2]
            v[axis] = lo; emit()
            v[axis] = hi; emit()

            // Tick marks at fixed world graduations, drawn perpendicular to the axis.
            val perp = if (axis == 2) 0 else 2 // X/Y ticks rise along Z (up); Z ticks along X.
            var k = ceil(lo / step).toInt()
            val kMax = floor(hi / step).toInt()
            while (k <= kMax && n + 2 <= MAX_VERTS) {
                v[0] = pivot[0]; v[1] = pivot[1]; v[2] = pivot[2]
                v[axis] = k * step
                val base = v[perp]
                v[perp] = base - tickLen; emit()
                v[perp] = base + tickLen; emit()
                k++
            }
        }

        buffer.position(0)
        return n
    }
}
