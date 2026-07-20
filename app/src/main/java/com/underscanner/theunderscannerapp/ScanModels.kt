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
    /** Per-scan system-health log (`health/{name}.jsonl`); drives the charts button. */
    val health: ScanArtifact = ScanArtifact(present = false),
    /** Free-text notes content (backend sends `notes.text`); used for client-side search. */
    val notesText: String = "",
    val downloadedLocally: Boolean = false,
    /** Whether this scan's health log is on the phone — charts then work offline. */
    val healthLocal: Boolean = false,
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
            health = ScanArtifact.from(obj.optJSONObject("health")),
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

// ---------------------------------------------------------------------------
// System health — live telemetry (`GET /system/health`) and the per-scan log
// (`GET /scans/{name}/health`, JSON Lines). See the app.py module docstring.
//
// EVERY metric is optional: the backend omits a key when the board doesn't expose
// that sensor or a read failed for that sample, so nothing here may be assumed
// present. Series are addressed by their backend key rather than by typed fields,
// which is what lets a new metric appear in the charts without an app change.
// ---------------------------------------------------------------------------

/** Hardware facts that don't change during a run; used for axis bounds and labels. */
data class HealthStatic(
    val cpuCores: Int? = null,
    val cpuMaxMhz: Int? = null,
    val memTotalMb: Int? = null,
    val powerMode: String? = null,
    val zones: List<String> = emptyList(),
    val rails: List<String> = emptyList()
) {
    companion object {
        fun from(obj: JSONObject?): HealthStatic {
            if (obj == null) return HealthStatic()
            return HealthStatic(
                cpuCores = obj.optIntOrNull("cpu_cores"),
                cpuMaxMhz = obj.optIntOrNull("cpu_max_mhz"),
                memTotalMb = obj.optIntOrNull("mem_total_mb"),
                powerMode = obj.optString("power_mode", "").ifBlank { null },
                zones = obj.optJSONArray("zones").toStringList(),
                rails = obj.optJSONArray("rails").toStringList()
            )
        }
    }
}

/**
 * One live telemetry snapshot. [values] holds every numeric metric keyed by its backend
 * name (`cpu`, `t_tj`, `w_in`, …); the named accessors are conveniences for the HUD.
 */
data class SystemHealth(
    val values: Map<String, Float>,
    val static: HealthStatic,
    val at: String? = null
) {
    operator fun get(key: String): Float? = values[key]

    val cpuPercent: Float? get() = values["cpu"]
    val cpuMhz: Float? get() = values["cpu_f"]
    val gpuPercent: Float? get() = values["gpu"]
    val memUsedMb: Float? get() = values["mem"]
    val tempTj: Float? get() = values["t_tj"]
    val tempCpu: Float? get() = values["t_cpu"]
    val tempGpu: Float? get() = values["t_gpu"]
    val tempSoc: Float? get() = values["t_soc"]
    val wattsIn: Float? get() = values["w_in"]
    val diskFreeGb: Float? get() = values["disk_free"]
    val cloudHz: Float? get() = values["cloud_hz"]
    val odomHz: Float? get() = values["odom_hz"]

    /** Memory used as a 0..1 fraction, when the total is known. */
    val memFraction: Float?
        get() {
            val used = memUsedMb ?: return null
            val total = static.memTotalMb?.takeIf { it > 0 } ?: return null
            return (used / total).coerceIn(0f, 1f)
        }

    /** CPU clock as a 0..1 fraction of the ceiling — how far throttling has pulled it down. */
    val cpuFreqFraction: Float?
        get() {
            val cur = cpuMhz ?: return null
            val max = static.cpuMaxMhz?.takeIf { it > 0 } ?: return null
            return (cur / max).coerceIn(0f, 1f)
        }

    companion object {
        /** Keys that are not metrics and must stay out of [values]. */
        private val NON_METRIC = setOf("static", "at", "type", "scan", "started_at",
                                       "hostname", "interval_s", "v")

        fun from(obj: JSONObject): SystemHealth = SystemHealth(
            values = obj.toMetricMap(NON_METRIC),
            static = HealthStatic.from(obj.optJSONObject("static")),
            at = obj.optString("at", "").ifBlank { null }
        )
    }
}

/** One logged sample: seconds since scan start, plus that sample's metrics. */
data class HealthSample(val t: Float, val values: Map<String, Float>)

/**
 * A parsed per-scan health log.
 *
 * Parsing is deliberately forgiving: any line that fails to parse is skipped rather than
 * failing the whole log. That is the point of the backend writing JSON Lines — a scan cut
 * short by a dead battery leaves a truncated final line, and everything before it must
 * still chart.
 */
data class HealthLog(
    val scan: String,
    val startedAt: String?,
    val intervalS: Float,
    val static: HealthStatic,
    val samples: List<HealthSample>
) {
    val isEmpty: Boolean get() = samples.isEmpty()

    /** Duration covered by the log, in seconds. */
    val durationS: Float get() = samples.lastOrNull()?.t ?: 0f

    /** Whether any sample carries [key] — used to hide series the board never reported. */
    fun has(key: String): Boolean = samples.any { it.values.containsKey(key) }

    /** Points of [key] as (t, value), skipping samples where it is absent. */
    fun series(key: String): List<Pair<Float, Float>> =
        samples.mapNotNull { s -> s.values[key]?.let { s.t to it } }

    /** Combined min/max across [keys], or null when none of them have data. */
    fun range(keys: List<String>): ClosedFloatingPointRange<Float>? {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (s in samples) for (k in keys) s.values[k]?.let {
            if (it < lo) lo = it
            if (it > hi) hi = it
        }
        return if (lo <= hi) lo..hi else null
    }

    /** Contiguous [t] spans where [key] is 0 (false) — drawn as dropout bands. */
    fun falseSpans(key: String): List<ClosedFloatingPointRange<Float>> {
        val spans = mutableListOf<ClosedFloatingPointRange<Float>>()
        var start: Float? = null
        for (s in samples) {
            val down = s.values[key]?.let { it < 0.5f } ?: false
            if (down && start == null) start = s.t
            if (!down && start != null) { spans += start!!..s.t; start = null }
        }
        start?.let { spans += it..durationS }
        return spans
    }

    companion object {
        /** Keys carried by a sample line that are not chartable metrics. */
        private val NON_METRIC = setOf("t", "type")

        /** Parse the JSONL body of `GET /scans/{name}/health`. */
        fun parse(text: String): HealthLog {
            var scan = ""
            var startedAt: String? = null
            var interval = 5f
            var static = HealthStatic()
            val samples = mutableListOf<HealthSample>()

            text.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                // Skip unparseable lines (notably a truncated final line) rather than
                // discarding the whole log.
                val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return@forEach
                if (obj.optString("type") == "header") {
                    scan = obj.optString("scan", scan)
                    startedAt = obj.optString("started_at", "").ifBlank { null }
                    interval = obj.optDouble("interval_s", interval.toDouble()).toFloat()
                    static = HealthStatic.from(obj.optJSONObject("static"))
                } else if (obj.has("t")) {
                    samples += HealthSample(
                        t = obj.optDouble("t", 0.0).toFloat(),
                        values = obj.toMetricMap(NON_METRIC)
                    )
                }
            }
            return HealthLog(scan, startedAt, interval, static, samples)
        }
    }
}

// --- JSON helpers: absent/null must yield null, never a silent 0 or NaN ---

internal fun JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key)) null else optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }

internal fun JSONArray?.toStringList(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it, "").ifBlank { null } }

/**
 * Flatten every numeric/boolean value into a `name -> Float` map, skipping [exclude] and
 * anything non-scalar. Booleans become 1f/0f so flags (`odom_ok`) live in the same map as
 * numbers and can be charted as bands.
 */
internal fun JSONObject.toMetricMap(exclude: Set<String>): Map<String, Float> {
    val out = LinkedHashMap<String, Float>()
    val it = keys()
    while (it.hasNext()) {
        val k = it.next()
        if (k in exclude || isNull(k)) continue
        when (val v = opt(k)) {
            is Boolean -> out[k] = if (v) 1f else 0f
            is Number -> out[k] = v.toFloat()
        }
    }
    return out
}
