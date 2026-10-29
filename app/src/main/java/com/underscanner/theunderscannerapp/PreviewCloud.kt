package com.underscanner.theunderscannerapp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * Client-side accumulating preview cloud for a live scan.
 *
 * The WebSocket stream sends per-frame world-frame points, not the whole map, so we
 * accumulate them here. To stay renderable on a phone over a multi-minute scan we
 * voxel-dedup (hash to a [voxelSize] grid) and cap the total point count. When the cap is
 * reached the cloud is *coarsened* in place (voxel size doubled, buffer re-deduped and
 * compacted) so new areas keep appearing instead of the preview silently freezing.
 *
 * Memory model: a single direct [ByteBuffer] of 16-byte interleaved records is pre-allocated
 * to [capacity] points. Each record is `xyz` (3× float32) followed by 4 bytes
 * (reflectivity, tag, line, reserved) — the exact `USC1` wire layout, which [PointChunks]
 * uploads verbatim into its VBOs (position at offset 0, attributes at 12, stride 16). The
 * producer (WebSocket thread) appends via *absolute* puts into region `[pointCount,
 * newCount)` and then publishes [pointCount]. Every mutation holds this object's monitor,
 * and so does the GL thread while it uploads, so an upload never sees a half-compacted
 * buffer; [generation] tells it when `[0, pointCount)` was rewritten and must be re-sent.
 */
class PreviewCloud(
    val capacity: Int = 3_000_000,
    private val initialVoxelSize: Float = 0.1f
) {
    private val backing: ByteBuffer =
        ByteBuffer.allocateDirect(capacity * STRIDE).order(ByteOrder.nativeOrder())

    /** Interleaved 16-byte records; writes are absolute. Uploaded to the GPU by [PointChunks]. */
    val byteBuffer: ByteBuffer = backing

    @Volatile
    var pointCount: Int = 0
        private set

    /** Bumped whenever existing records are rewritten in place ([coarsen], [clear]). */
    @Volatile
    var generation: Long = 0
        private set

    /** True only once even [MAX_VOXEL] can't make room and new voxels are being dropped. */
    @Volatile
    var capped: Boolean = false
        private set

    /** Current dedup grid (m); doubles each time the cloud is coarsened. */
    @Volatile
    var voxelSize: Float = initialVoxelSize
        private set

    // Running world bounds over kept points (published after pointCount for the GL thread).
    @Volatile var minX = 0f
        private set
    @Volatile var minY = 0f
        private set
    @Volatile var minZ = 0f
        private set
    @Volatile var maxX = 0f
        private set
    @Volatile var maxY = 0f
        private set
    @Volatile var maxZ = 0f
        private set

    // Occupied voxels, to deduplicate. Only touched by the single producer thread.
    private val voxels = LongHashSet(1 shl 20)
    private var invVoxel = 1f / voxelSize

    /**
     * Add a decoded frame: [src] is positioned at the first of [n] 16-byte records
     * (little-endian: x,y,z float32 then reflectivity, tag, line, reserved bytes). Reading
     * advances [src]'s position. Kept points carry all four attribute bytes through the dedup.
     */
    @Synchronized
    fun addFrame(src: ByteBuffer, n: Int) {
        var count = pointCount
        var mnx = if (count > 0) minX else Float.MAX_VALUE
        var mny = if (count > 0) minY else Float.MAX_VALUE
        var mnz = if (count > 0) minZ else Float.MAX_VALUE
        var mxx = if (count > 0) maxX else -Float.MAX_VALUE
        var mxy = if (count > 0) maxY else -Float.MAX_VALUE
        var mxz = if (count > 0) maxZ else -Float.MAX_VALUE

        for (i in 0 until n) {
            if (count >= capacity) {
                count = coarsen(count)
                if (count >= capacity) { capped = true; break }
            }
            val x = src.float
            val y = src.float
            val z = src.float
            val refl = src.get()
            val tag = src.get()
            val line = src.get()
            val reserved = src.get()
            if (voxels.add(voxelKey(x, y, z))) {
                val o = count * STRIDE
                backing.putFloat(o, x)
                backing.putFloat(o + 4, y)
                backing.putFloat(o + 8, z)
                backing.put(o + 12, refl)
                backing.put(o + 13, tag)
                backing.put(o + 14, line)
                backing.put(o + 15, reserved)
                if (x < mnx) mnx = x; if (x > mxx) mxx = x
                if (y < mny) mny = y; if (y > mxy) mxy = y
                if (z < mnz) mnz = z; if (z > mxz) mxz = z
                count++
            }
        }

        if (count > 0) {
            minX = mnx; minY = mny; minZ = mnz
            maxX = mxx; maxY = mxy; maxZ = mxz
        }
        pointCount = count // publish after writes (happens-before for the GL thread)
    }

    @Synchronized
    fun clear() {
        voxels.clear()
        capped = false
        voxelSize = initialVoxelSize
        invVoxel = 1f / voxelSize
        minX = 0f; minY = 0f; minZ = 0f
        maxX = 0f; maxY = 0f; maxZ = 0f
        pointCount = 0
        generation++
    }

    /**
     * Double the voxel size until the first [count] records fit in [COARSEN_TARGET] of
     * [capacity], keeping one record per coarse voxel and compacting them to the front.
     * Runs under the monitor (from [addFrame]), so no upload is in flight; bumping
     * [generation] makes the GL thread re-send the compacted buffer. Returns the new count
     * (also published to [pointCount]).
     */
    private fun coarsen(count: Int): Int {
        var n = count
        while (n > capacity * COARSEN_TARGET && voxelSize < MAX_VOXEL) {
            generation++
            voxelSize *= 2f
            invVoxel = 1f / voxelSize
            voxels.clear()
            var kept = 0
            for (i in 0 until n) {
                val o = i * STRIDE
                val key = voxelKey(backing.getFloat(o), backing.getFloat(o + 4), backing.getFloat(o + 8))
                if (voxels.add(key)) {
                    if (kept != i) {
                        // Raw 16-byte record copy (same buffer, same byte order).
                        val d = kept * STRIDE
                        backing.putLong(d, backing.getLong(o))
                        backing.putLong(d + 8, backing.getLong(o + 8))
                    }
                    kept++
                }
            }
            n = kept
        }
        pointCount = n
        return n
    }

    private fun voxelKey(x: Float, y: Float, z: Float): Long {
        // 21 bits per axis (signed), enough for ±100 km at 0.1 m resolution.
        val gx = floor(x * invVoxel).toInt().toLong() and 0x1FFFFF
        val gy = floor(y * invVoxel).toInt().toLong() and 0x1FFFFF
        val gz = floor(z * invVoxel).toInt().toLong() and 0x1FFFFF
        return gx or (gy shl 21) or (gz shl 42)
    }

    companion object {
        /** Bytes per interleaved point record: xyz (3× float32) + 4 attribute bytes. */
        const val STRIDE = 16

        /** After coarsening, the cloud should occupy at most this fraction of [capacity]. */
        private const val COARSEN_TARGET = 0.6f

        /** Coarsest preview grid (m); past this, new voxels are dropped ([capped]). */
        private const val MAX_VOXEL = 1.6f
    }
}
