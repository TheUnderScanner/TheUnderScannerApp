package com.underscanner.theunderscannerapp

import android.opengl.GLES20
import java.nio.ByteBuffer

/**
 * GPU-side copy of an interleaved 16-byte-per-point buffer ([PreviewCloud.STRIDE] layout),
 * split into fixed-size VBOs ("chunks") of [CHUNK_POINTS] records.
 *
 * Why chunks and not one big VBO: on Mali GPUs (the Pixel 8's G715 among them) a partial
 * `glBufferSubData` on a buffer the GPU may still be reading makes the driver copy-on-write
 * ("resource ghosting") the *whole* buffer. Appending a few thousand points to one 48 MB VBO
 * could therefore cost a 48 MB copy. Here full chunks are never touched again and only the
 * tail chunk receives new points, so the worst-case copy per update is one chunk (1 MB).
 *
 * Sync model: [sync] uploads only `[uploaded, count)` — the live cloud only grows at its
 * end. A change of `generation` (the source was coarsened or cleared, i.e. rewritten in
 * place) restarts the upload from 0, reusing the already-allocated chunks.
 *
 * GL thread only. The GL context (and every VBO with it) is destroyed whenever the
 * GLSurfaceView pauses, so [invalidate] must be called from `onSurfaceCreated`.
 */
class PointChunks {
    private var ids = IntArray(16)
    private var chunkCount = 0

    /** Records currently on the GPU, i.e. drawable. */
    var uploaded = 0
        private set

    private var generation = Long.MIN_VALUE

    /** Running total of bytes sent to the GPU (for the render KPIs). */
    var bytesUploaded = 0L
        private set

    val chunks: Int get() = chunkCount

    /** Forget all VBOs without deleting them: the context that owned them is gone. */
    fun invalidate() {
        chunkCount = 0
        uploaded = 0
        generation = Long.MIN_VALUE
    }

    /**
     * Bring the GPU copy up to `[0, count)` of [src]. The caller must guarantee `[0, count)`
     * is not being rewritten concurrently (the live cloud is synced under its own lock).
     * Leaves `GL_ARRAY_BUFFER` unbound.
     */
    fun sync(src: ByteBuffer, count: Int, gen: Long) {
        if (gen != generation || count < uploaded) {
            generation = gen
            uploaded = 0
        }
        if (uploaded >= count) return
        while (uploaded < count) {
            val chunk = uploaded / CHUNK_POINTS
            ensureChunk(chunk)
            val end = minOf(count, (chunk + 1) * CHUNK_POINTS)
            val bytes = (end - uploaded) * STRIDE
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[chunk])
            src.position(uploaded * STRIDE)
            GLES20.glBufferSubData(
                GLES20.GL_ARRAY_BUFFER, (uploaded - chunk * CHUNK_POINTS) * STRIDE, bytes, src
            )
            bytesUploaded += bytes
            uploaded = end
        }
        src.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Draw the uploaded records as `GL_POINTS`, one draw per chunk: position = 3 floats at
     * offset 0, attributes = 4 normalized bytes at offset 12. Unbinds afterwards — the
     * overlays still use client-side arrays, where a bound VBO would turn their pointers
     * into offsets.
     */
    fun draw(posHandle: Int, attrHandle: Int) {
        var first = 0
        var c = 0
        while (first < uploaded) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[c])
            GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, STRIDE, 0)
            if (attrHandle >= 0) {
                GLES20.glVertexAttribPointer(attrHandle, 4, GLES20.GL_UNSIGNED_BYTE, true, STRIDE, 12)
            }
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, minOf(CHUNK_POINTS, uploaded - first))
            first += CHUNK_POINTS
            c++
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun ensureChunk(index: Int) {
        while (chunkCount <= index) {
            if (chunkCount == ids.size) ids = ids.copyOf(ids.size * 2)
            val id = IntArray(1)
            GLES20.glGenBuffers(1, id, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, CHUNK_POINTS * STRIDE, null, GLES20.GL_DYNAMIC_DRAW)
            ids[chunkCount++] = id[0]
        }
    }

    companion object {
        /** Records per VBO: 65 536 × 16 B = 1 MB, the worst-case copy-on-write per update. */
        const val CHUNK_POINTS = 65_536
        private const val STRIDE = PreviewCloud.STRIDE
    }
}
