package com.underscanner.theunderscannerapp

import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------
// Data models matching the Jetson FastAPI contract (see CLAUDE / task spec §2).
// ---------------------------------------------------------------------------

/**
 * One downloadable/inspectable artifact of a scan (bag, pcd, config, notes).
 * Config and notes only carry [present]; bag and pcd also carry sizes.
 */
data class ScanArtifact(
    val present: Boolean,
    val sizeBytes: Long = 0L,
    val sizeHuman: String = ""
) {
    companion object {
        /** Parse a `{present, size_bytes, size_human}` object; tolerant of missing keys. */
        fun from(obj: JSONObject?): ScanArtifact {
            if (obj == null) return ScanArtifact(present = false)
            return ScanArtifact(
                present = obj.optBoolean("present", false),
                sizeBytes = obj.optLong("size_bytes", 0L),
                sizeHuman = obj.optString("size_human", "")
            )
        }
    }
}

/**
 * Metadata for a single scan known to the app.
 *
 * The Jetson is the source of truth for the *list*; the phone is the source of
 * truth for whether a scan's `.pcd` has been downloaded locally ([downloadedLocally]).
 * [localOnly] marks a scan that exists on this phone but is absent from the latest
 * server list (e.g. offline, or the server forgot it).
 */
data class ScanInfo(
    val name: String,
    val date: String,
    val location: String,
    val run: String,
    val bag: ScanArtifact,
    val pcd: ScanArtifact,
    val config: ScanArtifact,
    val notes: ScanArtifact,
    /** Free-text notes content (backend sends `notes.text`); used for client-side search. */
    val notesText: String = "",
    val downloadedLocally: Boolean = false,
    val localOnly: Boolean = false
) {
    companion object {
        fun from(obj: JSONObject): ScanInfo = ScanInfo(
            name = obj.getString("name"),
            date = obj.optString("date", ""),
            location = obj.optString("location", ""),
            run = obj.optString("run", ""),
            bag = ScanArtifact.from(obj.optJSONObject("bag")),
            pcd = ScanArtifact.from(obj.optJSONObject("pcd")),
            config = ScanArtifact.from(obj.optJSONObject("config")),
            notes = ScanArtifact.from(obj.optJSONObject("notes")),
            notesText = flattenJsonText(obj.optJSONObject("notes"))
        )
    }
}

/**
 * Collect every textual value inside a JSON object for client-side search — whether note
 * content lives under a single `text` key or in the structured form (site / issues / free / …,
 * plus any legacy fields still in the file). Recurses into nested objects/arrays and skips the
 * artifact bookkeeping keys. Used both for the notes embedded in the scan list and for the
 * standalone `GET /scans/{name}/notes` payload.
 */
fun flattenJsonText(notes: JSONObject?): String {
    if (notes == null) return ""
    val sb = StringBuilder()
    fun walk(obj: JSONObject) {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k == "present" || k == "size_bytes" || k == "size_human") continue
            when (val v = obj.opt(k)) {
                is JSONObject -> walk(v)
                is JSONArray -> for (i in 0 until v.length()) {
                    (v.opt(i) as? JSONObject)?.let { walk(it) }
                        ?: v.opt(i)?.takeIf { it != JSONObject.NULL }?.let { sb.append(it).append(' ') }
                }
                JSONObject.NULL, null -> {}
                else -> sb.append(v).append(' ')
            }
        }
    }
    walk(notes)
    return sb.toString().trim()
}

/** Readiness heartbeat from `GET /status`. */
data class StatusInfo(
    val status: String,
    val hostname: String,
    val version: String
) {
    companion object {
        fun from(obj: JSONObject): StatusInfo = StatusInfo(
            status = obj.optString("status", "ok"),
            hostname = obj.optString("hostname", ""),
            version = obj.optString("version", "")
        )
    }
}

/** Connection state for the Scan Library top bar. */
sealed class ConnectionState {
    object Connecting : ConnectionState()
    data class Connected(val status: StatusInfo) : ConnectionState()
    data class Offline(val reason: String) : ConnectionState()
}

// ---------------------------------------------------------------------------
// Phase 2 — scan control + live preview
// ---------------------------------------------------------------------------

/** Live scan state from `GET /scan/status`. */
data class ScanStatus(
    val running: Boolean,
    val scan: String?,
    val startedAt: String?,
    val elapsedS: Long,
    val odomOk: Boolean,
    val cloudOk: Boolean
) {
    companion object {
        fun from(obj: JSONObject): ScanStatus = ScanStatus(
            running = obj.optBoolean("running", false),
            scan = obj.optString("scan", "").ifBlank { null },
            startedAt = obj.optString("started_at", "").ifBlank { null },
            elapsedS = obj.optLong("elapsed_s", 0L),
            odomOk = obj.optBoolean("odom_ok", false),
            cloudOk = obj.optBoolean("cloud_ok", false)
        )
    }
}

/** Result of `POST /scan/start`. */
data class StartResult(
    val scan: String,
    val running: Boolean,
    val startedAt: String?
) {
    companion object {
        fun from(obj: JSONObject): StartResult = StartResult(
            scan = obj.optString("scan", ""),
            running = obj.optBoolean("running", true),
            startedAt = obj.optString("started_at", "").ifBlank { null }
        )
    }
}

/** Result of `POST /scan/stop`, used for the post-scan summary. */
data class StopResult(
    val scan: String,
    val pcdSize: String,
    val bagSize: String
) {
    companion object {
        fun from(obj: JSONObject): StopResult = StopResult(
            scan = obj.optString("scan", ""),
            pcdSize = humanFromAny(obj.opt("pcd")),
            bagSize = humanFromAny(obj.opt("bag"))
        )

        /** The backend may return pcd/bag as a {size_human,...} object or a plain value. */
        private fun humanFromAny(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "—"
            is JSONObject -> value.optString("size_human", "").ifBlank {
                value.optLong("size_bytes", -1L).let { if (it >= 0) humanSize(it) else "—" }
            }
            is Number -> humanSize(value.toLong())
            else -> value.toString()
        }
    }
}

/** Typed errors so the UI can react to the backend's 409 conflicts. */
class ScanAlreadyRunningException : Exception("Un scan est déjà en cours")
class NoScanRunningException : Exception("Aucun scan en cours")
