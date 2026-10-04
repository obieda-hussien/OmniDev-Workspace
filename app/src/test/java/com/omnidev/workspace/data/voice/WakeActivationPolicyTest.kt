package com.omnidev.workspace.data.voice

import org.junit.Assert.*
import org.junit.Test

class WakeActivationPolicyTest {
    @Test fun aVoiceMatchCannotSubstituteForAnyRequiredConsent() {
        for (enabled in listOf(false, true)) for (supported in listOf(false, true))
            for (lockListening in listOf(false, true)) for (wake in listOf(false, true)) for (overlay in listOf(false, true)) {
                assertEquals(enabled && supported && lockListening && wake && overlay,
                    WakeActivationPolicy.canListen(enabled, supported, true, false, lockListening, wake, overlay))
            }
    }
    @Test fun screenOffNeedsWakeConsentEvenWhenDeviceHasNoLock() {
        assertFalse(WakeActivationPolicy.canListen(true, true, false, false, false, false, false))
        assertTrue(WakeActivationPolicy.canListen(true, true, false, false, false, true, false))
        assertTrue(WakeActivationPolicy.canListen(true, true, false, true, false, false, false))
    }
}
