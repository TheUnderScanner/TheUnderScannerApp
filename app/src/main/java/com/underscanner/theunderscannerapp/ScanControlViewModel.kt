package com.underscanner.theunderscannerapp

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** UI phase of the active-scan flow. */
enum class ControlPhase { Idle, Starting, Active, Stopping, Summary }

/**
 * Drives Phase 2: starting/stopping a scan and the live preview.
 *
 * Shared across the Control Room and Active Scan screens (hoisted in the NavHost) so the
 * accumulated preview and scan state survive navigation between them. The scan lives on the
 * Jetson; this VM never stops it implicitly — only [stopScan] (an explicit user action) does.
 */
class ScanControlViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsRepository(app)
    private val client = ScanApiClient(settings.baseUrl)

    // --- Connection / scan status ---
    private val _connection = mutableStateOf<ConnectionState>(ConnectionState.Connecting)
    val connection: State<ConnectionState> = _connection

    private val _scanStatus = mutableStateOf<ScanStatus?>(null)
    val scanStatus: State<ScanStatus?> = _scanStatus

    /**
     * Latest Jetson telemetry, refreshed by the same loop as the status polls. Holds its last
     * known value on a failed fetch — the connection indicator already reports the outage, and
     * blanking the readouts on one dropped packet would just make them flicker.
     */
    private val _health = mutableStateOf<SystemHealth?>(null)
    val health: State<SystemHealth?> = _health

    // --- Active-scan flow ---
    private val _phase = mutableStateOf(ControlPhase.Idle)
    val phase: State<ControlPhase> = _phase

    private val _scanName = mutableStateOf<String?>(null)
    val scanName: State<String?> = _scanName

    private val _message = mutableStateOf<String?>(null)
    val message: State<String?> = _message

    private val _stopResult = mutableStateOf<StopResult?>(null)
    val stopResult: State<StopResult?> = _stopResult

    private val _elapsed = mutableStateOf(0L)
    val elapsed: State<Long> = _elapsed

    // --- Live preview ---
    val previewCloud = PreviewCloud()

    /** LiDAR path accumulated from the pose stream; drawn as the trajectory polyline. */
    val trajectory = Trajectory()

    private val _link = mutableStateOf(PreviewLinkState.Idle)
    val link: State<PreviewLinkState> = _link

    private val _pose = mutableStateOf<FloatArray?>(null)
    val pose: State<FloatArray?> = _pose

    private val _previewPoints = mutableStateOf(0)
    val previewPoints: State<Int> = _previewPoints

    private val _receiving = mutableStateOf(false)
    val receiving: State<Boolean> = _receiving

    @Volatile private var lastFrameAt = 0L
    val previewCapped: Boolean get() = previewCloud.capped

    // --- Shutdown ---
    private val _shuttingDown = mutableStateOf(false)
    val shuttingDown: State<Boolean> = _shuttingDown

    private var pollJob: Job? = null
    private var pollers = 0
    private var previewUiJob: Job? = null
    private var stream: PreviewStreamManager? = null

    val lastLocation: String get() = settings.lastLocation

    // --- SSH handoff helper ---
    val jetsonHost: String get() = SettingsRepository.hostFrom(settings.baseUrl)
    var sshUser: String
        get() = settings.sshUser
        set(value) { settings.sshUser = value }

    /** Re-read the (possibly edited) Jetson address from settings into the client. */
    private fun syncBaseUrl() = client.updateBaseUrl(settings.baseUrl)

    // -----------------------------------------------------------------------
    // Polling (connection + scan status). Active while a control screen is shown.
    // -----------------------------------------------------------------------

    /**
     * Start the connection/scan-status poll loop. Reference-counted: the Control Room and the
     * Active Scan screen both call this, and during the navigation transition both are briefly
     * alive. Without ref-counting the leaving screen's [stopPolling] (fired after the transition
     * animation) would cancel the shared job out from under the entering screen.
     */
    fun startPolling() {
        pollers++
        _shuttingDown.value = false
        if (pollJob?.isActive == true) return
        syncBaseUrl()
        pollJob = viewModelScope.launch {
            var tick = 0
            while (isActive) {
                if (tick % 2 == 0) pollOnce()       // network poll every ~2 s
                updateDerivedState()                 // local tick every ~1 s
                tick++
                delay(1000)
            }
        }
    }

    fun stopPolling() {
        pollers = (pollers - 1).coerceAtLeast(0)
        if (pollers > 0) return
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun pollOnce() {
        // While the Jetson is halting, the lost connection is expected — don't flag it as error.
        if (_shuttingDown.value) {
            _connection.value = ConnectionState.Offline("Jetson en cours d'arrêt")
            return
        }
        client.getStatus().fold(
            onSuccess = { _connection.value = ConnectionState.Connected(it) },
            onFailure = { _connection.value = ConnectionState.Offline(it.message ?: "Injoignable") }
        )
        client.getScanStatus().fold(
            onSuccess = { status ->
                _scanStatus.value = status
                // Resync elapsed to the server's authoritative value.
                if (status.running) _elapsed.value = status.elapsedS
            },
            onFailure = { /* keep last known status; connection state already reflects the error */ }
        )
        client.getHealth().fold(
            onSuccess = { _health.value = it },
            onFailure = { /* telemetry is best-effort; never let it drive connection state */ }
        )
    }

    private fun updateDerivedState() {
        if (_phase.value == ControlPhase.Active && _scanStatus.value?.running == true) {
            _elapsed.value = _elapsed.value + 1
        }
    }

    // -----------------------------------------------------------------------
    // Live-preview UI ticker. Tied to attach/detach (Active screen only), so the point
    // counter and "receiving" indicator track the accumulating cloud at ~4 Hz regardless of
    // the network poll loop — the renderer reads the same [previewCloud] directly.
    // -----------------------------------------------------------------------

    private fun startPreviewUi() {
        if (previewUiJob?.isActive == true) return
        previewUiJob = viewModelScope.launch {
            while (isActive) {
                _previewPoints.value = previewCloud.pointCount
                _receiving.value = System.currentTimeMillis() - lastFrameAt < 3000
                delay(250)
            }
        }
    }

    private fun stopPreviewUi() {
        previewUiJob?.cancel()
        previewUiJob = null
    }

    // -----------------------------------------------------------------------
    // Start / stop
    // -----------------------------------------------------------------------

    /** Start a new scan. [onActive] is invoked when the active-scan screen should open. */
    fun startScan(location: String, onActive: () -> Unit, onError: (String) -> Unit) {
        if (_phase.value == ControlPhase.Starting) return
        syncBaseUrl()
        _phase.value = ControlPhase.Starting
        _message.value = null
        val loc = location.ifBlank { settings.lastLocation }
        viewModelScope.launch {
            client.startScan(loc).fold(
                onSuccess = { result ->
                    if (loc.isNotBlank()) settings.lastLocation = loc
                    previewCloud.clear()
                    trajectory.clear()
                    _pose.value = null
                    _scanName.value = result.scan
                    _elapsed.value = 0
                    _phase.value = ControlPhase.Active
                    onActive()
                },
                onFailure = { e ->
                    if (e is ScanAlreadyRunningException) {
                        // A scan is already running on the Jetson — resume it.
                        _message.value = "Un scan est déjà en cours — reprise."
                        _scanName.value = _scanStatus.value?.scan
                        _phase.value = ControlPhase.Active
                        onActive()
                    } else {
                        _phase.value = ControlPhase.Idle
                        onError(e.message ?: "Échec du démarrage")
                    }
                }
            )
        }
    }

    /** Open the active screen for a scan already running on the Jetson (resume). */
    fun resumeActive(onActive: () -> Unit) {
        _scanName.value = _scanStatus.value?.scan
        _elapsed.value = _scanStatus.value?.elapsedS ?: 0
        _phase.value = ControlPhase.Active
        onActive()
    }

    fun stopScan() {
        if (_phase.value == ControlPhase.Stopping) return
        _phase.value = ControlPhase.Stopping
        viewModelScope.launch {
            client.stopScan().fold(
                onSuccess = { result ->
                    detachPreview()
                    _stopResult.value = result
                    _phase.value = ControlPhase.Summary
                },
                onFailure = { e ->
                    detachPreview()
                    // No scan running == already stopped; still show a summary-less close.
                    _message.value = e.message
                    _stopResult.value = _scanName.value?.let { StopResult(it, "—", "—") }
                    _phase.value = ControlPhase.Summary
                }
            )
        }
    }

    // -----------------------------------------------------------------------
    // Preview stream lifecycle (tied to the Active Scan screen)
    // -----------------------------------------------------------------------

    fun attachPreview() {
        if (stream != null) return
        syncBaseUrl()
        val manager = PreviewStreamManager(
            client = client,
            cloud = previewCloud,
            onPose = { x, y, z ->
                _pose.value = floatArrayOf(x, y, z)
                trajectory.append(x, y, z)
            },
            onState = { _link.value = it },
            onFrame = { lastFrameAt = System.currentTimeMillis() },
            isRunning = { _scanStatus.value?.running ?: true }
        )
        stream = manager
        manager.start(viewModelScope)
        startPreviewUi()
    }

    /** Close the WebSocket when leaving the active screen — does NOT stop the scan. */
    fun detachPreview() {
        stopPreviewUi()
        stream?.stop()
        stream = null
        _link.value = PreviewLinkState.Idle
    }

    // -----------------------------------------------------------------------
    // System power
    // -----------------------------------------------------------------------

    /**
     * Power the Jetson off. On success the host halts and the connection drops; we enter a
     * terminal [shuttingDown] state and stop polling so the dropped link is shown gracefully
     * (not as an error). A 409 means a scan is running — surfaced via [onError].
     */
    fun shutdownJetson(onError: (String) -> Unit) {
        if (_shuttingDown.value) return
        syncBaseUrl()
        viewModelScope.launch {
            client.shutdown().fold(
                onSuccess = {
                    _shuttingDown.value = true
                    _connection.value = ConnectionState.Offline("Jetson en cours d'arrêt")
                },
                onFailure = { e ->
                    onError(
                        if (e is ScanAlreadyRunningException)
                            "Un scan est en cours — arrête-le avant d'éteindre."
                        else e.message ?: "Échec de l'extinction"
                    )
                }
            )
        }
    }

    /** Reset after the post-scan summary is dismissed. */
    fun finishSummary() {
        _phase.value = ControlPhase.Idle
        _stopResult.value = null
        _scanName.value = null
        _message.value = null
        previewCloud.clear()
        trajectory.clear()
        _pose.value = null
        _previewPoints.value = 0
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
        detachPreview()
    }
}
