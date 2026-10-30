package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.*
import kotlinx.serialization.json.*
import java.util.UUID

/** Bounded retrieval assistance. It never executes an operation or repairs arguments. */
class AutomaticToolRouter(private val catalog: RunToolCatalog) {
    private var recoveries = 0

    fun recovery(objective: String, signal: String): String? {
        if (recoveries >= 2) return null
        recoveries++
        catalog.prepare(objective, signal)
        return "Runtime automatically retrieved tools (recovery $recoveries/2). " +
            "Read the current schemas and propose a valid call or clarify missing/ambiguous information. " +
            "No operation was executed by retrieval. Do not claim completion without evidence."
    }

    /** Real progress starts a fresh recovery episode instead of spending one budget per whole run. */
    fun resetRecoveries() {
        recoveries = 0
    }

    fun unavailableToolReply(content: String): Boolean = UNAVAILABLE.any { it in content.lowercase() }

    /** A whole-response operation proposal, never extraction from prose or observations. */
    fun adapt(response: CompletionResponse, exposedNames: Set<String>): CompletionResponse {
        if (response.toolCalls.isNotEmpty()) return response
        val raw = response.content.trim().let {
            if (it.startsWith("```json\n") && it.endsWith("```")) it.removePrefix("```json\n").removeSuffix("```").trim() else it
        }
        val attempted = Regex("^\\{\\s*\"omni_operation\"").containsMatchIn(raw)
        val root = try { Json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null }
        if (root == null && !attempted || root != null && "omni_operation" !in root) return response
        fun rejected(reason: String) = response.copy(content = "", toolCalls = listOf(ToolCall(
            "operation_${UUID.randomUUID()}", "discover_tools", emptyMap(), argumentError = reason, textProtocol = true
        )))
        val operation = root?.get("omni_operation") as? JsonObject
            ?: return rejected("Return one JSON object containing omni_operation with intent and arguments.")
        val intent = operation["intent"] as? JsonPrimitive
        if (root.keys != setOf("omni_operation") || operation.keys != setOf("intent", "arguments") ||
            intent?.isString != true || intent.content.isBlank() || intent.content.length > 800)
            return rejected("Operation needs only a concrete nonempty intent (up to 800 characters) and arguments object.")
        val decoded = ToolArgumentCodec.decode(operation["arguments"])
        decoded.error?.let { return rejected(it) }
        val match = catalog.matchOperation(intent.content)
        catalog.loadCandidates(match.candidates)
        val tool = match.tool ?: run {
            // Strict matching needs overlapping English terms, so an intent in another language finds nothing.
            // Offer the wider retrieval result (with exact signatures) instead of a dead-end, still without executing.
            val candidates = match.candidates.ifEmpty { catalog.looseCandidates(intent.content).also { catalog.loadCandidates(it) } }
            return rejected(buildString {
                append("Operation is ambiguous or unregistered. ")
                if (candidates.isEmpty()) append("No close tool was found; name an exact tool from: ${catalog.exactToolDigest(12)}.")
                else append("Candidates loaded for the next request: ${candidates.joinToString("; ") { catalog.signature(it) }}.")
                append(" Clarify the operation; no action was executed.")
            })
        }
        // A unique, permitted match carrying explicit arguments is executed even if its schema was not
        // loaded yet. Loading is only prompt economy; the strict argument contract still runs afterwards.
        if (tool.name !in exposedNames) catalog.ensureLoaded(tool.name)
        return response.copy(content = "", toolCalls = listOf(ToolCall(
            "operation_${UUID.randomUUID()}", tool.name, decoded.arguments, textProtocol = true
        )))
    }

    companion object {
        const val CONTRACT = "Runtime automatically retrieves relevant tools each turn; discover_tools is optional. " +
            "Instead of naming a tool you may output ONLY {\"omni_operation\":{\"intent\":\"concrete operation\",\"arguments\":{}}}. " +
            "Use exact parameter keys from current schemas. Ambiguous matches require clarification; retrieval never executes an action."
        private val UNAVAILABLE = listOf("no suitable tool", "cannot find a tool", "can't find a tool", "no tool available", "don't have a tool", "do not have a tool",
            "مش لاقي اداة", "مش لاقي أداة", "مفيش اداة", "مفيش أداة", "لا توجد أداة", "لا أملك أداة")
    }
}
