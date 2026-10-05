package com.omnidev.workspace.data.model

import kotlinx.serialization.json.*

/** Strict transport decoding. A malformed or null payload must never turn into `{}`. */
object ToolArgumentCodec {
    data class Decoded(val arguments: Map<String, String>, val error: String? = null)

    fun decode(raw: String): Decoded = try {
        decode(Json.parseToJsonElement(raw))
    } catch (_: Exception) {
        Decoded(emptyMap(), "Arguments must be a valid JSON object.")
    }

    fun decode(element: JsonElement?): Decoded {
        if (element !is JsonObject) return Decoded(emptyMap(), "Arguments must be a JSON object.")
        if (element.values.any { it == JsonNull }) {
            return Decoded(emptyMap(), "Null arguments are unsupported; omit optional values.")
        }
        return Decoded(element.mapValues { (_, value) ->
            if (value is JsonPrimitive) value.content else value.toString()
        })
    }
}
