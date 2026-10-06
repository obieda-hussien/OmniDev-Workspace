package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.tools.GitHubRequestContract
import com.omnidev.workspace.data.tools.ToolExecutionResult

/** A successful repo listing or unrelated tool cannot erase a failed GitHub resource read. */
class GitHubFailureLedger {
    private val failures = linkedMapOf<String, String>()
    private val attempts = mutableMapOf<String, Int>()
    fun observe(call: ToolCall, result: ToolExecutionResult) {
        if (call.name != "github_manager" || result.classification in setOf("TOOL_NOT_EXPOSED", "INVALID_TOOL_BATCH", "BATCH_PREFLIGHT_BLOCKED", "INVALID_TOOL_ARGUMENTS")) return
        val key = GitHubRequestContract.failureKey(call.arguments["action"].orEmpty(), call.arguments) ?: return
        if (result.isError) {
            failures[key] = result.output.take(1200)
            attempts[key] = (attempts[key] ?: 0) + 1
        } else {
            failures.remove(key)
            attempts.remove(key)
        }
    }
    fun unresolved(): String? = failures.takeIf { it.isNotEmpty() }?.entries?.joinToString("\n") { "${it.key}: ${it.value}" }
    fun repeatedFailure(): Boolean = attempts.values.any { it >= 2 }
}
