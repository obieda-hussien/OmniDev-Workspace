package com.omnidev.workspace.domain.engine.completion

import com.omnidev.workspace.data.model.ToolCall

/**
 * Gives every tool call a stable, run-unique id *before* it executes. Provider ids are kept when they
 * are usable; blank or repeated ids (some OpenAI-compatible Gemini responses) get a runtime id. The
 * same normalized call is stored in history and used for its result, so provider pairing stays valid.
 */
class ToolCallIdentity(runId: String, historicalIds: List<String> = emptyList()) {
    private val prefix = "call_" + runId.filter { it.isLetterOrDigit() }.takeLast(8).ifBlank { "run" }
    private val used = HashSet<String>(historicalIds)
    private var counter = 0

    @Synchronized
    fun stabilize(calls: List<ToolCall>): List<ToolCall> = calls.map { call ->
        val id = call.id.trim()
        val unusable = id.isEmpty() || id in used || !id.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == ':' || it == '.' } || id.length > 128
        val stable = if (unusable) nextId() else id
        used += stable
        if (stable == call.id) call else call.copy(id = stable)
    }

    private fun nextId(): String {
        while (true) {
            counter += 1
            val candidate = "${prefix}_$counter"
            if (candidate !in used) return candidate
        }
    }
}
