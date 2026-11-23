package com.omnidev.workspace.data.model

import kotlinx.serialization.json.*

/** Strict object decoding. Top-level null fields are omissions; required fields are checked by preflight. */
object ToolArgumentCodec {
    data class Decoded(val arguments: Map<String, String>, val error: String? = null)

    /** Some compatible providers omit the payload for zero-argument discovery. */
    fun decodeProviderCall(name: String, raw: String?): Decoded =
        if (name == "discover_tools" && (raw.isNullOrBlank() || raw.trim() == "null")) Decoded(emptyMap())
        else decode(raw.orEmpty())

    fun decode(raw: String): Decoded = try {
        decode(Json.parseToJsonElement(raw))
    } catch (_: Exception) {
        Decoded(emptyMap(), "Arguments must be a valid JSON object.")
    }

    fun decode(element: JsonElement?): Decoded {
        if (element !is JsonObject) return Decoded(emptyMap(), "Arguments must be a JSON object.")
        return Decoded(element.filterValues { it != JsonNull }.mapValues { (_, value) ->
            if (value is JsonPrimitive) value.content else value.toString()
        })
    }
}
