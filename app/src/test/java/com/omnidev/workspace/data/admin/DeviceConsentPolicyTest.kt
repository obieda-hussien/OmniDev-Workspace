package com.omnidev.workspace.data.admin

import org.junit.Assert.*
import org.junit.Test

class DeviceConsentPolicyTest {
    @Test fun lockedScreensNeverAllowImagesOrGeneralMutationEvenWithEveryConsent() {
        for (settings in listOf(false, true)) for (observe in listOf(false, true)) {
            assertNotNull(DeviceConsentPolicy.denial(true, settings, true, false, observe, true))
            assertNotNull(DeviceConsentPolicy.denial(true, settings, false, true, observe, true))
        }
    }
    @Test fun lockObservationAndSettingsConsentAreIndependent() {
        assertNotNull(DeviceConsentPolicy.denial(true, false, false, false, false, true))
        assertNull(DeviceConsentPolicy.denial(true, false, false, false, true, false))
        assertNotNull(DeviceConsentPolicy.denial(true, true, false, false, true, false))
        assertNull(DeviceConsentPolicy.denial(true, true, false, false, true, true))
    }
    @Test fun unlockedSettingsStillRequireTheirOwnConsent() {
        for (screenshot in listOf(false, true)) for (mutation in listOf(false, true)) {
            assertNotNull(DeviceConsentPolicy.denial(false, true, screenshot, mutation, true, false))
            assertNull(DeviceConsentPolicy.denial(false, true, screenshot, mutation, false, true))
        }
    }
    @Test fun ordinaryUnlockedAppsKeepTheirExistingAccess() {
        for (screenshot in listOf(false, true)) for (mutation in listOf(false, true))
            assertNull(DeviceConsentPolicy.denial(false, false, screenshot, mutation, false, false))
    }
    @Test fun pinPermitIsAbsentByDefaultAndConsumedBeforeAnyAttempt() {
        val permit = OneShotUnlockPermit()
        assertFalse(permit.consume(10))
        permit.arm(100)
        assertTrue(permit.consume(101))
        assertFalse(permit.consume(102))
        assertFalse(permit.active(102))
    }
    @Test fun pinPermitExpiresAtExactlyFifteenMinutes() {
        val permit = OneShotUnlockPermit()
        permit.arm(100)
        assertTrue(permit.active(900_099))
        assertFalse(permit.consume(900_100))
        assertFalse(permit.active(101))
    }
    @Test fun revocationAndProcessRestartCannotRestoreAPermit() {
        val permit = OneShotUnlockPermit()
        permit.arm(10)
        permit.revoke()
        assertFalse(permit.consume(11))
        permit.arm(20)
        assertFalse(OneShotUnlockPermit().consume(21))
        assertTrue(permit.consume(21))
    }
}
