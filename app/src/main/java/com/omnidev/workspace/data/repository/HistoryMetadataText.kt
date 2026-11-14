package com.omnidev.workspace.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Prefer the original composer text over expanded context; never inspect tool/assistant messages. */
internal object HistoryMetadataText {
    fun original(content: String, metadata: String): String {
        val input = try {
            Json.parseToJsonElement(metadata).jsonObject["userInput"]?.jsonPrimitive?.contentOrNull
        } catch (_: IllegalArgumentException) { null }
        val value = input?.takeIf { it.isNotBlank() } ?: content
        if (value.length <= 1500) return value
        val end = if (Character.isHighSurrogate(value[1499])) 1499 else 1500
        return value.substring(0, end)
    }
}
