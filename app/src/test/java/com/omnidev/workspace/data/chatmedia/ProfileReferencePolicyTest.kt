package com.omnidev.workspace.data.chatmedia

import org.junit.Assert.*
import org.junit.Test

class ProfileReferencePolicyTest {
    private val face = "face-12345678-1234-1234-1234-123456789abc.jpg"
    private val body = "body-12345678-1234-1234-1234-123456789abc.jpg"
    @Test fun acceptsSupportedImageModels() {
        ProfileReferencePolicy.validate("image", "gemini", "gemini-2.5-flash-image")
        ProfileReferencePolicy.validate("image", "openai", "gpt-image-1.5")
    }
    @Test fun refusesVideoMusicAndUnsupportedProvidersRatherThanDroppingReferences() {
        for ((kind, provider, model) in listOf(Triple("video", "gemini", "veo-3.1-generate-preview"),
            Triple("music", "minimax", "music-3.0"), Triple("image", "xai", "grok-imagine-image-2.0"),
            Triple("image", "open_router", "provider/image"))) {
            assertTrue(runCatching { ProfileReferencePolicy.validate(kind, provider, model) }.isFailure)
        }
    }
    @Test fun revocationAndReplacementInvalidateQueuedSelection() {
        ProfileReferencePolicy.validateSelection(listOf(face, body), true, setOf(face, body))
        assertTrue(runCatching { ProfileReferencePolicy.validateSelection(listOf(face), false, setOf(face)) }.isFailure)
        assertTrue(runCatching { ProfileReferencePolicy.validateSelection(listOf(face), true, setOf(body)) }.isFailure)
    }
    @Test fun cannotReadArbitraryPathsOrEmptyAndDuplicateReferences() {
        for (value in listOf("../../secrets.xml", "/sdcard/me.jpg", "face-../../name.jpg", "https://example.com/photo.jpg")) {
            assertFalse(ProfileReferencePolicy.validFile(value))
            assertTrue(runCatching { ProfileReferencePolicy.validateSelection(listOf(value), true, setOf(value)) }.isFailure)
        }
        assertTrue(runCatching { ProfileReferencePolicy.validateSelection(emptyList(), true, emptySet()) }.isFailure)
        assertTrue(runCatching { ProfileReferencePolicy.validateSelection(listOf(face, face), true, setOf(face)) }.isFailure)
    }
}
