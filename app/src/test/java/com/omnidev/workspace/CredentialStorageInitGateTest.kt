package com.omnidev.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialStorageInitGateTest {
    @Test
    fun lockedProcessNeverInitializesCredentialStorage() {
        val gate = CredentialStorageInitGate()
        assertFalse(gate.tryBegin(userUnlocked = false))
        assertFalse(gate.tryBegin(userUnlocked = false))
        assertTrue(gate.tryBegin(userUnlocked = true))
    }

    @Test
    fun duplicateUnlockSignalsInitializeOnce() {
        val gate = CredentialStorageInitGate()
        assertTrue(gate.tryBegin(userUnlocked = true))
        assertFalse(gate.tryBegin(userUnlocked = true))
    }

    @Test
    fun failedPostUnlockStartupCanRetryWithoutAllowingLockedStartup() {
        val gate = CredentialStorageInitGate()
        assertTrue(gate.tryBegin(userUnlocked = true))
        gate.resetAfterFailure()
        assertFalse(gate.tryBegin(userUnlocked = false))
        assertTrue(gate.tryBegin(userUnlocked = true))
    }
}
