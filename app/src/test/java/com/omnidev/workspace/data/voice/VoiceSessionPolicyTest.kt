package com.omnidev.workspace.data.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceSessionPolicyTest {
    @Test fun codesAndExplicitSpellingNeverBecomeOrdinaryTasks() {
        for (value in listOf("unlock phone", "افتح القفل", "رمز النقش", "1234", "one two three four", "واحد اثنين ثلاثة أربعة", "واحد اتنين تلاتة أربعة", "capital alpha bravo at five"))
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
