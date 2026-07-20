package com.underscanner.theunderscannerapp

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * State holder for the single Scan Library screen.
 *
 * Responsibilities:
 *  - poll `/status` to drive the connection indicator,
 *  - hold one unified scan list (server list, falling back to an offline cache,
 *    plus any scan whose `.pcd` exists locally but is absent from the server list),
 *  - stream `.pcd` downloads with per-scan progress,
 *  - proxy config and notes requests to the client.
 *
 * The Jetson is the source of truth for the *list*; the phone is the source of
 * truth for *what it has downloaded* (a file existing in [LocalScanStorage]).
 */
class ScanLibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsRepository(app)
    val client = ScanApiClient(settings.baseUrl)

    // --- Connection ---
    private val _connection = mutableStateOf<ConnectionState>(ConnectionState.Connecting)
    val connection: State<ConnectionState> = _connection

    val baseUrl: String get() = settings.baseUrl

    // --- Scan list ---
    // [_allScans] is the merged source list (server/cache + local-only); [_scans] is what the
    // UI shows after the current search filter + sort are applied.
    private val _allScans = mutableStateOf<List<ScanInfo>>(emptyList())
    private val _scans = mutableStateOf<List<ScanInfo>>(emptyList())
    val scans: State<List<ScanInfo>> = _scans

    // --- Search + sort (client-side, work offline on cached data) ---
    private val _query = mutableStateOf("")
    val query: State<String> = _query

    private val _sort = mutableStateOf(
        runCatching { ScanSort.valueOf(settings.scanSort) }.getOrDefault(ScanSort.DATE_DESC)
    )
    val sort: State<ScanSort> = _sort

    private val _isRefreshing = mutableStateOf(false)
    val isRefreshing: State<Boolean> = _isRefreshing

    private val _listError = mutableStateOf<String?>(null)
    val listError: State<String?> = _listError

    /** Last scan list received from the server (null until a successful fetch / cache load). */
    private var serverScans: List<ScanInfo>? = null
    private var loadedFromServerOnce = false

    // Searchable notes text per scan, fetched on demand from `/scans/{name}/notes` because the
    // list endpoint only reports note *presence*, not content. Cached for the session so search
    // over notes works offline once warmed.
    private val notesTextCache = mutableMapOf<String, String>()

    // --- Downloads: scanName -> progress in [0,1], or -1 for indeterminate ---
    val downloadProgress: SnapshotStateMap<String, Float> = mutableStateMapOf()
    private val downloadJobs = mutableMapOf<String, Job>()

    private var statusJob: Job? = null

    init {
        loadCache()
        rebuildList()
        refresh()
    }

    // -----------------------------------------------------------------------
    // Connection polling
    // -----------------------------------------------------------------------

    fun startStatusPolling() {
        if (statusJob?.isActive == true) return
        statusJob = viewModelScope.launch {
            while (isActive) {
                pollStatusOnce()
                delay(3000)
            }
        }
    }

    fun stopStatusPolling() {
        statusJob?.cancel()
        statusJob = null
    }

    private suspend fun pollStatusOnce() {
        client.getStatus().fold(
            onSuccess = { status ->
                // Only publish a new state on an actual category change. The status payload
                // (its `time`) differs every poll, and reassigning it every 3s would recompose
                // the whole screen — including the scan list — and jank scrolling. The fields
                // the UI shows (hostname/version) are constant for a session.
                if (_connection.value !is ConnectionState.Connected) {
                    _connection.value = ConnectionState.Connected(status)
                }
                // First time we reach the server, pull the scan list automatically.
                if (!loadedFromServerOnce) refreshSuspending()
            },
            onFailure = { e ->
                if (_connection.value !is ConnectionState.Offline) {
                    _connection.value = ConnectionState.Offline(e.message ?: "Injoignable")
                }
            }
        )
    }

    // -----------------------------------------------------------------------
    // Scan list
    // -----------------------------------------------------------------------

    fun refresh() {
        viewModelScope.launch { refreshSuspending() }
    }

    private suspend fun refreshSuspending() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        _listError.value = null
        client.listScans().fold(
            onSuccess = { (list, raw) ->
                serverScans = list
                loadedFromServerOnce = true
                saveCache(raw)
                rebuildList()
            },
            onFailure = { e ->
                // Keep whatever we have (cache / local) and surface the error.
                _listError.value = e.message ?: "Échec du chargement"
                rebuildList()
            }
        )
        _isRefreshing.value = false
    }

    /**
     * Merge the server list (or cache) with locally-downloaded scans, flagging
     * download state and appending any scan present only on this phone.
     */
    private fun rebuildList() {
        val context = getApplication<Application>()
        val base = serverScans ?: emptyList()
        val known = base.map { scan ->
            scan.copy(
                downloadedLocally = LocalScanStorage.isDownloaded(context, scan.name),
                healthLocal = LocalScanStorage.hasHealthLog(context, scan.name),
                // Prefer the fuller notes text we've fetched (the list payload may omit it).
                notesText = notesTextCache[scan.name] ?: scan.notesText
            )
        }
        val knownNames = known.map { it.name }.toSet()

        val localOnly = LocalScanStorage.downloadedScanNames(context)
            .filter { it !in knownNames }
            .map { localOnlyScan(context, it) }

        _allScans.value = known + localOnly
        recompute()
        warmNotesCache()
    }

    /**
     * Fetch notes content for scans that have notes but no cached searchable text yet, so the
     * search bar can match on note content. One request per scan per session; only while online.
     */
    private fun warmNotesCache() {
        if (_connection.value !is ConnectionState.Connected) return
        val pending = _allScans.value.filter {
            it.notes.present && !it.localOnly && it.name !in notesTextCache
        }
        for (scan in pending) {
            notesTextCache[scan.name] = "" // reserve so we don't refetch in flight
            viewModelScope.launch {
                client.getNotes(scan.name).onSuccess { json ->
                    val text = flattenJsonText(json)
                    if (text != notesTextCache[scan.name]) {
                        notesTextCache[scan.name] = text
                        mergeNotesText(scan.name, text)
                    }
                }
            }
        }
    }

    /** Patch one scan's searchable notes text into the current list and re-filter/sort. */
    private fun mergeNotesText(name: String, text: String) {
        _allScans.value = _allScans.value.map {
            if (it.name == name) it.copy(notesText = text) else it
        }
        recompute()
    }

    // -----------------------------------------------------------------------
    // Search + sort (purely client-side; the displayed list is filtered then sorted)
    // -----------------------------------------------------------------------

    fun setQuery(q: String) {
        _query.value = q
        recompute()
    }

    fun setSort(s: ScanSort) {
        _sort.value = s
        settings.scanSort = s.name
        recompute()
    }

    private fun recompute() {
        val q = normalizeFuzzy(_query.value)
        val filtered = if (q.isEmpty()) _allScans.value else _allScans.value.filter { scan ->
            val haystack = listOf(scan.name, scan.location, scan.date, scan.notesText)
                .joinToString(" ")
            normalizeFuzzy(haystack).contains(q)
        }
        val comparator = when (_sort.value) {
            ScanSort.DATE_DESC -> compareByDescending<ScanInfo> { it.date }.thenByDescending { it.name }
            ScanSort.DATE_ASC -> compareBy<ScanInfo> { it.date }.thenBy { it.name }
            ScanSort.NAME_ASC -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            ScanSort.NAME_DESC -> compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name }
            ScanSort.PCD_DESC -> compareByDescending<ScanInfo> { it.pcd.sizeBytes }.thenBy { it.name }
            ScanSort.BAG_DESC -> compareByDescending<ScanInfo> { it.bag.sizeBytes }.thenBy { it.name }
        }
        _scans.value = filtered.sortedWith(comparator)
    }

    private fun localOnlyScan(context: android.content.Context, name: String): ScanInfo {
        val file = LocalScanStorage.pcdFile(context, name)
        // Best-effort split of "<date>_<location>_<run>".
        val parts = name.split("_")
        return ScanInfo(
            name = name,
            date = parts.getOrElse(0) { "" },
            location = parts.getOrElse(1) { "" },
            run = parts.getOrElse(2) { "" },
            bag = ScanArtifact(present = false),
            pcd = ScanArtifact(present = true, sizeBytes = file.length(), sizeHuman = humanSize(file.length())),
            config = ScanArtifact(present = false),
            notes = ScanArtifact(present = false),
            downloadedLocally = true,
            healthLocal = LocalScanStorage.hasHealthLog(getApplication(), name),
            localOnly = true
        )
    }

    /**
     * Make sure the scan's health log is on the phone, then invoke [onReady].
     *
     * The log is a small sidecar normally pulled alongside the `.pcd`, but a scan
     * downloaded before health logging existed — or one whose charts are opened without
     * downloading the cloud — won't have it yet, so fetch on demand. Already-local logs
     * open immediately and work offline.
     */
    fun openHealthLog(scanName: String, onReady: () -> Unit, onError: (String) -> Unit) {
        val context = getApplication<Application>()
        if (LocalScanStorage.hasHealthLog(context, scanName)) {
            onReady()
            return
        }
        viewModelScope.launch {
            client.downloadHealth(scanName, LocalScanStorage.healthFile(context, scanName)).fold(
                onSuccess = {
                    rebuildList()
                    onReady()
                },
                onFailure = { onError("Journal de santé indisponible pour ce scan.") }
            )
        }
    }

    // -----------------------------------------------------------------------
    // Downloads
    // -----------------------------------------------------------------------

    fun downloadPcd(scan: ScanInfo, onError: (String) -> Unit) {
        if (downloadJobs[scan.name]?.isActive == true) return
        val context = getApplication<Application>()
        val destination = LocalScanStorage.pcdFile(context, scan.name)
        downloadProgress[scan.name] = -1f
        val job = viewModelScope.launch {
            // Throttle to whole-percent changes: the streamer fires per 64 KB chunk (hundreds
            // of times/sec on WiFi), and writing the snapshot map that often floods recomposition.
            var lastPct = Int.MIN_VALUE
            client.downloadPcd(scan.name, destination) { p ->
                val pct = if (p < 0f) -1 else (p * 100).toInt()
                if (pct != lastPct) {
                    lastPct = pct
                    downloadProgress[scan.name] = p
                }
            }
                .fold(
                    onSuccess = {
                        // Best-effort: grab the sidecar trajectory next to the pcd (ignore 404).
                        client.downloadTrajectory(
                            scan.name, LocalScanStorage.trajFile(context, scan.name)
                        )
                        // Same for the health log — a 404 just means a scan from before
                        // health logging existed.
                        client.downloadHealth(
                            scan.name, LocalScanStorage.healthFile(context, scan.name)
                        )
                        downloadProgress.remove(scan.name)
                        rebuildList()
                    },
                    onFailure = { e ->
                        downloadProgress.remove(scan.name)
                        if (e !is kotlinx.coroutines.CancellationException) {
                            onError(e.message ?: "Échec du téléchargement")
                        }
                    }
                )
            downloadJobs.remove(scan.name)
        }
        downloadJobs[scan.name] = job
    }

    fun cancelDownload(scanName: String) {
        downloadJobs[scanName]?.cancel()
        downloadJobs.remove(scanName)
        downloadProgress.remove(scanName)
    }

    fun isDownloading(scanName: String): Boolean = downloadProgress.containsKey(scanName)

    /** Absolute local file for a downloaded scan, or null if not downloaded. */
    fun localPcdFile(scanName: String): File? {
        val f = LocalScanStorage.pcdFile(getApplication(), scanName)
        return if (f.exists()) f else null
    }

    // -----------------------------------------------------------------------
    // Config & notes (one-shot, called from the UI's coroutine scope)
    // -----------------------------------------------------------------------

    suspend fun fetchConfig(name: String): Result<String> = client.getConfig(name)
    suspend fun fetchNotes(name: String): Result<JSONObject> = client.getNotes(name)
    suspend fun saveNotes(name: String, notes: JSONObject): Result<Unit> =
        client.putNotes(name, notes).onSuccess {
            // Keep the searchable notes text in sync with the just-saved form.
            val text = flattenJsonText(notes)
            notesTextCache[name] = text
            mergeNotesText(name, text)
        }

    // -----------------------------------------------------------------------
    // Settings
    // -----------------------------------------------------------------------

    fun updateBaseUrl(url: String) {
        settings.baseUrl = url
        client.updateBaseUrl(settings.baseUrl)
        // Force a fresh server fetch against the new address.
        loadedFromServerOnce = false
        _connection.value = ConnectionState.Connecting
        refresh()
    }

    // -----------------------------------------------------------------------
    // Offline cache (last /scans JSON written to a file)
    // -----------------------------------------------------------------------

    private fun loadCache() {
        val cache = LocalScanStorage.scansCacheFile(getApplication())
        if (!cache.exists()) return
        runCatching {
            val raw = cache.readText()
            serverScans = parseCachedScans(raw)
        }
    }

    private fun saveCache(raw: String) {
        runCatching { LocalScanStorage.scansCacheFile(getApplication()).writeText(raw) }
    }

    private fun parseCachedScans(raw: String): List<ScanInfo> {
        val arr = JSONObject(raw).optJSONArray("scans") ?: return emptyList()
        return (0 until arr.length()).map { ScanInfo.from(arr.getJSONObject(it)) }
    }

    override fun onCleared() {
        super.onCleared()
        stopStatusPolling()
    }
}

/** Scan-library sort orders. Default is [DATE_DESC] (newest first), the original behavior. */
enum class ScanSort(val label: String) {
    DATE_DESC("Date (récent → ancien)"),
    DATE_ASC("Date (ancien → récent)"),
    NAME_ASC("Nom (A → Z)"),
    NAME_DESC("Nom (Z → A)"),
    PCD_DESC("Taille PCD (lourd → léger)"),
    BAG_DESC("Taille Bag (lourd → léger)")
}

/**
 * Normalize a string for non-strict search: lowercase and drop separators so that `-`, `_`
 * and spaces are interchangeable ("cave-x", "cave_x", "cave x" all become "cavex").
 */
private fun normalizeFuzzy(s: String): String =
    s.lowercase().replace(Regex("[\\s_-]+"), "")

/** Human-readable byte size, used for local-only scans built from a file length. */
fun humanSize(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> String.format("%.1f GiB", bytes / gb)
        bytes >= mb -> String.format("%.1f MiB", bytes / mb)
        bytes >= kb -> String.format("%.1f KiB", bytes / kb)
        else -> "$bytes B"
    }
}
