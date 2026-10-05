package com.omnidev.workspace.data.admin

import org.junit.Assert.*
import org.junit.Test

class KeyguardUnlockSessionTest {
    @Test fun promptWaitsForBothResumeAndWindowFocusAndOpensOnlyOnce() {
        val session = KeyguardUnlockSession(60_000)
        assertFalse(session.begin(false, false))
        assertFalse(session.begin(true, false))
        assertFalse(session.begin(false, true))
        assertTrue(session.begin(true, true))
        assertFalse(session.begin(true, true))
    }
    @Test fun cancelledCallbackCannotMaskBiometricUnlockDuringStatePropagation() {
        val session = KeyguardUnlockSession(60_000)
        session.begin(true, true)
        session.callback(KeyguardUnlockSession.Signal.CANCELLED, 100)
        assertNull(session.observe(101, locked = true, consent = true))
        assertNull(session.observe(1_599, locked = true, consent = true))
        assertTrue(session.observe(1_600, locked = false, consent = true)!!.startsWith("UNLOCKED:"))
    }
    @Test fun genuineCancellationIsBoundedAndExplainsWhatTheUserMustDo() {
        val session = KeyguardUnlockSession(60_000)
        session.begin(true, true)
        session.callback(KeyguardUnlockSession.Signal.CANCELLED, 100)
        val result = session.observe(1_600, locked = true, consent = true)!!
        assertTrue(result.startsWith("USER_ACTION_REQUIRED:"))
        assertTrue(result.contains("fingerprint or device code"))
        assertTrue(result.contains("then continue"))
        assertFalse(session.begin(true, true))
    }
    @Test fun successCallbackAloneNeverProvesUnlock() {
        val session = KeyguardUnlockSession(60_000)
        session.begin(true, true)
        session.callback(KeyguardUnlockSession.Signal.SUCCEEDED, 100)
        assertNull(session.observe(500, locked = true, consent = true))
        assertTrue(session.observe(1_600, locked = true, consent = true)!!.contains("still locked"))
    }
    @Test fun unlockedStateNeedsNoCallbackOrSecondPrompt() {
        val session = KeyguardUnlockSession(60_000)
        assertTrue(session.observe(0, locked = false, consent = true)!!.startsWith("UNLOCKED:"))
        assertFalse(session.begin(true, true))
    }
    @Test fun consentRevocationWinsEvenIfDeviceHasJustUnlocked() {
        val session = KeyguardUnlockSession(60_000)
        session.begin(true, true)
        session.callback(KeyguardUnlockSession.Signal.SUCCEEDED, 100)
        assertTrue(session.observe(200, locked = false, consent = false)!!.startsWith("DENIED:"))
    }
    @Test fun revocationStopsAStillPendingPromptImmediately() {
        val session = KeyguardUnlockSession(60_000)
        session.begin(true, true)
        assertTrue(session.observe(100, locked = true, consent = false)!!.startsWith("DENIED:"))
    }
    @Test fun blockedBackgroundStartHasAnAbsoluteDeadline() {
        val session = KeyguardUnlockSession(60_000)
        assertNull(session.observe(59_999, locked = true, consent = true))
        assertTrue(session.observe(60_000, locked = true, consent = true)!!.contains("timed out"))
    }
    @Test fun lateCallbackCannotExtendDeadline() {
        val session = KeyguardUnlockSession(60_000)
        session.begin(true, true)
        session.callback(KeyguardUnlockSession.Signal.ERROR, 59_999)
        assertTrue(session.observe(60_000, locked = true, consent = true)!!.contains("could not display"))
    }
    @Test fun completedSessionIgnoresLateCallbacksAndStateChanges() {
        val session = KeyguardUnlockSession(60_000)
        val result = session.observe(0, locked = false, consent = true)
        session.callback(KeyguardUnlockSession.Signal.CANCELLED, 100)
        assertEquals(result, session.observe(3_000, locked = true, consent = false))
    }
    @Test fun unrelatedCallbackBeforePromptCannotTerminateTheSession() {
        val session = KeyguardUnlockSession(60_000)
        session.callback(KeyguardUnlockSession.Signal.CANCELLED, 100)
        assertNull(session.observe(2_000, locked = true, consent = true))
        assertTrue(session.begin(true, true))
    }
}
