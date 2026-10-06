package com.omnidev.workspace.data.admin

import org.junit.Assert.*
import org.junit.Test

class SavedPinAuthorizationTest {
    private var disk = SavedPinAuthorization.State.NONE
    private var writable = true
    private fun recreateProcess() = SavedPinAuthorization(read = { disk }, write = { state ->
        if (writable) { disk = state; true } else false
    })

    @Test fun existingInstallationsDoNotGainPersistentPermission() {
        val auth = recreateProcess()
        assertFalse(auth.granted())
        assertFalse(auth.beginAttempt())
    }
    @Test fun authenticatedRememberSurvivesProcessAndDeviceRestart() {
        assertTrue(recreateProcess().rememberFromUser())
        repeat(5) {
            val auth = recreateProcess()
            assertTrue(auth.granted())
            assertFalse(auth.paused())
        }
    }
    @Test fun successfulUnlockKeepsAuthorizationForLaterRequests() {
        val auth = recreateProcess()
        auth.rememberFromUser()
        repeat(4) {
            assertTrue(auth.beginAttempt())
            assertTrue(auth.paused())
            assertFalse(auth.beginAttempt())
            assertTrue(auth.finishAttempt(androidUnlocked = true))
        }
        assertTrue(recreateProcess().granted())
        assertFalse(recreateProcess().paused())
    }
    @Test fun failedInputCannotBeRepeatedByASecondToolCall() {
        val auth = recreateProcess()
        auth.rememberFromUser()
        assertTrue(auth.beginAttempt())
        assertFalse(auth.finishAttempt(androidUnlocked = false))
        assertTrue(auth.granted())
        repeat(4) { assertFalse(auth.beginAttempt()) }
    }
    @Test fun interruptedInputStaysPausedAcrossRestart() {
        val auth = recreateProcess()
        auth.rememberFromUser()
        assertTrue(auth.beginAttempt())
        // No completion callback: process death must not make this a new credential attempt.
        assertTrue(recreateProcess().paused())
        assertFalse(recreateProcess().beginAttempt())
    }
    @Test fun authenticatedResumeReenablesAPausedGrant() {
        val auth = recreateProcess()
        auth.rememberFromUser(); auth.beginAttempt(); auth.finishAttempt(false)
        assertTrue(auth.rememberFromUser())
        assertTrue(auth.beginAttempt())
    }
    @Test fun lateUnlockCannotRestoreRevokedAuthorization() {
        val auth = recreateProcess()
        auth.rememberFromUser(); auth.beginAttempt()
        assertTrue(auth.revoke())
        assertFalse(auth.finishAttempt(true))
        assertFalse(recreateProcess().granted())
        assertFalse(recreateProcess().beginAttempt())
    }
    @Test fun unableToPersistAttemptMeansNoCredentialInputIsAuthorized() {
        val auth = recreateProcess()
        auth.rememberFromUser()
        writable = false
        assertFalse(auth.beginAttempt())
        assertEquals(SavedPinAuthorization.State.READY, disk)
    }
    @Test fun unableToPersistSuccessKeepsAttemptsPaused() {
        val auth = recreateProcess()
        auth.rememberFromUser(); auth.beginAttempt()
        writable = false
        assertFalse(auth.finishAttempt(true))
        assertFalse(recreateProcess().beginAttempt())
    }
    @Test fun clearingDataOrUninstallingDropsTheRememberedGrant() {
        val auth = recreateProcess()
        auth.rememberFromUser()
        disk = SavedPinAuthorization.State.NONE
        assertFalse(recreateProcess().granted())
    }
}
