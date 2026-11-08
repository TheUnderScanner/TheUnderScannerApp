package com.underscanner.theunderscannerapp

/**
 * Keeps the Jetson's wall clock corrected from the phone's.
 *
 * The Jetson runs its own access point, so it has no NTP and its clock drifts by days. The
 * backend keeps an in-memory offset (`clock_offset_s` in `/status`) that it applies to every
 * user-facing timestamp. Process-wide (an `object`) because both polling ViewModels see
 * `/status`; whichever polls first does the sync.
 *
 * Syncs when:
 *  - the backend reports offset 0 → never synced since it (re)started;
 *  - the last sync is older than [RESYNC_MS] → corrects slow drift during a long session.
 * Attempts are rate-limited by [RETRY_MS] so a backend without `POST /clock` (404) or a
 * failing call isn't hammered every poll. A backend too old to report the offset is skipped.
 */
object ClockSync {
    private const val RETRY_MS = 30_000L
    private const val RESYNC_MS = 10 * 60_000L

    @Volatile private var lastAttemptAt = 0L
    @Volatile private var lastSyncAt = 0L

    suspend fun maybeSync(client: ScanApiClient, status: StatusInfo) {
        val offset = status.clockOffsetS ?: return
        val now = System.currentTimeMillis()
        val due = offset == 0f || now - lastSyncAt > RESYNC_MS
        if (!due || now - lastAttemptAt < RETRY_MS) return
        lastAttemptAt = now
        client.syncClock().onSuccess { lastSyncAt = System.currentTimeMillis() }
    }
}
