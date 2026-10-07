package com.omnidev.workspace.data.chatmedia

import com.omnidev.workspace.data.model.AIModel
import com.omnidev.workspace.data.model.ModelProvider

data class MediaModelOption(val kind: MediaKind, val provider: ModelProvider, val id: String, val name: String, val note: String = "")

/** Output capability is separate from image/video input or generic text-model availability. */
object MediaModelCatalog {
    val builtIns = listOf(
        MediaModelOption(MediaKind.IMAGE, ModelProvider.GEMINI, "gemini-3.1-flash-image", "Gemini 3.1 Flash Image"),
        MediaModelOption(MediaKind.IMAGE, ModelProvider.GEMINI, "gemini-3-pro-image-preview", "Gemini 3 Pro Image"),
        MediaModelOption(MediaKind.IMAGE, ModelProvider.GEMINI, "gemini-2.5-flash-image", "Gemini 2.5 Flash Image", "1K output"),
        MediaModelOption(MediaKind.IMAGE, ModelProvider.OPENAI, "gpt-image-1.5", "GPT Image 1.5"),
        MediaModelOption(MediaKind.IMAGE, ModelProvider.OPENAI, "gpt-image-1", "GPT Image 1"),
        MediaModelOption(MediaKind.IMAGE, ModelProvider.OPENAI, "gpt-image-1-mini", "GPT Image 1 Mini"),
        MediaModelOption(MediaKind.IMAGE, ModelProvider.XAI, "grok-imagine-image-2.0", "Grok Imagine Image 2.0", "JPEG output"),
        MediaModelOption(MediaKind.VIDEO, ModelProvider.GEMINI, "veo-3.1-fast-generate-preview", "Veo 3.1 Fast"),
        MediaModelOption(MediaKind.VIDEO, ModelProvider.GEMINI, "veo-3.1-generate-preview", "Veo 3.1"),
        MediaModelOption(MediaKind.VIDEO, ModelProvider.XAI, "grok-imagine-video-1.5", "Grok Imagine Video 1.5"),
        MediaModelOption(MediaKind.MUSIC, ModelProvider.GEMINI, "lyria-3-clip-preview", "Lyria 3 Clip", "30-second MP3 clips, songs and instrumentals"),
        MediaModelOption(MediaKind.MUSIC, ModelProvider.GEMINI, "lyria-3.5", "Lyria 3.5", "Full songs; duration and tempo are creative guidance"),
        MediaModelOption(MediaKind.MUSIC, ModelProvider.MINIMAX, "music-3.0", "MiniMax Music 3.0", "Existing paid API accounts only"),
        MediaModelOption(MediaKind.MUSIC, ModelProvider.MINIMAX, "music-2.6", "MiniMax Music 2.6", "Existing paid API accounts only")
    )
    fun key(provider: ModelProvider) = when (provider) {
        ModelProvider.GEMINI -> "gemini"; ModelProvider.OPENAI -> "openai"; ModelProvider.OPEN_ROUTER -> "open_router"
        ModelProvider.XAI -> "xai"; ModelProvider.MINIMAX -> "minimax"; else -> error("Unsupported media provider.")
    }
    fun provider(key: String) = when (key) {
        "gemini" -> ModelProvider.GEMINI; "openai" -> ModelProvider.OPENAI; "open_router" -> ModelProvider.OPEN_ROUTER
        "xai" -> ModelProvider.XAI; "minimax" -> ModelProvider.MINIMAX; else -> error("Unsupported media provider.")
    }
    fun rawId(id: String) = id.substringAfter("::").removePrefix("models/")
    fun supports(kind: MediaKind, provider: ModelProvider, id: String): Boolean {
        if (id != rawId(id) || !id.matches(Regex("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)?"))) return false
        return builtIns.any { it.kind == kind && it.provider == provider && it.id == id } || when (provider) {
            ModelProvider.GEMINI -> when (kind) {
                MediaKind.IMAGE -> id.startsWith("gemini-") && id.contains("image")
                MediaKind.VIDEO -> id.startsWith("veo-3.1-") && id.contains("generate")
                MediaKind.MUSIC -> id.startsWith("lyria-3") && !id.contains("realtime")
            }
            ModelProvider.OPENAI -> kind == MediaKind.IMAGE && id.startsWith("gpt-image-")
            ModelProvider.OPEN_ROUTER -> kind == MediaKind.IMAGE && '/' in id
            ModelProvider.XAI -> (kind == MediaKind.IMAGE && id.startsWith("grok-imagine-image")) || (kind == MediaKind.VIDEO && id.startsWith("grok-imagine-video"))
            else -> false
        }
    }
    fun available(kind: MediaKind, connected: Set<ModelProvider>, discovered: List<AIModel>): List<MediaModelOption> {
        val live = discovered.mapNotNull { model ->
            val id = rawId(model.id)
            if (model.provider !in connected || !supports(kind, model.provider, id) ||
                (model.provider == ModelProvider.OPEN_ROUTER && "image" !in model.outputModalities)) null
            else MediaModelOption(kind, model.provider, id, model.displayName)
        }
        return (live + builtIns.filter { it.kind == kind && it.provider in connected }).distinctBy { it.provider to it.id }
    }
    fun select(kind: MediaKind, config: MediaConfig, provider: ModelProvider, model: String): MediaConfig {
        val defaults = MediaConfig.defaults(kind)
        return config.copy(enabled = true, provider = provider, model = model,
            aspect = if (kind == MediaKind.IMAGE && provider == ModelProvider.OPENAI && config.aspect !in setOf("1:1", "16:9", "9:16")) "1:1" else config.aspect,
            quality = defaults.quality,
            compression = defaults.compression,
            resolution = if (kind == MediaKind.IMAGE) "1K" else defaults.resolution,
            durationSeconds = if (kind == MediaKind.MUSIC && model != "lyria-3-clip-preview") 120 else defaults.durationSeconds,
            format = if (provider == ModelProvider.XAI && kind == MediaKind.IMAGE) "jpeg" else defaults.format,
            videoAudio = if (provider == ModelProvider.GEMINI) true else config.videoAudio,
            background = if (provider in setOf(ModelProvider.GEMINI, ModelProvider.XAI)) "auto" else config.background)
    }
}
