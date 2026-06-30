package com.underscanner.theunderscannerapp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.floor

/**
 * Client-side accumulating preview cloud for a live scan.
 *
 * The WebSocket stream sends per-frame world-frame points, not the whole map, so we
 * accumulate them here. To stay renderable on a phone over a multi-minute scan we
 * voxel-dedup (hash to a [voxelSize] grid) and cap the total point count.
 *
 * Memory model: a single direct [FloatBuffer] of xyz triplets is pre-allocated to
 * [capacity] points. The producer (WebSocket thread) appends via *absolute* puts into
 * region `[pointCount, newCount)` and then publishes [pointCount] (volatile). The GL
 * thread reads region `[0, pointCount)` — disjoint from in-flight writes — with no
 * per-frame allocation and no locking against the renderer.
 */
class PreviewCloud(
    val capacity: Int = 500_000,
    val voxelSize: Float = 0.1f
) {
    private val backing: ByteBuffer =
        ByteBuffer.allocateDirect(capacity * 3 * 4).order(ByteOrder.nativeOrder())

    /** xyz triplets; position is kept at 0, writes are absolute. Read by the GL thread. */
    val buffer: FloatBuffer = backing.asFloatBuffer()

    @Volatile
    var pointCount: Int = 0
        private set

    /** True once [capacity] is reached and new voxels are being dropped. */
    @Volatile
    var capped: Boolean = false
        private set

    // Occupied voxels, to deduplicate. Only touched by the single producer thread.
    private val voxels = HashSet<Long>(1 shl 20)
    private val invVoxel = 1f / voxelSize

    /**
     * Add a decoded frame: [floats] holds [n] points as (x, y, z, intensity), 4 floats each.
     * Intensity is currently unused (the shared renderer colors points uniformly).
     */
    @Synchronized
    fun addFrame(floats: FloatArray, n: Int) {
        var count = pointCount
        for (i in 0 until n) {
            if (count >= capacity) { capped = true; break }
            val base = i * 4
            val x = floats[base]
            val y = floats[base + 1]
            val z = floats[base + 2]
            if (voxels.add(voxelKey(x, y, z))) {
                val o = count * 3
                buffer.put(o, x)
                buffer.put(o + 1, y)
                buffer.put(o + 2, z)
                count++
            }
        }
        pointCount = count // publish after writes (happens-before for the GL thread)
    }

    @Synchronized
    fun clear() {
        voxels.clear()
        capped = false
        pointCount = 0
    }

    private fun voxelKey(x: Float, y: Float, z: Float): Long {
        // 21 bits per axis (signed), enough for ±100 km at 0.1 m resolution.
        val gx = floor(x * invVoxel).toInt().toLong() and 0x1FFFFF
        val gy = floor(y * invVoxel).toInt().toLong() and 0x1FFFFF
        val gz = floor(z * invVoxel).toInt().toLong() and 0x1FFFFF
        return gx or (gy shl 21) or (gz shl 42)
    }
}
