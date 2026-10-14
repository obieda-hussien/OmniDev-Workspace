package com.omnidev.workspace.data.debug

/** One report per blocked heartbeat; a recovered queue arms the next report. */
internal class MainThreadStallTracker(private val thresholdMs: Long = 10_000) {
    @Volatile private var pendingSince: Long? = null
    private var reported = false

    @Synchronized fun enqueue(now: Long): Boolean {
        if (pendingSince != null) return false
        pendingSince = now
        reported = false
        return true
    }

    @Synchronized fun acknowledge() { pendingSince = null; reported = false }

    @Synchronized fun shouldReport(now: Long, debuggerAttached: Boolean): Boolean {
        val started = pendingSince ?: return false
        if (debuggerAttached || reported || now - started < thresholdMs) return false
        reported = true
        return true
    }
}
