package com.omnidev.workspace.data.admin

/** Separate user grants; a flavor's automatic confirmations never create these grants. */
object DeviceConsentPolicy {
    enum class Scope(val title: String, val detail: String) {
        WAKE("Wake the screen", "Allow Omni to turn the display on for a requested task."),
        UNLOCK("Request Android unlock", "Allow Omni to show Android's unlock prompt. Android verifies your identity."),
        LOCK_OVERLAY("Assistant on the lock screen", "Show the assistant while locked. An existing conversation is hidden until you unlock."),
        LOCK_OBSERVE("Inspect the lock screen", "Allow semantic inspection of visible lock-screen controls. PIN entry and screenshots remain private."),
        SETTINGS("Inspect and operate Settings", "Allow Android Settings inspection and interaction, including sensitive settings exposed by Android. Protected inputs stay private."),
        SAVED_PIN("Use a local unlock PIN", "Admin only. Store a PIN locally and authorize one keypad attempt for the next 15 minutes. The PIN never enters model arguments or chat.")
    }

    fun denial(locked: Boolean, settings: Boolean, screenshot: Boolean, mutation: Boolean,
               observe: Boolean, settingsConsent: Boolean): String? = when {
        locked && (screenshot || mutation) -> "Locked-screen images and general interaction are private. Use device_admin request_unlock or unlock_with_saved_pin."
        locked && !observe -> "Enable Inspect the lock screen in Device access first."
        settings && !settingsConsent -> "Enable Inspect and operate Settings in Device access first."
        else -> null
    }
}

/** In-memory, monotonic, one-shot authorization. A process restart or reboot removes it. */
class OneShotUnlockPermit {
    private var deadline = 0L
    @Synchronized fun arm(now: Long) { deadline = now + 15 * 60_000L }
    @Synchronized fun active(now: Long): Boolean = deadline > 0 && now < deadline
    @Synchronized fun consume(now: Long): Boolean {
        val allowed = active(now)
        deadline = 0
        return allowed
    }
    @Synchronized fun revoke() { deadline = 0 }
}
