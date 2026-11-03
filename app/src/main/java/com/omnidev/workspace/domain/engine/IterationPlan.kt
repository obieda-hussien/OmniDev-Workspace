package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.model.AIModel

/**
 * Per-iteration prompt and input-budget arithmetic, moved out of AgentPipeline's flow lambda, which is
 * at the JVM method-size limit. One object replaces three locals that used to live across suspension points.
 */
class IterationPlan(
    val systemPrompt: String,
    val exposed: List<ToolDefinition>,
    val inputBudget: Int
) {
    companion object {
        fun compute(
            baseSystemPrompt: String,
            runtimeBackends: RuntimeBackendLedger,
            toolDefs: List<ToolDefinition>,
            exposed: List<ToolDefinition>,
            nativeTools: Boolean,
            model: AIModel,
            desiredOutputBudget: Int,
            contextWindowBuffer: Int,
            inputReserveTokens: Int
        ): IterationPlan {
            val prompt = baseSystemPrompt + "\n" + runtimeBackends.guidance() +
                (if (runtimeBackends.reportOnly) "\nTool execution has ended for this run. Answer now from existing evidence; disclose incomplete work and required recovery. No tool calls." else "") +
                (if (toolDefs.isEmpty()) "" else "\n" + AutomaticToolRouter.CONTRACT) +
                (if (nativeTools || toolDefs.isEmpty()) "" else "\n" + ToolTextProtocol.schemas(exposed))
            // Tool definitions are already compacted to the exact set sent to the provider.
            val schemaEstimate = if (nativeTools) (exposed.sumOf { it.toString().length } / 3).coerceAtLeast(0) else 0
            val budget = model.contextWindow - desiredOutputBudget - contextWindowBuffer -
                prompt.length / 3 - schemaEstimate - inputReserveTokens
            return IterationPlan(prompt, exposed, budget)
        }
    }
}
