package com.omnidev.workspace.data.chatmedia

import com.omnidev.workspace.data.model.AIModel
import com.omnidev.workspace.data.model.ModelProvider
import org.junit.Assert.*
import org.junit.Test

class MediaSettingsTest {
    private val connected = setOf(ModelProvider.GEMINI, ModelProvider.OPENAI, ModelProvider.XAI)
    private fun enabled(kind: MediaKind) = MediaPreferences().with(kind, MediaConfig.defaults(kind).copy(enabled = true))
    @Test fun `every generation type is off initially and cannot be enabled by agent arguments`() {
        MediaKind.entries.forEach { kind ->
            assertFalse(MediaPreferences()[kind].enabled)
            assertTrue(runCatching { MediaRequestPolicy.resolve(kind, MediaPreferences(), connected, mapOf("enabled" to "true")) }.isFailure)
        }
    }
    @Test fun `selected account is mandatory and provider or model substitutions are rejected`() {
        assertTrue(runCatching { MediaRequestPolicy.resolve(MediaKind.IMAGE, enabled(MediaKind.IMAGE), emptySet(), emptyMap()) }.isFailure)
        listOf(mapOf("provider" to "openai"), mapOf("model" to "gpt-image-1.5")).forEach { args ->
            assertTrue(runCatching { MediaRequestPolicy.resolve(MediaKind.IMAGE, enabled(MediaKind.IMAGE), connected, args) }.isFailure)
        }
    }
    @Test fun `locked defaults block creative and numeric overrides while accepting ordinary descriptions`() {
        val prefs = enabled(MediaKind.VIDEO).with(MediaKind.VIDEO, enabled(MediaKind.VIDEO)[MediaKind.VIDEO].copy(allowOverrides = false))
        assertEquals(8, MediaRequestPolicy.resolve(MediaKind.VIDEO, prefs, connected, mapOf("prompt" to "ocean")).durationSeconds)
        listOf(mapOf("duration_seconds" to "4"), mapOf("negative_prompt" to "rain"), mapOf("video_audio" to "false")).forEach {
            assertTrue(runCatching { MediaRequestPolicy.resolve(MediaKind.VIDEO, prefs, connected, it) }.isFailure)
        }
    }
    @Test fun `enabled customization is bounded and respects provider combinations`() {
        val prefs = enabled(MediaKind.VIDEO)
        assertEquals(6, MediaRequestPolicy.resolve(MediaKind.VIDEO, prefs, connected, mapOf("duration_seconds" to "6")).durationSeconds)
        listOf(mapOf("duration_seconds" to "six"), mapOf("duration_seconds" to "6", "resolution" to "1080p"), mapOf("video_audio" to "false")).forEach {
            assertTrue(runCatching { MediaRequestPolicy.resolve(MediaKind.VIDEO, prefs, connected, it) }.isFailure)
        }
        assertTrue(runCatching { MediaRequestPolicy.resolve(MediaKind.IMAGE, enabled(MediaKind.IMAGE), connected, mapOf("format" to "webp")) }.isFailure)
    }
    @Test fun `parameters from a different media type cannot be silently ignored`() {
        assertTrue(runCatching { MediaRequestPolicy.resolve(MediaKind.IMAGE, enabled(MediaKind.IMAGE), connected, mapOf("lyrics" to "words")) }.isFailure)
        assertTrue(runCatching { MediaRequestPolicy.resolve(MediaKind.MUSIC, enabled(MediaKind.MUSIC), connected, mapOf("resolution" to "4K")) }.isFailure)
    }
    @Test fun `settings round trip all selections and customization without mixing chat roles`() {
        val image = MediaModelCatalog.select(MediaKind.IMAGE, MediaConfig(), ModelProvider.OPENAI, "gpt-image-1.5")
            .copy(format = "webp", background = "transparent", quality = "high", compression = 72, autoSaveToGallery = true, allowOverrides = false)
        val music = MediaConfig.defaults(MediaKind.MUSIC).copy(enabled = true, model = "lyria-3.5", durationSeconds = 120, format = "wav", genre = "pop", mood = "happy", instruments = "oud", language = "Arabic", lyrics = "مرحبا", tempoBpm = 100, announceCompletion = false)
        val all = enabled(MediaKind.VIDEO).with(MediaKind.IMAGE, image).with(MediaKind.MUSIC, music)
        assertEquals(all, MediaPreferencesCodec.decode(MediaPreferencesCodec.encode(all)))
        assertEquals(all[MediaKind.VIDEO], all.with(MediaKind.IMAGE, image.copy(enabled = false))[MediaKind.VIDEO])
    }
    @Test fun `missing and corrupt persisted preferences fail closed`() {
        listOf(null, "", "not json", "[]", "{\"IMAGE\":{\"enabled\":true,\"provider\":\"INVALID\"}}").forEach { value ->
            assertTrue(MediaKind.entries.all { !MediaPreferencesCodec.decode(value)[it].enabled })
        }
    }
    @Test fun `catalog only shows connected supported output models and does not confuse vision input`() {
        val vision = AIModel("open_router::vendor/vision", "Vision", ModelProvider.OPEN_ROUTER, contextWindow = 10000, supportsVision = true)
        val output = vision.copy(id = "open_router::vendor/image", displayName = "Image", outputModalities = setOf("image"))
        val live = MediaModelCatalog.available(MediaKind.IMAGE, setOf(ModelProvider.OPEN_ROUTER), listOf(vision, output))
        assertEquals(listOf("vendor/image"), live.map { it.id })
        assertTrue(MediaModelCatalog.available(MediaKind.VIDEO, setOf(ModelProvider.OPENAI), emptyList()).isEmpty())
        assertTrue(MediaModelCatalog.available(MediaKind.MUSIC, emptySet(), emptyList()).isEmpty())
    }
    @Test fun `switching providers clears incompatible native output settings and URL models are refused`() {
        val old = MediaConfig(format = "webp", quality = "high", background = "transparent", resolution = "4K", aspect = "21:9")
        val gemini = MediaModelCatalog.select(MediaKind.IMAGE, old, ModelProvider.GEMINI, "gemini-2.5-flash-image")
        val xai = MediaModelCatalog.select(MediaKind.IMAGE, old, ModelProvider.XAI, "grok-imagine-image-2.0")
        MediaRequestPolicy.validate(MediaKind.IMAGE, gemini); MediaRequestPolicy.validate(MediaKind.IMAGE, xai)
        assertEquals("jpeg", xai.format); assertEquals("auto", xai.background)
        assertFalse(MediaModelCatalog.supports(MediaKind.IMAGE, ModelProvider.OPENAI, "https://evil.example/model"))
    }
    @Test fun `music prompt includes vocals lyrics and guidance but instrumental suppresses lyrics`() {
        val config = MediaConfig.defaults(MediaKind.MUSIC).copy(model = "lyria-3.5", durationSeconds = 90, genre = "pop", lyrics = "secret lyric", language = "Arabic", tempoBpm = 95)
        val prompt = MediaRequestPolicy.prompt(MediaKind.MUSIC, "make music", config)
        assertTrue(prompt.contains("90 seconds")); assertTrue(prompt.contains("95 BPM")); assertTrue(prompt.contains("secret lyric"))
        assertFalse(MediaRequestPolicy.prompt(MediaKind.MUSIC, "make music", config.copy(instrumental = true)).contains("secret lyric"))
        assertTrue(runCatching { MediaRequestPolicy.validate(MediaKind.MUSIC, config.copy(model = "lyria-3-clip-preview")) }.isFailure)
    }
}
