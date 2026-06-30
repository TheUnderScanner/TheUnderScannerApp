package com.underscanner.theunderscannerapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the Jetson scan-library backend (Phase 1, plain request/response).
 * Implements the contract in §2 of the task spec. The base URL is configurable at
 * runtime because the Jetson's hotspot IP changes between sessions.
 */
class ScanApiClient(initialBaseUrl: String) {

    @Volatile
    private var baseUrl: String = SettingsRepository.normalize(initialBaseUrl)

    fun updateBaseUrl(newUrl: String) {
        baseUrl = SettingsRepository.normalize(newUrl)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Downloads can be tens of MB; give them room beyond the read timeout.
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    /** GET /status — readiness heartbeat used to drive the connection indicator. */
    suspend fun getStatus(): Result<StatusInfo> = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url("$baseUrl/status").get().build())
                .execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    StatusInfo.from(JSONObject(response.body?.string().orEmpty().ifEmpty { "{}" }))
                }
        }
    }

    /** GET /scans — full scan list, newest first. Returns the raw JSON too for caching. */
    suspend fun listScans(): Result<Pair<List<ScanInfo>, String>> = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url("$baseUrl/scans").get().build())
                .execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val raw = response.body?.string().orEmpty().ifEmpty { "{\"scans\":[]}" }
                    parseScans(raw) to raw
                }
        }
    }

    /** GET /scans/{name}/config — read-only YAML text. */
    suspend fun getConfig(name: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url("$baseUrl/scans/$name/config").get().build())
                .execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
        }
    }

    /** GET /scans/{name}/notes — arbitrary JSON object form ({} if none). */
    suspend fun getNotes(name: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url("$baseUrl/scans/$name/notes").get().build())
                .execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    JSONObject(response.body?.string().orEmpty().ifEmpty { "{}" })
                }
        }
    }

    /** PUT /scans/{name}/notes — persist the notes form as a JSON object. */
    suspend fun putNotes(name: String, notes: JSONObject): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = notes.toString().toRequestBody("application/json".toMediaType())
            client.newCall(Request.Builder().url("$baseUrl/scans/$name/notes").put(body).build())
                .execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    Unit
                }
        }
    }

    /**
     * GET /scans/{name}/pcd — stream the point cloud to [destination], reporting
     * progress in [0,1] (or -1 if the server sends no Content-Length). Honors
     * coroutine cancellation and cleans up a partial file on failure.
     */
    suspend fun downloadPcd(
        name: String,
        destination: File,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("$baseUrl/scans/$name/pcd").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty response body")
                val total = body.contentLength()
                var read = 0L
                try {
                    body.byteStream().use { input ->
                        FileOutputStream(destination).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var n: Int
                            while (input.read(buffer).also { n = it } != -1) {
                                currentCoroutineContext().ensureActive()
                                output.write(buffer, 0, n)
                                read += n
                                onProgress(if (total > 0) read.toFloat() / total else -1f)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    destination.delete()
                    throw e
                }
                destination
            }
        }
    }

    private fun parseScans(raw: String): List<ScanInfo> {
        val arr: JSONArray = JSONObject(raw).optJSONArray("scans") ?: JSONArray()
        return (0 until arr.length()).map { ScanInfo.from(arr.getJSONObject(it)) }
    }

    // -----------------------------------------------------------------------
    // Phase 2 — scan control
    // -----------------------------------------------------------------------

    /** POST /scan/start — begin a scan. Fails with [ScanAlreadyRunningException] on 409. */
    suspend fun startScan(location: String?): Result<StartResult> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().apply {
                if (!location.isNullOrBlank()) put("location", location.trim())
            }
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            client.newCall(Request.Builder().url("$baseUrl/scan/start").post(body).build())
                .execute().use { response ->
                    if (response.code == 409) throw ScanAlreadyRunningException()
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    StartResult.from(JSONObject(response.body?.string().orEmpty().ifEmpty { "{}" }))
                }
        }
    }

    /** POST /scan/stop — stop + save. Fails with [NoScanRunningException] on 409. */
    suspend fun stopScan(): Result<StopResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = "{}".toRequestBody("application/json".toMediaType())
            client.newCall(Request.Builder().url("$baseUrl/scan/stop").post(body).build())
                .execute().use { response ->
                    if (response.code == 409) throw NoScanRunningException()
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    StopResult.from(JSONObject(response.body?.string().orEmpty().ifEmpty { "{}" }))
                }
        }
    }

    /**
     * POST /system/shutdown — power the Jetson off. Fails with [ScanAlreadyRunningException]
     * on 409 (the backend refuses while a scan is running). A successful call halts the host,
     * so the connection is expected to drop right after.
     */
    suspend fun shutdown(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = "{}".toRequestBody("application/json".toMediaType())
            client.newCall(Request.Builder().url("$baseUrl/system/shutdown").post(body).build())
                .execute().use { response ->
                    if (response.code == 409) throw ScanAlreadyRunningException()
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    Unit
                }
        }
    }

    /** GET /scan/status — poll live scan state. */
    suspend fun getScanStatus(): Result<ScanStatus> = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url("$baseUrl/scan/status").get().build())
                .execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    ScanStatus.from(JSONObject(response.body?.string().orEmpty().ifEmpty { "{}" }))
                }
        }
    }

    /**
     * Open the live preview WebSocket (`ws(s)://<host>/ws/preview`). The caller owns the
     * returned socket and must close it. Uses a client with no read timeout so the long-lived
     * stream is not torn down.
     */
    fun openPreviewSocket(listener: WebSocketListener): WebSocket {
        val wsUrl = baseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/ws/preview"
        val request = Request.Builder().url(wsUrl).build()
        return wsClient.newWebSocket(request, listener)
    }

    /** Separate client for the streaming socket: no read timeout, OkHttp pings to keep alive. */
    private val wsClient = client.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
}
