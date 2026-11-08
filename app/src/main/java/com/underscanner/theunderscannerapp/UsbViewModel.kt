package com.underscanner.theunderscannerapp

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * USB stick plugged into the Jetson: the sticks it sees, mount / eject, and the (single) scan
 * copy job with its progress. Hoisted in the NavHost: the Control Room shows the stick card,
 * the Library the per-scan copy button and progress bar — both read this one state.
 *
 * The copy runs on the Jetson (rsync); this VM only polls `/usb/devices` (sticks + job), every
 * second while a copy runs, every 3 s otherwise, and only while a USB-aware screen is shown
 * (reference-counted like the other poll loops: two screens overlap during a transition).
 */
class UsbViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsRepository(app)
    private val client = ScanApiClient(settings.baseUrl)

    var devices by mutableStateOf<List<UsbDevice>>(emptyList())
        private set
    var transfer by mutableStateOf(UsbTransfer.IDLE)
        private set

    /** False against a backend without `/usb` (404): the USB UI then hides itself. */
    var supported by mutableStateOf(true)
        private set

    /** The mounted stick copies go to (the backend copies to the only mounted one). */
    val mounted: UsbDevice? get() = devices.firstOrNull { it.mounted }

    /**
     * The copy job to show on a scan card: running, or finished while this app watched it.
     * The backend keeps its last result forever; without this a "Copié ✓" from days ago
     * would stay on the card.
     */
    val visibleTransfer: UsbTransfer?
        get() = transfer.takeIf { it.active || (it.state != "idle" && it.scan == watchedScan) }

    private var watchedScan by mutableStateOf<String?>(null)

    /** Device being mounted / ejected (its button shows a spinner). */
    var busyDevice by mutableStateOf<String?>(null)
        private set

    private var pollJob: Job? = null
    private var pollers = 0

    fun startPolling() {
        pollers++
        if (pollJob?.isActive == true) return
        client.updateBaseUrl(settings.baseUrl)
        pollJob = viewModelScope.launch {
            while (isActive) {
                refreshOnce()
                delay(if (transfer.active) 1000 else 3000)
            }
        }
    }

    fun stopPolling() {
        pollers = (pollers - 1).coerceAtLeast(0)
        if (pollers > 0) return
        pollJob?.cancel()
        pollJob = null
    }

    suspend fun refreshOnce() {
        client.getUsbDevices().fold(
            onSuccess = { (d, t) ->
                devices = d
                transfer = t
                supported = true
                if (t.active) watchedScan = t.scan
            },
            onFailure = { e ->
                if (e is HttpStatusException && e.code == 404) supported = false
                // other failures: the connection indicator already says so; keep last state
            }
        )
    }

    private fun act(device: String?, onResult: (String?) -> Unit, call: suspend () -> Result<*>) {
        viewModelScope.launch {
            busyDevice = device
            val r = call()
            refreshOnce()
            busyDevice = null
            onResult(r.exceptionOrNull()?.let { it.message ?: "Erreur" })
        }
    }

    fun mount(device: UsbDevice, onResult: (String?) -> Unit) =
        act(device.device, onResult) { client.mountUsb(device.device) }

    fun eject(device: UsbDevice, onResult: (String?) -> Unit) =
        act(device.device, onResult) { client.ejectUsb(device.device) }

    suspend fun plan(scan: String): Result<UsbPlan> = client.getUsbPlan(scan)

    fun startCopy(scan: String, onResult: (String?) -> Unit) {
        watchedScan = scan
        act(null, onResult) { client.startUsbCopy(scan) }
    }

    fun cancelCopy() {
        viewModelScope.launch {
            client.cancelUsbTransfer()
            refreshOnce()
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }
}
