package com.omnidev.workspace.data.admin

/** Android callbacks are hints; only a current keyguard read can prove unlock. */
class KeyguardUnlockSession(private val deadline: Long) {
    enum class Signal { SUCCEEDED, CANCELLED, ERROR }
    private var requested = false
    private var signal: Signal? = null
    private var settleDeadline: Long? = null
    var result: String? = null
        private set

    fun begin(resumed: Boolean, focused: Boolean): Boolean {
        if (requested || result != null || !resumed || !focused) return false
        requested = true
        return true
    }

    fun callback(value: Signal, now: Long) {
        if (!requested || result != null) return
        signal = value
        // Give OEM keyguard state a short settling window, without extending the session.
        if (settleDeadline == null) settleDeadline = minOf(deadline, now + 1_500)
    }

    fun observe(now: Long, locked: Boolean, consent: Boolean): String? {
        if (result != null) return result
        result = when {
            !consent -> "DENIED: Android unlock consent was revoked."
            !locked -> "UNLOCKED: verified with Android keyguard state."
            settleDeadline?.let { now >= it } == true -> when (signal) {
                Signal.CANCELLED -> "USER_ACTION_REQUIRED: Android closed the unlock prompt while the device remained locked. Unlock with your fingerprint or device code, then continue the task. No credential was entered by this host."
                Signal.ERROR -> "USER_ACTION_REQUIRED: Android could not display its unlock prompt. Open Omni in the foreground and unlock manually, then continue the task."
                else -> "USER_ACTION_REQUIRED: Android reported dismissal but the device is still locked. Unlock manually, then continue the task."
            }
            now >= deadline -> "USER_ACTION_REQUIRED: Android unlock timed out. Unlock with your fingerprint or device code, then continue the task."
            else -> null
        }
        return result
    }
}
