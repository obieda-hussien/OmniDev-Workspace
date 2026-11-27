package com.omnidev.workspace.data.network

import com.omnidev.workspace.data.model.ToolArgumentCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** A tool block can carry initial input before subsequent input_json_delta events. */
@Serializable
internal data class AnthropicStreamContentBlock(
    val type: String = "",
    val id: String? = null,
    val name: String? = null,
    val input: JsonElement? = null
)

/** Decode accumulated tool input while preserving the distinction between omission and an empty object. */
internal fun decodeAnthropicToolInput(
    name: String,
    initial: JsonElement?,
    streamed: String? = null
): ToolArgumentCodec.Decoded =
    when {
        streamed != null -> ToolArgumentCodec.decode(streamed)
        initial == null && name == "discover_tools" -> ToolArgumentCodec.Decoded(emptyMap())
        else -> ToolArgumentCodec.decode(initial)
    }
