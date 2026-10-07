package com.omnidev.workspace.data.chatmedia

import com.omnidev.workspace.data.model.ModelProvider

enum class MediaKind(val title: String, val action: String) {
    IMAGE("Images", "image"), VIDEO("Video", "video"), MUSIC("Music & songs", "music");
    companion object { fun fromAction(value: String) = entries.firstOrNull { it.action == value } }
}

/** User-selected defaults. Jobs keep a snapshot; a later disable still stops queued/running work. */
data class MediaConfig(
    val enabled: Boolean = false,
    val provider: ModelProvider = ModelProvider.GEMINI,
    val model: String = "gemini-3.1-flash-image",
    val aspect: String = "1:1",
    val resolution: String = "1K",
    val quality: String = "auto",
    val format: String = "png",
    val background: String = "auto",
    val compression: Int = 85,
    val durationSeconds: Int = 8,
    val videoAudio: Boolean = true,
    val style: String = "",
    val negativePrompt: String = "",
    val lyrics: String = "",
    val instrumental: Boolean = false,
    val tempoBpm: Int = 0,
    val genre: String = "",
    val mood: String = "",
    val instruments: String = "",
    val language: String = "",
    val allowOverrides: Boolean = true,
    val announceCompletion: Boolean = true,
    val autoSaveToGallery: Boolean = false
) {
    companion object {
        fun defaults(kind: MediaKind) = when (kind) {
            MediaKind.IMAGE -> MediaConfig()
            MediaKind.VIDEO -> MediaConfig(model = "veo-3.1-fast-generate-preview", aspect = "16:9", resolution = "720p", format = "mp4")
            MediaKind.MUSIC -> MediaConfig(model = "lyria-3-clip-preview", aspect = "", resolution = "", format = "mp3", durationSeconds = 30)
        }
    }
}

data class MediaPreferences(val selections: Map<MediaKind, MediaConfig> = MediaKind.entries.associateWith(MediaConfig::defaults)) {
    operator fun get(kind: MediaKind) = selections[kind] ?: MediaConfig.defaults(kind)
    fun with(kind: MediaKind, config: MediaConfig) = copy(selections = selections + (kind to config))
}

/** The text agent can customize an allowed request, never turn media on or select another provider/model. */
object MediaRequestPolicy {
    fun resolve(kind: MediaKind, preferences: MediaPreferences, connected: Set<ModelProvider>, args: Map<String, String>): MediaConfig {
        var value = preferences[kind]
        require(value.enabled) { "${kind.title} generation is disabled. The user must enable it in Model Selection; do not generate using another route." }
        require(value.provider in connected) { "Connect the selected ${value.provider.displayName} provider in Providers first." }
        require(args["provider"] == null || args["provider"].equals(MediaModelCatalog.key(value.provider), true)) { "Use the user's selected provider from Model Selection." }
        require(args["model"] == null || args["model"] == value.model) { "Use the user's selected media model from Model Selection." }
        val customKeys = setOf("aspect_ratio", "resolution", "quality", "format", "duration_seconds", "negative_prompt", "style", "lyrics", "instrumental", "tempo_bpm", "genre", "mood", "instruments", "language", "video_audio", "background", "compression")
        val supported = when(kind) {
            MediaKind.IMAGE -> setOf("aspect_ratio", "resolution", "quality", "format", "background", "compression", "style", "negative_prompt")
            MediaKind.VIDEO -> setOf("aspect_ratio", "resolution", "duration_seconds", "video_audio", "style", "negative_prompt")
            MediaKind.MUSIC -> setOf("format", "duration_seconds", "style", "negative_prompt", "lyrics", "instrumental", "tempo_bpm", "genre", "mood", "instruments", "language")
        }
        require(args.keys.none { it in customKeys && it !in supported }) { "This setting does not apply to ${kind.title.lowercase()} generation." }
        if (!value.allowOverrides) require(args.keys.none { it in customKeys }) { "Request-specific media settings are locked in Model Selection. Use the saved defaults." }
        fun number(key: String, fallback: Int) = args[key]?.let { it.toIntOrNull() ?: error("Invalid $key.") } ?: fallback
        fun bool(key: String, fallback: Boolean) = args[key]?.let { it.toBooleanStrictOrNull() ?: error("Invalid $key.") } ?: fallback
        value = value.copy(aspect = args["aspect_ratio"] ?: value.aspect, resolution = args["resolution"] ?: value.resolution,
            quality = args["quality"] ?: value.quality, format = args["format"] ?: value.format,
            background = args["background"] ?: value.background, compression = number("compression", value.compression),
            durationSeconds = number("duration_seconds", value.durationSeconds), style = args["style"] ?: value.style,
            negativePrompt = args["negative_prompt"] ?: value.negativePrompt, lyrics = args["lyrics"] ?: value.lyrics,
            instrumental = bool("instrumental", value.instrumental), videoAudio = bool("video_audio", value.videoAudio),
            tempoBpm = number("tempo_bpm", value.tempoBpm), genre = args["genre"] ?: value.genre,
            mood = args["mood"] ?: value.mood, instruments = args["instruments"] ?: value.instruments,
            language = args["language"] ?: value.language)
        validate(kind, value)
        return value
    }

    fun validate(kind: MediaKind, value: MediaConfig) {
        require(MediaModelCatalog.supports(kind, value.provider, value.model)) { "Choose a supported ${kind.title.lowercase()} model in Model Selection." }
        require(value.style.length <= 2000 && value.negativePrompt.length <= 2000 && value.lyrics.length <= 3500 &&
            listOf(value.genre, value.mood, value.instruments, value.language).all { it.length <= 200 }) { "Media instructions are too long." }
        require(value.compression in 0..100 && (value.tempoBpm == 0 || value.tempoBpm in 40..240)) { "Invalid compression or tempo." }
        when (kind) {
            MediaKind.IMAGE -> {
                require(value.aspect in setOf("1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "21:9")) { "Unsupported image aspect ratio." }
                require(value.resolution in setOf("1K", "2K", "4K")) { "Unsupported image resolution." }
                require(value.quality in setOf("auto", "low", "medium", "high") && value.format in setOf("png", "jpeg", "webp")) { "Unsupported image quality or format." }
                require(value.background in setOf("auto", "opaque", "transparent") && !(value.background == "transparent" && value.format == "jpeg")) { "Transparent images need PNG or WebP." }
                if (value.provider == ModelProvider.XAI) require(value.resolution in setOf("1K", "2K") && value.format == "jpeg" && value.quality == "auto" && value.background == "auto") { "xAI images support 1K/2K native JPEG output." }
                if (value.provider == ModelProvider.GEMINI) require(value.format == "png" && value.quality == "auto" && value.background == "auto" && (!value.model.startsWith("gemini-2.5") || value.resolution == "1K")) { "Gemini returns its native format; Gemini 2.5 has 1K output." }
                if (value.provider == ModelProvider.OPENAI) require(value.aspect in setOf("1:1", "16:9", "9:16")) { "OpenAI images support square, landscape or portrait." }
            }
            MediaKind.VIDEO -> {
                require(value.aspect in setOf("16:9", "9:16") && value.format == "mp4") { "Choose landscape or portrait MP4 video." }
                if (value.provider == ModelProvider.GEMINI) {
                    require(value.durationSeconds in setOf(4, 6, 8) && value.resolution in setOf("720p", "1080p")) { "Veo supports 4, 6 or 8 seconds; 720p or 1080p." }
                    require(value.resolution != "1080p" || value.durationSeconds == 8) { "1080p Veo video requires 8 seconds." }
                    require(value.videoAudio) { "Veo 3.1 generates audio; use xAI for a muted generation." }
                } else require(value.durationSeconds in 1..15 && value.resolution in setOf("480p", "720p", "1080p")) { "xAI supports 1–15 seconds and 480p, 720p or 1080p." }
            }
            MediaKind.MUSIC -> {
                require(value.format in setOf("mp3", "wav") && value.durationSeconds in 15..180) { "Choose MP3/WAV and a 15–180 second duration hint." }
                if (value.model == "lyria-3-clip-preview") require(value.durationSeconds == 30 && value.format == "mp3") { "Lyria Clip produces a fixed 30-second MP3." }
                if (value.provider == ModelProvider.MINIMAX) require(value.format == "mp3") { "MiniMax music is saved as MP3." }
            }
        }
    }

    fun prompt(kind: MediaKind, text: String, config: MediaConfig) = buildString {
        append(text)
        if (config.style.isNotBlank()) append("\nCreative direction: ${config.style}")
        if (config.negativePrompt.isNotBlank()) append("\nAvoid: ${config.negativePrompt}")
        if (kind == MediaKind.MUSIC) {
            append(if (config.instrumental) "\nInstrumental only, no vocals." else "\nCreate a song with vocals.")
            if (config.model != "lyria-3-clip-preview") append("\nTarget duration: about ${config.durationSeconds} seconds.")
            if (config.tempoBpm > 0) append("\nTempo: ${config.tempoBpm} BPM.")
            listOf("Genre" to config.genre, "Mood" to config.mood, "Instruments" to config.instruments, "Lyrics language" to config.language)
                .filter { it.second.isNotBlank() }.forEach { append("\n${it.first}: ${it.second}") }
            if (!config.instrumental && config.lyrics.isNotBlank()) append("\nUse these lyrics:\n${config.lyrics}")
        }
    }
}
