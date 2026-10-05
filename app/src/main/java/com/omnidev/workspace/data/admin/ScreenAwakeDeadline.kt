package com.omnidev.workspace.data.admin

/** One two-minute display lease, shared across assistant and authentication hosts. */
class ScreenAwakeDeadline {
    private var started = false
    private var deadline = 0L
    fun start(now: Long, newInvocation: Boolean = false) {
        if (!started || newInvocation) { started = true; deadline = now + DURATION_MS }
    }
    fun remaining(now: Long): Long = (deadline - now).coerceIn(0, DURATION_MS)
    fun cancel() { deadline = 0 }
    companion object { const val DURATION_MS = 120_000L }
}
