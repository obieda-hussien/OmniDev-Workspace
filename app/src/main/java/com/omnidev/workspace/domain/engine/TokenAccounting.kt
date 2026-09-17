package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.tools.ToolDefinition

/**
 * Provider-independent token accounting.
 *
 * Native usage is authoritative when present. Providers that omit usage receive a conservative
 * local estimate so budget enforcement, Team allocation and analytics do not silently treat a
 * completion as free.
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
            return Usage(
                inputTokens = native.promptTokens.coerceAtLeast(0),
                outputTokens = native.completionTokens.coerceAtLeast(0),
                totalTokens = native.totalTokens.coerceAtLeast(1),
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
