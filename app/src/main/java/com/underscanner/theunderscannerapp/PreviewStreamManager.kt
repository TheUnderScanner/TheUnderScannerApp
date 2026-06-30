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

    /** Binary frame: little-endian uint32 N, then N×(x,y,z,intensity) float32. */
    private fun decodeFrame(bytes: ByteString) {
        val arr = bytes.toByteArray()
        if (arr.size < 4) return
        val bb = ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN)
        val n = bb.int
        if (n <= 0) return
        // Guard against truncated frames: 4 floats (16 bytes) per point follow the count.
        val available = (arr.size - 4) / 16
        val count = if (n <= available) n else available
        if (count <= 0) return
        val floats = FloatArray(count * 4)
        for (i in floats.indices) floats[i] = bb.float
        cloud.addFrame(floats, count)
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
