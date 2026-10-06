package com.omnidev.workspace.data.admin

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SavedPinKeypadWaitTest {
    @Test fun waitsForKeypadInsteadOfFailingBeforeAndroidShowsIt() = runTest {
        assertEquals(SavedPinKeypadWait.Outcome.READY, SavedPinKeypadWait.await(
            { true }, { true }, { false }, { testScheduler.currentTime >= 600 }))
        assertEquals(600L, testScheduler.currentTime)
    }
    @Test fun absentOrNonemptyKeypadTimesOutWithoutAnAttempt() = runTest {
        assertEquals(SavedPinKeypadWait.Outcome.UNSUPPORTED, SavedPinKeypadWait.await(
            { true }, { true }, { false }, { false }))
        assertEquals(8_000L, testScheduler.currentTime)
    }
    @Test fun revokedOrExpiredPermitStopsWaitingBeforeInput() = runTest {
        assertEquals(SavedPinKeypadWait.Outcome.REVOKED, SavedPinKeypadWait.await(
            { true }, { testScheduler.currentTime < 450 }, { false }, { false }))
        assertEquals(450L, testScheduler.currentTime)
    }
    @Test fun manualOrBiometricUnlockWinsWithoutTouchingKeypad() = runTest {
        assertEquals(SavedPinKeypadWait.Outcome.UNLOCKED, SavedPinKeypadWait.await(
            { testScheduler.currentTime < 300 }, { true }, { false }, { false }))
        assertEquals(300L, testScheduler.currentTime)
    }
    @Test fun cancelledNativePromptWinsEvenIfKeypadIsStillVisible() = runTest {
        assertEquals(SavedPinKeypadWait.Outcome.PROMPT_FINISHED, SavedPinKeypadWait.await(
            { true }, { true }, { true }, { true }))
        assertEquals(0L, testScheduler.currentTime)
    }
    @Test fun revocationWinsOverReadyKeypad() = runTest {
        assertEquals(SavedPinKeypadWait.Outcome.REVOKED, SavedPinKeypadWait.await(
            { true }, { false }, { false }, { true }))
    }
}
