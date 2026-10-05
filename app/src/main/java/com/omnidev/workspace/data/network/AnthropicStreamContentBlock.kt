package com.omnidev.workspace.data.network

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
