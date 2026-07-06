package com.underscanner.theunderscannerapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class PreviewLinkState { Connecting, Connected, Reconnecting, Idle }

/**
 * Owns the live-preview WebSocket: connects, parses frames into [cloud], reports pose, and
 * auto-reconnects with backoff while a scan is still running. `/scan/status` (via [isRunning])
 * is the source of truth for "still scanning" — a dropped socket alone never stops anything.
 *
 * Frame decoding happens on OkHttp's reader thread (off the main thread), as required.
 */
class PreviewStreamManager(
    private val client: ScanApiClient,
    private val cloud: PreviewCloud,
    private val onPose: (Float, Float, Float) -> Unit,
    private val onState: (PreviewLinkState) -> Unit,
    private val onFrame: () -> Unit,
    private val isRunning: () -> Boolean
) {
    private var ws: WebSocket? = null
    private var reconnectJob: Job? = null
    @Volatile private var stopped = false
    private var attempt = 0

    fun start(scope: CoroutineScope) {
        stopped = false
        attempt = 0
        connect(scope)
    }

    fun stop() {
        stopped = true
        reconnectJob?.cancel()
        reconnectJob = null
        ws?.close(1000, "client closing")
        ws = null
        onState(PreviewLinkState.Idle)
    }

    private fun connect(scope: CoroutineScope) {
        if (stopped) return
        onState(if (attempt == 0) PreviewLinkState.Connecting else PreviewLinkState.Reconnecting)
        ws = client.openPreviewSocket(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0
                onState(PreviewLinkState.Connected)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                decodeFrame(bytes)
                onFrame()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                decodePose(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scheduleReconnect(scope)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!stopped) scheduleReconnect(scope)
            }
        })
    }

    private fun scheduleReconnect(scope: CoroutineScope) {
        if (stopped) return
        ws = null
        onState(PreviewLinkState.Reconnecting)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            attempt++
            // Exponential backoff: 1, 2, 4, 8, capped at 10 s.
            val backoff = (1000L shl (attempt - 1).coerceAtMost(4)).coerceAtMost(10_000L)
            delay(backoff)
            if (!stopped && isRunning()) connect(scope) else onState(PreviewLinkState.Idle)
        }
    }

    /**
     * `USC1` binary frame (little-endian): an 8-byte header — magic `"USC1"` then uint32
     * point_count — followed by point_count 16-byte records:
     * `x,y,z` float32 (offsets 0/4/8) then reflectivity, tag, line, reserved uint8 (12..15).
     *
     * The magic doubles as a version tag: a frame that doesn't start with it is ignored.
     */
    private fun decodeFrame(bytes: ByteString) {
        val arr = bytes.toByteArray()
        if (arr.size < HEADER_SIZE) return
        if (arr[0] != 'U'.code.toByte() || arr[1] != 'S'.code.toByte() ||
            arr[2] != 'C'.code.toByte() || arr[3] != '1'.code.toByte()
        ) {
            // Unknown/legacy frame — don't crash, just skip it.
            android.util.Log.w("PreviewStream", "Dropping frame with bad magic")
            return
        }
        val bb = ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(4)
        val n = bb.int
        if (n <= 0) return
        // Guard against truncated frames: RECORD_SIZE bytes per point follow the header.
        val available = (arr.size - HEADER_SIZE) / RECORD_SIZE
        val count = if (n <= available) n else available
        if (count <= 0) return
        // bb is positioned at the first record; PreviewCloud reads records directly (zero copy
        // into its interleaved backing buffer, dedup preserved).
        cloud.addFrame(bb, count)
    }

    companion object {
        /** `USC1` header: 4-byte magic + uint32 point_count. */
        private const val HEADER_SIZE = 8
        /** Per-point record: xyz (3× float32) + 4 attribute bytes. */
        private const val RECORD_SIZE = 16
    }

    private fun decodePose(text: String) {
        try {
            val o = JSONObject(text)
            if (o.optString("type") == "pose") {
                onPose(
                    o.optDouble("x", 0.0).toFloat(),
                    o.optDouble("y", 0.0).toFloat(),
                    o.optDouble("z", 0.0).toFloat()
                )
            }
        } catch (_: Exception) {
            // Ignore malformed text frames.
        }
    }
}
