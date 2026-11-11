package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.tools.ToolExecutionResult

/**
 * Bounds thinking-only loops between real tool observations. This never asserts completion:
 * P0 still owns the acceptance decision. An announced action is not progress.
 */
internal class ModelActionProgressBudget(private val ceilingTokens: Int = 32_000) {
    init { require(ceilingTokens in 1_000..500_000) }
    private var spentSinceProgress = 0L

    fun onModelResponse(usedTokens: Int, toolCalls: List<ToolCall>, content: String, toolsAvailable: Boolean): Boolean {
        spentSinceProgress = (spentSinceProgress + usedTokens.coerceAtLeast(0).toLong()).coerceAtMost(2_000_000L)
        // Do not interrupt a tool proposal or a substantive answer; P0 will review them.
        return toolsAvailable && toolCalls.isEmpty() && spentSinceProgress >= ceilingTokens &&
            (content.isBlank() || UnfulfilledPromiseDetector.isUnfulfilled(content))
    }

    fun onToolResults(calls: List<ToolCall>, results: List<ToolExecutionResult>) {
        if (calls.zip(results).any { (call, result) ->
                !result.isError && call.name != RunToolCatalog.DISCOVER.name
            }) spentSinceProgress = 0
    }
}
