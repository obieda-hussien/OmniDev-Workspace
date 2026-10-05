package com.omnidev.workspace.data.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceSessionPolicyTest {
    @Test fun privateUnlockNeedsEveryCapabilityAndReportsTheMissingSetup() {
        assertNull(VoiceSessionPolicy.unlockUnavailableReason(true, true, true, true, true, true, true, true))
        val expected = listOf("digital assistant", "local voice conversation", "offline speech model", "microphone", "Accessibility", "Request Android unlock", "spoken unlock code", "lock screen")
        for (missing in 0..7) {
            val capabilities = List(8) { it != missing }
            val reason = VoiceSessionPolicy.unlockUnavailableReason(capabilities[0], capabilities[1], capabilities[2], capabilities[3],
                capabilities[4], capabilities[5], capabilities[6], capabilities[7])
            assertTrue("Missing capability $missing: $reason", reason?.contains(expected[missing]) == true)
        }
    }
    @Test fun deviceGrantsAreExplainedBeforeVoiceModelSetup() {
        assertTrue(VoiceSessionPolicy.unlockUnavailableReason(false, false, false, false, false, false, false, false)!!.contains("Request Android unlock"))
        assertTrue(VoiceSessionPolicy.unlockUnavailableReason(false, false, false, false, false, true, false, false)!!.contains("spoken unlock code"))
    }
    @Test fun codesAndExplicitSpellingNeverBecomeOrdinaryTasks() {
        for (value in listOf("unlock phone", "open the lock screen", "Open lock screen", "افتح شاشة القفل", "افتح شاشه القفل", "افتح القفل", "رمز النقش", "1234", "one two three four", "واحد اثنين ثلاثة أربعة", "واحد اتنين تلاتة أربعة", "capital alpha bravo at five"))
            assertEquals(VoiceSessionPolicy.Intent.UNLOCK, VoiceSessionPolicy.intent(value))
        assertEquals(VoiceSessionPolicy.Intent.TASK, VoiceSessionPolicy.intent("open whatsapp and message my friend"))
        assertEquals(VoiceSessionPolicy.Intent.CANCEL, VoiceSessionPolicy.intent("cancel"))
    }
    @Test fun explicitConfirmationCannotBeHiddenInsideAnUtterance() {
        assertTrue(VoiceSessionPolicy.confirmed("confirm"))
        assertTrue(VoiceSessionPolicy.confirmed("تأكيد"))
        assertFalse(VoiceSessionPolicy.confirmed("confirm one two three four"))
        assertFalse(VoiceSessionPolicy.confirmed("no"))
    }
    @Test fun privateInputNeedsEveryPermissionAndAStillLockedDevice() {
        for (locked in listOf(false, true)) for (overlay in listOf(false, true)) for (voice in listOf(false, true)) for (unlock in listOf(false, true)) {
            assertEquals(locked && overlay && voice && unlock, VoiceSessionPolicy.canCapture(locked, overlay, voice, unlock, true))
        }
        assertTrue(VoiceSessionPolicy.canCapture(false, false, false, false, false))
        assertFalse(VoiceSessionPolicy.canCapture(true, false, true, true, false))
    }
    @Test fun onlyDeviceActionsAndCapturesPauseForUnlock() {
        assertTrue(VoiceSessionPolicy.shouldUnlockForTool("semantic_ui", "tap_xy"))
        assertTrue(VoiceSessionPolicy.shouldUnlockForTool("visual_inspector", "describe"))
        assertFalse(VoiceSessionPolicy.shouldUnlockForTool("semantic_ui", "get_summary"))
        assertFalse(VoiceSessionPolicy.shouldUnlockForTool("search_web", "search"))
        assertFalse(VoiceSessionPolicy.shouldUnlockForTool("device_admin", "request_unlock"))
    }
}
