package com.omnidev.workspace.data.chatmedia

import com.omnidev.workspace.data.model.ModelProvider
import kotlinx.serialization.json.*

/** Explicit fields preserve backwards compatibility without storing provider credentials. */
object MediaPreferencesCodec {
    fun configJson(c: MediaConfig) = buildJsonObject {
        put("enabled", c.enabled); put("provider", c.provider.name); put("model", c.model)
        put("aspect", c.aspect); put("resolution", c.resolution); put("quality", c.quality); put("format", c.format)
        put("background", c.background); put("compression", c.compression); put("duration", c.durationSeconds)
        put("video_audio", c.videoAudio); put("style", c.style); put("negative", c.negativePrompt); put("lyrics", c.lyrics)
        put("instrumental", c.instrumental); put("tempo", c.tempoBpm); put("genre", c.genre); put("mood", c.mood)
        put("instruments", c.instruments); put("language", c.language); put("overrides", c.allowOverrides)
        put("announce", c.announceCompletion); put("gallery", c.autoSaveToGallery)
    }
    fun config(kind: MediaKind, json: JsonObject): MediaConfig {
        val d = MediaConfig.defaults(kind)
        fun text(key: String, default: String) = json[key]?.jsonPrimitive?.content ?: default
        fun flag(key: String, default: Boolean) = json[key]?.jsonPrimitive?.booleanOrNull ?: default
        fun number(key: String, default: Int) = json[key]?.jsonPrimitive?.intOrNull ?: default
        return d.copy(enabled = flag("enabled", false), provider = ModelProvider.valueOf(text("provider", d.provider.name)),
            model = text("model", d.model), aspect = text("aspect", d.aspect), resolution = text("resolution", d.resolution),
            quality = text("quality", d.quality), format = text("format", d.format), background = text("background", d.background),
            compression = number("compression", d.compression), durationSeconds = number("duration", d.durationSeconds),
            videoAudio = flag("video_audio", d.videoAudio), style = text("style", ""), negativePrompt = text("negative", ""),
            lyrics = text("lyrics", ""), instrumental = flag("instrumental", false), tempoBpm = number("tempo", 0),
            genre = text("genre", ""), mood = text("mood", ""), instruments = text("instruments", ""), language = text("language", ""),
            allowOverrides = flag("overrides", true), announceCompletion = flag("announce", true), autoSaveToGallery = flag("gallery", false))
            .also { MediaRequestPolicy.validate(kind, it) }
    }
    fun encode(value: MediaPreferences) = buildJsonObject {
        MediaKind.entries.forEach { put(it.name, configJson(value[it])) }
    }.toString()
    fun decode(value: String?): MediaPreferences {
        val json = runCatching { Json.parseToJsonElement(value.orEmpty()).jsonObject }.getOrNull() ?: return MediaPreferences()
        return MediaPreferences(MediaKind.entries.associateWith { kind ->
            runCatching { config(kind, json[kind.name]!!.jsonObject) }.getOrDefault(MediaConfig.defaults(kind))
        })
    }
}
