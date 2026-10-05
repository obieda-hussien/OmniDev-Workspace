package com.omnidev.workspace.data.admin

/** One two-minute display lease, shared across assistant and authentication hosts. */
class ScreenAwakeDeadline {
    enum class Origin { ASSISTANT_INVOCATION, STANDALONE_REQUEST, HOST_HANDOFF }
    private var started = false
    private var deadline = 0L
    fun start(now: Long, origin: Origin = Origin.HOST_HANDOFF) {
        if (!started || origin != Origin.HOST_HANDOFF) { started = true; deadline = now + DURATION_MS }
    }
    fun remaining(now: Long): Long = (deadline - now).coerceIn(0, DURATION_MS)
    fun cancel() { deadline = 0 }
    companion object { const val DURATION_MS = 120_000L }
}
