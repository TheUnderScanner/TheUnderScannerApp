package com.underscanner.theunderscannerapp

import android.content.Context

/**
 * Persists the editable Jetson base URL (host + port). The hotspot-assigned IP
 * changes between sessions, so this must be easy to edit and is saved to prefs.
 */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("underscanner_settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) {
            prefs.edit().putString(KEY_BASE_URL, normalize(value)).apply()
        }

    /** Last location entered when starting a scan, prefilled next time (sticky). */
    var lastLocation: String
        get() = prefs.getString(KEY_LAST_LOCATION, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_LAST_LOCATION, value).apply()
        }

    /** SSH username for the Jetson, used by the SSH-handoff helper (default [DEFAULT_SSH_USER]). */
    var sshUser: String
        get() = prefs.getString(KEY_SSH_USER, DEFAULT_SSH_USER) ?: DEFAULT_SSH_USER
        set(value) {
            val v = value.trim().ifBlank { DEFAULT_SSH_USER }
            prefs.edit().putString(KEY_SSH_USER, v).apply()
        }

    /** Persisted scan-library sort order (stored as the [ScanSort] name). */
    var scanSort: String
        get() = prefs.getString(KEY_SCAN_SORT, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_SCAN_SORT, value).apply()
        }

    /** Viewer: whether the axis-ruler helper lines are forced always-on (persisted across sessions). */
    var viewerHelpers: Boolean
        get() = prefs.getBoolean(KEY_VIEWER_HELPERS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_VIEWER_HELPERS, value).apply()
        }

    /** Viewer: orthographic (true) vs perspective (false) projection (persisted across sessions). */
    var viewerOrthographic: Boolean
        get() = prefs.getBoolean(KEY_VIEWER_ORTHO, false)
        set(value) {
            prefs.edit().putBoolean(KEY_VIEWER_ORTHO, value).apply()
        }

    companion object {
        // The Jetson's current hotspot IP. Editable in Settings.
        const val DEFAULT_BASE_URL = "http://10.75.93.211:8000"
        const val DEFAULT_SSH_USER = "orin4slam"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_LAST_LOCATION = "last_location"
        private const val KEY_SSH_USER = "ssh_user"
        private const val KEY_SCAN_SORT = "scan_sort"
        private const val KEY_VIEWER_HELPERS = "viewer_helpers"
        private const val KEY_VIEWER_ORTHO = "viewer_ortho"

        /**
         * Normalize user input into a usable base URL: ensure a scheme, drop any
         * trailing slash. Returns the default if the input is blank.
         */
        fun normalize(input: String): String {
            var url = input.trim()
            if (url.isEmpty()) return DEFAULT_BASE_URL
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            return url.trimEnd('/')
        }

        /**
         * Extract just the host from a base URL (no scheme, no port) for SSH handoff.
         * `http://10.75.93.211:8000` → `10.75.93.211`.
         */
        fun hostFrom(baseUrl: String): String {
            var s = normalize(baseUrl)
            s = s.substringAfter("://")
            s = s.substringBefore('/')        // drop any path
            s = s.substringBeforeLast(':')    // drop the :port
            return s
        }
    }
}
