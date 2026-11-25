package com.omnidev.workspace.data.tools

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Compose the declared Bash stages without losing JSON escaping or upstream exit status. */
internal object RuntimeShellPipeline {
    fun script(pipelineJson: String): String? {
        val stages = try {
            Json.parseToJsonElement(pipelineJson) as? JsonArray
        } catch (_: SerializationException) {
            null
        }
        if (stages.isNullOrEmpty()) return null

        val commands = stages.map { stage ->
            val command = (stage as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (command.isBlank()) return null
            command
        }
        // Newlines keep a trailing comment inside its stage and support compound Bash commands.
        return "set -o pipefail || exit\n" + commands.joinToString(" | ") { "{\n$it\n}" }
    }
}
