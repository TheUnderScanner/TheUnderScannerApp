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
    /** Which SLAM algo/profile/overrides produced the scan; null for pre-profile scans. */
    val slam: SlamMeta? = null,
    /** Whether the scan is on the USB stick mounted on the Jetson; null when none is mounted. */
    val usb: UsbPresence? = null,
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
            notesText = flattenJsonText(obj.optJSONObject("notes")),
            slam = SlamMeta.from(obj.optJSONObject("slam")),
            usb = obj.optJSONObject("usb")?.let {
                UsbPresence(it.optBoolean("present", false), it.optString("stick", ""))
            }
        )
    }
}

data class UsbPresence(val present: Boolean, val stick: String)

// ---------------------------------------------------------------------------
// USB stick on the Jetson (`/usb/...`, `/scans/{name}/usb`)
// ---------------------------------------------------------------------------

data class UsbDevice(
    val device: String,         // /dev/sda1
    val name: String,           // label, else vendor + model
    val model: String?,
    val vendor: String?,
    val size: Long,
    val sizeHuman: String,
    val fstype: String,
    val fsver: String?,
    val mounted: Boolean,
    val free: Long?,
    val freeHuman: String?
) {
    val isFat32: Boolean get() = fstype == "vfat"

    /** "FAT32", "EXFAT", "NTFS"… */
    val fsLabel: String get() = fsver ?: fstype.uppercase()

    /** "SanDisk Cruzer Force · 29.1 GiB · FAT32" */
    val details: String
        get() = listOfNotNull(
            listOfNotNull(vendor, model).joinToString(" ").ifBlank { null }, sizeHuman, fsLabel
        ).joinToString(" · ")

    companion object {
        fun from(o: JSONObject) = UsbDevice(
            device = o.optString("device"),
            name = o.optString("name", o.optString("device")),
            model = o.optString("model", "").ifBlank { null },
            vendor = o.optString("vendor", "").ifBlank { null },
            size = o.optLong("size", 0L),
            sizeHuman = o.optString("size_human", ""),
            fstype = o.optString("fstype", ""),
            fsver = o.optString("fsver", "").ifBlank { null },
            mounted = o.optBoolean("mounted", false),
            free = if (o.isNull("free")) null else o.optLong("free"),
            freeHuman = o.optString("free_human", "").ifBlank { null }
        )
    }
}

/** The (single) scan → USB copy job, as `GET /usb/transfer` reports it. */
data class UsbTransfer(
    val state: String,          // idle | copying | syncing | done | error | cancelled
    val scan: String?,
    val label: String?,
    val bytesDone: Long,
    val bytesTotal: Long,
    val percent: Int,
    val rateBps: Double?,
    val etaS: Long?,
    val error: String?
) {
    val active: Boolean get() = state == "copying" || state == "syncing"

    companion object {
        val IDLE = UsbTransfer("idle", null, null, 0, 0, 0, null, null, null)

        fun from(o: JSONObject?): UsbTransfer {
            if (o == null) return IDLE
            return UsbTransfer(
                state = o.optString("state", "idle"),
                scan = o.optString("scan", "").ifBlank { null },
                label = o.optString("label", "").ifBlank { null },
                bytesDone = o.optLong("bytes_done", 0L),
                bytesTotal = o.optLong("bytes_total", 0L),
                percent = o.optInt("percent", 0),
                rateBps = if (o.isNull("rate_bps")) null else o.optDouble("rate_bps").takeIf { !it.isNaN() },
                etaS = if (o.isNull("eta_s")) null else o.optLong("eta_s"),
                error = o.optString("error", "").ifBlank { null }
            )
        }
    }
}

/** Dry run of copying a scan to the stick (`GET /scans/{name}/usb`). */
data class UsbPlan(
    val ok: Boolean,
    val problems: List<String>,
    val already: Boolean,
    val totalHuman: String,
    val neededHuman: String,
    val bytesNeeded: Long,
    val device: UsbDevice?
) {
    companion object {
        fun from(o: JSONObject) = UsbPlan(
            ok = o.optBoolean("ok", false),
            problems = o.optJSONArray("problems").toStringList(),
            already = o.optBoolean("already", false),
            totalHuman = o.optString("total_human", ""),
            neededHuman = o.optString("needed_human", ""),
            bytesNeeded = o.optLong("bytes_needed", 0L),
            device = o.optJSONObject("device")?.let(UsbDevice::from)
        )
    }
}

/**
 * Provenance of a scan's config (`configs/<scan>.json` on the Jetson): the algo and profile it
 * ran with, the per-scan overrides, and the sha256 of the exact config file that ran.
 * [overrides] is sorted by key (JSON object order isn't guaranteed across org.json
 * implementations); values are shown as the backend sent them.
 */
data class SlamMeta(
    val algo: String,
    val algoLabel: String?,
    val profile: String,
    val overrides: List<Pair<String, String>>,
    val sha256: String?
) {
    /** "FAST-LIO2 · voxel-0.3" */
    val title: String get() = "${algoLabel ?: algo} · $profile"

    /** "mapping.det_range=60.0, preprocess.blind=0.3", or "" when none. */
    val overridesText: String get() = overrides.joinToString(", ") { "${it.first}=${it.second}" }

    companion object {
        fun from(obj: JSONObject?): SlamMeta? {
            if (obj == null) return null
            val algo = obj.optString("algo", "").ifBlank { return null }
            val ovr = obj.optJSONObject("overrides")
            val pairs = mutableListOf<Pair<String, String>>()
            ovr?.keys()?.forEach { k -> pairs += k to ovr.opt(k).toString() }
            return SlamMeta(
                algo = algo,
                algoLabel = obj.optString("algo_label", "").ifBlank { null },
                profile = obj.optString("profile", ""),
                overrides = pairs.sortedBy { it.first },
                sha256 = obj.optString("sha256", "").ifBlank { null }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// SLAM algos + config profiles (`/slam/...`). Profiles live on the Jetson; the phone
// only reads/edits them through the API, which validates every write.
// ---------------------------------------------------------------------------

/** One validation finding. [line] is 1-based in the text [where] refers to. */
data class SlamIssue(
    val level: String,          // "error" | "warning"
    val msg: String,
    val line: Int?,
    val where: String           // "yaml" (profile text) | "overrides"
) {
    val isError: Boolean get() = level == "error"

    companion object {
        fun from(obj: JSONObject) = SlamIssue(
            level = obj.optString("level", "error"),
            msg = obj.optString("msg", ""),
            line = obj.optIntOrNull("line"),
            where = obj.optString("where", "yaml")
        )

        fun list(arr: JSONArray?): List<SlamIssue> =
            if (arr == null) emptyList()
            else (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::from) }
    }
}

/** A 422 from a `/slam` write or `/scan/start`: the config was refused, see [issues]. */
class SlamConfigException(val issues: List<SlamIssue>, message: String) : Exception(message)

data class SlamAlgoInfo(
    val id: String,
    val label: String,
    val description: String,
    val defaultProfile: String,
    /** Keys the backend imposes whatever the profile says (shown read-only in the editor). */
    val forced: List<String>
) {
    companion object {
        fun from(obj: JSONObject) = SlamAlgoInfo(
            id = obj.optString("id"),
            label = obj.optString("label", obj.optString("id")),
            description = obj.optString("description", ""),
            defaultProfile = obj.optString("default_profile", "usine"),
            forced = obj.optJSONObject("forced")?.keys()?.asSequence()?.sorted()?.toList().orEmpty()
        )
    }
}

data class SlamAlgos(val default: String, val algos: List<SlamAlgoInfo>) {
    fun byId(id: String?): SlamAlgoInfo? = algos.firstOrNull { it.id == id }

    companion object {
        fun from(obj: JSONObject): SlamAlgos {
            val arr = obj.optJSONArray("algos")
            val algos = if (arr == null) emptyList()
                else (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(SlamAlgoInfo::from) }
            return SlamAlgos(obj.optString("default", algos.firstOrNull()?.id ?: ""), algos)
        }
    }
}

data class SlamProfileInfo(
    val name: String,
    val readonly: Boolean,
    val isDefault: Boolean,
    val updated: String?
) {
    companion object {
        /** Name of the factory profile: read-only, can only be duplicated. */
        const val FACTORY = "usine"

        fun from(obj: JSONObject) = SlamProfileInfo(
            name = obj.optString("name"),
            readonly = obj.optBoolean("readonly", false),
            isDefault = obj.optBoolean("is_default", false),
            updated = obj.optString("updated", "").ifBlank { null }
        )
    }
}

/** Result of `POST /slam/{algo}/validate` (a dry run). */
data class SlamValidation(
    val ok: Boolean,
    val issues: List<SlamIssue>,
    /** The profile text with type conversions written in (what Save would store). */
    val normalized: String?,
    /** The config a scan would run with (profile + overrides + imposed keys). */
    val effective: String?
) {
    val errors: List<SlamIssue> get() = issues.filter { it.isError }
    val warnings: List<SlamIssue> get() = issues.filterNot { it.isError }

    companion object {
        fun from(obj: JSONObject) = SlamValidation(
            ok = obj.optBoolean("ok", false),
            issues = SlamIssue.list(obj.optJSONArray("issues")),
            normalized = obj.optString("normalized", "").ifEmpty { null },
            effective = obj.optString("effective", "").ifEmpty { null }
        )
    }
}

/** Result of saving a profile: the stored (normalized) text + warnings. */
data class SlamSaveResult(val text: String, val issues: List<SlamIssue>)

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
    val version: String,
    /**
     * Phone-vs-Jetson clock correction the backend applies, in seconds. `0` = not synced since
     * the backend started (drives [ClockSync]); `null` = backend too old to report it.
     */
    val clockOffsetS: Float? = null
) {
    companion object {
        fun from(obj: JSONObject): StatusInfo = StatusInfo(
            status = obj.optString("status", "ok"),
            hostname = obj.optString("hostname", ""),
            version = obj.optString("version", ""),
            clockOffsetS = if (obj.has("clock_offset_s") && !obj.isNull("clock_offset_s"))
                obj.optDouble("clock_offset_s").toFloat() else null
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
    val cloudOk: Boolean,
    /** Algo/profile/overrides of the running scan (null when idle or on an older backend). */
    val slam: SlamMeta? = null
) {
    companion object {
        fun from(obj: JSONObject): ScanStatus = ScanStatus(
            running = obj.optBoolean("running", false),
            scan = obj.optString("scan", "").ifBlank { null },
            startedAt = obj.optString("started_at", "").ifBlank { null },
            elapsedS = obj.optLong("elapsed_s", 0L),
            odomOk = obj.optBoolean("odom_ok", false),
            cloudOk = obj.optBoolean("cloud_ok", false),
            slam = SlamMeta.from(obj.optJSONObject("slam"))
        )
    }
}

/** Result of `POST /scan/start`. */
data class StartResult(
    val scan: String,
    val running: Boolean,
    val startedAt: String?,
    val slam: SlamMeta? = null
) {
    companion object {
        fun from(obj: JSONObject): StartResult = StartResult(
            scan = obj.optString("scan", ""),
            running = obj.optBoolean("running", true),
            startedAt = obj.optString("started_at", "").ifBlank { null },
            slam = SlamMeta.from(obj.optJSONObject("slam"))
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

/** A non-2xx answer; [message] is the backend's `detail` (French, shown to the user). */
class HttpStatusException(val code: Int, message: String) : java.io.IOException(message)

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
