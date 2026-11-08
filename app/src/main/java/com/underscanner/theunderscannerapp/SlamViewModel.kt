package com.underscanner.theunderscannerapp

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * SLAM algos + config profiles, as stored on the Jetson (the phone keeps no copy: the Jetson
 * is the source of truth and validates every write). Shared by Settings, the profile list /
 * editor screens and the Control Room's scan form, so it is hoisted in the NavHost.
 *
 * Everything here needs the Jetson: offline, [error] says so and the screens stay read-only.
 */
class SlamViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsRepository(app)
    private val client = ScanApiClient(settings.baseUrl)

    var algos by mutableStateOf<SlamAlgos?>(null)
        private set

    /** Algo whose profiles are listed (starts on the Jetson's default algo). */
    var algo by mutableStateOf<String?>(null)
        private set

    var profiles by mutableStateOf<List<SlamProfileInfo>>(emptyList())
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    val algoInfo: SlamAlgoInfo? get() = algos?.byId(algo)
    val defaultProfile: String? get() = profiles.firstOrNull { it.isDefault }?.name

    /** Re-read the (possibly edited) Jetson address, then the algos and the profile list. */
    fun refresh() {
        client.updateBaseUrl(settings.baseUrl)
        viewModelScope.launch {
            loading = true
            client.getSlamAlgos().fold(
                onSuccess = { a ->
                    algos = a
                    val id = algo?.takeIf { a.byId(it) != null } ?: a.default
                    algo = id
                    loadProfiles(id)
                },
                onFailure = { error = describe(it) }
            )
            loading = false
        }
    }

    private suspend fun loadProfiles(id: String) {
        client.getSlamProfiles(id).fold(
            onSuccess = { profiles = it; error = null },
            onFailure = { error = describe(it) }
        )
    }

    fun selectAlgo(id: String) {
        if (id == algo) return
        algo = id
        profiles = emptyList()
        viewModelScope.launch { loadProfiles(id) }
    }

    // ---- list actions: each reports through [onResult] (null = success, else a message) ----

    private fun act(onResult: (String?) -> Unit, call: suspend () -> Result<*>) {
        viewModelScope.launch {
            val r = call()
            algo?.let { loadProfiles(it) }
            if (r.isSuccess) {
                client.getSlamAlgos().onSuccess { algos = it }   // default_profile may have moved
            }
            onResult(r.exceptionOrNull()?.let(::describe))
        }
    }

    fun setDefaultAlgo(id: String, onResult: (String?) -> Unit) =
        act(onResult) { client.setSlamDefaults(algo = id, profile = null) }

    fun setDefaultProfile(name: String, onResult: (String?) -> Unit) {
        val a = algo ?: return
        act(onResult) { client.setSlamDefaults(algo = a, profile = name) }
    }

    fun copy(from: String, to: String, onResult: (String?) -> Unit) {
        val a = algo ?: return
        act(onResult) { client.copySlamProfile(a, from, to) }
    }

    fun rename(from: String, to: String, onResult: (String?) -> Unit) {
        val a = algo ?: return
        act(onResult) { client.renameSlamProfile(a, from, to) }
    }

    fun delete(name: String, onResult: (String?) -> Unit) {
        val a = algo ?: return
        act(onResult) { client.deleteSlamProfile(a, name) }
    }

    // ---- editor (called from the editor screen's coroutine scope) ----

    suspend fun loadText(algo: String, name: String): Result<String> =
        client.getSlamProfile(algo, name)

    suspend fun validate(algo: String, yaml: String): Result<SlamValidation> =
        client.validateSlam(algo, yaml = yaml)

    /** Store [yaml] (server validates again); the list is refreshed on success. */
    suspend fun save(algo: String, name: String, yaml: String): Result<SlamSaveResult> =
        client.putSlamProfile(algo, name, yaml).also { r ->
            if (r.isSuccess && algo == this.algo) loadProfiles(algo)
        }

    /** Per-scan check before starting: stored [profile] + [overrides] (Control Room). */
    suspend fun validateForScan(algo: String, profile: String?, overrides: String?): Result<SlamValidation> =
        client.validateSlam(algo, profile = profile, overrides = overrides)

    companion object {
        fun describe(t: Throwable): String = when (t) {
            is SlamConfigException -> t.message ?: "config refusée"
            is java.net.ConnectException, is java.net.SocketTimeoutException,
            is java.net.UnknownHostException, is java.net.NoRouteToHostException ->
                "Jetson injoignable"
            else -> t.message ?: t.javaClass.simpleName
        }

        /**
         * Light client-side check of a profile name; the backend's rule is authoritative
         * (letters/digits/._-, 64 max, first char a letter or digit).
         */
        fun nameProblem(name: String): String? = when {
            name.isBlank() -> "Nom vide"
            name.length > 64 -> "64 caractères maximum"
            !name.first().isLetterOrDigit() -> "Doit commencer par une lettre ou un chiffre"
            name.any { !(it.isLetterOrDigit() || it in "._-") } -> "Lettres, chiffres, . - _ uniquement"
            name == SlamProfileInfo.FACTORY -> "Nom réservé au profil usine"
            else -> null
        }
    }
}
