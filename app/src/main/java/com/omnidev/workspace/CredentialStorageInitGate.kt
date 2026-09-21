package com.omnidev.workspace

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Prevents all credential-encrypted application initialization before first user unlock.
 * Initialization is idempotent across the boot receiver and dynamically registered unlock
 * receiver, but a failed attempt may be retried on a subsequent unlocked entry point.
 */
internal class CredentialStorageInitGate {
    private val started = AtomicBoolean(false)

    fun tryBegin(userUnlocked: Boolean): Boolean =
        userUnlocked && started.compareAndSet(false, true)

    fun resetAfterFailure() {
        started.set(false)
    }
}
