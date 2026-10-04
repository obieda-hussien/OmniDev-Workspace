package com.omnidev.workspace.data.voice

/** Voice matching only decides whether to offer a panel; it grants no device authority. */
object WakeActivationPolicy {
    fun canListen(enabled: Boolean, supported: Boolean, locked: Boolean, interactive: Boolean,
                  lockListening: Boolean, wakeConsent: Boolean, overlayConsent: Boolean): Boolean =
        enabled && supported && (interactive || wakeConsent) &&
            (!locked || (lockListening && wakeConsent && overlayConsent))
}
