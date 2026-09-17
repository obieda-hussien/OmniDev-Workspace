package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.tools.ToolDefinition

/**
 * Provider-independent token accounting.
 *
 * Native total usage is authoritative when present. Missing prompt/completion components are
 * reconciled against a local estimate without changing the provider's reported total. Providers
 * that omit usage entirely receive a conservative local estimate so budgets and analytics never
 * silently treat a completion as free.
 */
object TokenAccounting {

    data class Usage(
        val inputTokens: Int,
        val outputTokens: Int,
        val totalTokens: Int,
        val estimated: Boolean
    )

    fun usage(request: CompletionRequest, response: CompletionResponse): Usage {
        val native = response.tokensUsed
        if (native != null && native.totalTokens > 0) {
            val total = native.totalTokens.coerceAtLeast(1)
            val reportedInput = native.promptTokens.coerceAtLeast(0)
            val reportedOutput = native.completionTokens.coerceAtLeast(0)
            val reconciled = reconcileComponents(
                total = total,
                reportedInput = reportedInput,
                reportedOutput = reportedOutput,
                estimatedInput = estimateInputTokens(request),
                estimatedOutput = estimateResponseTokens(response)
            )
            return Usage(
                inputTokens = reconciled.first,
                outputTokens = reconciled.second,
                totalTokens = total,
                // Total is provider-native; only component allocation may be inferred.
                estimated = false
            )
        }

        val input = estimateInputTokens(request)
        val output = estimateResponseTokens(response)
        return Usage(
            inputTokens = input,
            outputTokens = output,
            totalTokens = (input + output).coerceAtLeast(1),
            estimated = true
        )
    }

    fun estimateInputTokens(request: CompletionRequest): Int {
        val chars = (request.systemPrompt?.length ?: 0) +
            request.messages.sumOf(::messageChars) +
            request.tools.orEmpty().sumOf(::toolChars)
        // Code, JSON schemas and multilingual text often tokenize denser than plain English.
        return ((chars + 2) / 3).coerceAtLeast(1)
    }

    fun estimateResponseTokens(response: CompletionResponse): Int {
        val chars = response.content.length +
            response.toolCalls.sumOf { call ->
                call.name.length + call.arguments.entries.sumOf { (k, v) -> k.length + v.length + 4 }
            }
        return ((chars + 2) / 3).coerceAtLeast(1)
    }

    private fun reconcileComponents(
        total: Int,
        reportedInput: Int,
        reportedOutput: Int,
        estimatedInput: Int,
        estimatedOutput: Int
    ): Pair<Int, Int> {
        if (reportedInput > 0 && reportedOutput > 0) {
            // Some providers round components independently. Preserve their ratio but force
            // components to sum to the authoritative total used for budgets/costs.
            val reportedSum = reportedInput.toLong() + reportedOutput.toLong()
            if (reportedSum == total.toLong()) return reportedInput to reportedOutput
            val input = ((total.toLong() * reportedInput) / reportedSum)
                .toInt().coerceIn(0, total)
            return input to (total - input)
        }
        if (reportedInput > 0) {
            val input = reportedInput.coerceAtMost(total)
            return input to (total - input)
        }
        if (reportedOutput > 0) {
            val output = reportedOutput.coerceAtMost(total)
            return (total - output) to output
        }

        val estimatedSum = (estimatedInput.toLong() + estimatedOutput.toLong()).coerceAtLeast(1L)
        val input = ((total.toLong() * estimatedInput.toLong()) / estimatedSum)
            .toInt().coerceIn(0, total)
        return input to (total - input)
    }

    private fun messageChars(message: ChatMessage): Int =
        message.content.length +
            message.toolCalls.sumOf { it.name.length + it.arguments.toString().length } +
            message.toolResults.sumOf { it.toolName.length + it.output.length } +
            message.attachments.sumOf { attachment ->
                // Base64 images are provider payload, but counting all base64 chars here would be
                // wildly pessimistic compared with multimodal tokenization. Use a capped proxy.
                minOf(attachment.base64Data?.length ?: 0, 12_000)
            }

    private fun toolChars(tool: ToolDefinition): Int =
        tool.name.length + tool.description.length + tool.parameters.sumOf { parameter ->
            parameter.name.length + parameter.type.length + parameter.description.length + 16
        }
}
