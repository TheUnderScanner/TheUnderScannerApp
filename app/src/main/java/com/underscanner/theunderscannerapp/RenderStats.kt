package com.underscanner.theunderscannerapp

/**
 * Phone-side render KPIs over a rolling [WINDOW_MS] window. Written by the GL thread
 * ([record] once per frame), read by [ScanControlViewModel], which forwards them over the
 * preview socket so the backend can append them to the scan's health log (`ph_*` keys).
 */
class RenderStats {
    @Volatile var fps = 0f
        private set
    /** Longest frame-to-frame interval in the window: the stutter you actually feel. */
    @Volatile var frameMaxMs = 0f
        private set
    /** CPU → GPU upload rate (kB/s): ~0 once a file is loaded, small and steady when live. */
    @Volatile var uploadKbps = 0f
        private set
    @Volatile var chunks = 0
        private set

    private var windowStart = 0L
    private var frames = 0
    private var maxInterval = 0L
    private var lastFrame = 0L
    private var bytesAtWindowStart = 0L

    /** GL thread, once per frame. [bytesUploaded] is the running total from [PointChunks]. */
    fun record(nowMs: Long, bytesUploaded: Long, chunkCount: Int) {
        if (lastFrame != 0L) maxInterval = maxOf(maxInterval, nowMs - lastFrame)
        lastFrame = nowMs
        frames++
        if (windowStart == 0L) {
            windowStart = nowMs
            bytesAtWindowStart = bytesUploaded
            return
        }
        val span = nowMs - windowStart
        if (span >= WINDOW_MS) {
            fps = frames * 1000f / span
            frameMaxMs = maxInterval.toFloat()
            uploadKbps = (bytesUploaded - bytesAtWindowStart) / 1.024f / span
            chunks = chunkCount
            windowStart = nowMs
            bytesAtWindowStart = bytesUploaded
            frames = 0
            maxInterval = 0
        }
    }

    companion object {
        const val WINDOW_MS = 2000L
    }
}
