package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.model.AIModel
import com.omnidev.workspace.data.network.nativeToolSchemaChars

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
            exposed: List<ToolDefinition>,
            nativeTools: Boolean,
            model: AIModel,
            desiredOutputBudget: Int,
            contextWindowBuffer: Int,
            inputReserveTokens: Int
        ): IterationPlan {
            val clock = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z (z)", java.util.Locale.US).format(java.util.Date())
            val prompt = baseSystemPrompt + "\nDevice local date/time: $clock" + "\n" + runtimeBackends.guidance() +
                (if (runtimeBackends.reportOnly) "\nTool execution has ended for this run. Answer now from existing evidence; disclose incomplete work and required recovery. No tool calls." else "") +
                (if (exposed.isEmpty() || runtimeBackends.reportOnly) "" else if (nativeTools) "\nCall tools through the native function interface using the advertised schemas." else "\n" + TextToolCallAdapter.CONTRACT) +
                (if (nativeTools || exposed.isEmpty() || runtimeBackends.reportOnly) "" else "\n" + ToolTextProtocol.schemas(exposed))
            // Tool definitions are already compacted to the exact set sent to the provider.
            val schemaEstimate = if (nativeTools) {
                ((nativeToolSchemaChars(exposed, model.provider).toLong() + 2) / 3).toInt()
            } else 0
            val budget = model.contextWindow - desiredOutputBudget - contextWindowBuffer -
                prompt.length / 3 - schemaEstimate - inputReserveTokens
            return IterationPlan(prompt, exposed, budget)
        }
    }
}
