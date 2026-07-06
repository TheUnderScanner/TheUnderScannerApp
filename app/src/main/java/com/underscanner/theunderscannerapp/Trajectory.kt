package com.underscanner.theunderscannerapp

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The LiDAR path (sensor `/Odometry` positions) as a growing polyline.
 *
 * Live: the pose callback appends each new position (deduplicated by a minimum step) while a
 * scan streams. Saved: [loadFile] fills it once from a downloaded `UTR1` `.traj` file.
 *
 * Same lock-free contract as [PreviewCloud]: one pre-allocated direct buffer of xyz triplets,
 * the single producer appends with absolute puts then publishes [pointCount] (volatile), and
 * the GL thread reads `[0, pointCount)` as a `GL_LINE_STRIP` — no locking against the draw loop.
 */
class Trajectory(
    val capacity: Int = 200_000,
    private val minStep: Float = 0.05f
) {
    private val backing: ByteBuffer =
        ByteBuffer.allocateDirect(capacity * 3 * 4).order(ByteOrder.nativeOrder())

    /** xyz triplets; position kept at 0, writes absolute. Read by the GL thread. */
    val buffer: FloatBuffer = backing.asFloatBuffer()

    @Volatile
    var pointCount: Int = 0
        private set

    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private val minStepSq = minStep * minStep

    /** Append one path point unless it is within [minStep] of the previous one. */
    @Synchronized
    fun append(x: Float, y: Float, z: Float) {
        val count = pointCount
        if (count >= capacity) return
        if (count > 0) {
            val dx = x - lastX; val dy = y - lastY; val dz = z - lastZ
            if (dx * dx + dy * dy + dz * dz < minStepSq) return
        }
        val o = count * 3
        buffer.put(o, x)
        buffer.put(o + 1, y)
        buffer.put(o + 2, z)
        lastX = x; lastY = y; lastZ = z
        pointCount = count + 1 // publish after writes
    }

    @Synchronized
    fun clear() {
        pointCount = 0
    }

    /**
     * Load a `UTR1` binary path file: little-endian header (4-byte magic + uint32 count) then
     * count × (x,y,z) float32. Replaces any current contents. Returns the number of points
     * loaded (0 on a missing/invalid file).
     */
    @Synchronized
    fun loadFile(file: File): Int {
        pointCount = 0
        if (!file.exists() || file.length() < 8) return 0
        val bytes = file.readBytes()
        if (bytes.size < 8 ||
            bytes[0] != 'U'.code.toByte() || bytes[1] != 'T'.code.toByte() ||
            bytes[2] != 'R'.code.toByte() || bytes[3] != '1'.code.toByte()
        ) return 0
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(4)
        val declared = bb.int
        val available = (bytes.size - 8) / 12
        val n = minOf(declared, available, capacity)
        for (i in 0 until n) {
            val o = i * 3
            buffer.put(o, bb.float)
            buffer.put(o + 1, bb.float)
            buffer.put(o + 2, bb.float)
        }
        pointCount = n
        return n
    }
}
