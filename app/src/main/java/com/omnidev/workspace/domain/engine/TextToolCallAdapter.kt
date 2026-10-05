package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.*
import kotlinx.serialization.json.*
import java.util.UUID

/** Opt-in syntax in the agent contract, never regex extraction from prose or tool results. */
object TextToolCallAdapter {
    const val CONTRACT = "If native function calling is unavailable, output ONLY {\"omni_tool_call\":{\"name\":\"exact_name\",\"arguments\":{}}}. One call per response. Use only loaded tools; call discover_tools with arguments {\"query\":\"concrete operation\"} to load missing tools. Never embed this envelope in prose."

    fun adapt(response: CompletionResponse): CompletionResponse {
        if (response.toolCalls.isNotEmpty()) return response
        val raw = response.content.trim().let {
            if (it.startsWith("```json\n") && it.endsWith("```")) it.removePrefix("```json\n").removeSuffix("```").trim() else it
        }
        fun malformed(): CompletionResponse = response.copy(content = "", toolCalls = listOf(ToolCall(
            "text_${UUID.randomUUID()}", "discover_tools", emptyMap(),
            argumentError = "Malformed text tool envelope. Return one valid JSON object with omni_tool_call, name and arguments.",
            textProtocol = true
        )))
        val looksLikeEnvelope = Regex("^\\{\\s*\"omni_tool_call\"").containsMatchIn(raw)
        val root = try { Json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null } ?: return if (looksLikeEnvelope) malformed() else response
        if ("omni_tool_call" !in root) return response
        if (root.keys != setOf("omni_tool_call")) return malformed()
        val envelope = root["omni_tool_call"] as? JsonObject ?: return malformed()
        val name = envelope["name"] as? JsonPrimitive ?: return malformed()
        if (!name.isString) return malformed()
        val decoded = ToolArgumentCodec.decode(envelope["arguments"])
        val error = if (envelope.keys != setOf("name", "arguments")) "Only name and arguments are allowed in the envelope." else decoded.error
        return response.copy(content = "", toolCalls = listOf(ToolCall(
            "text_${UUID.randomUUID()}", name.content, decoded.arguments, argumentError = error, textProtocol = true
        )))
    }
}
