package com.omnidev.workspace.data.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceSessionFailureTest {
    @Test fun explainsMissingOfflineOutputWithoutBlamingAllPermissions() {
        assertTrue(VoiceSessionFailure.describe("speech_output").contains("TTS voice"))
        assertTrue(VoiceSessionFailure.describe("speech_output").contains("private local code entry"))
    }
    @Test fun separatesModelCaptureAndNativePromptFailures() {
        assertTrue(VoiceSessionFailure.describe("model").contains("model"))
        assertTrue(VoiceSessionFailure.describe("microphone").contains("microphone"))
        assertTrue(VoiceSessionFailure.describe("native_prompt").contains("Android credential prompt"))
    }
    @Test fun unknownStageCannotEchoAPrivateCredential() {
        val output = VoiceSessionFailure.describe("secret=123456")
        assertFalse(output.contains("123456"))
        assertFalse(output.contains("secret="))
    }
}
