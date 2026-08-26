package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.CompletionResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ContextCompressor {

    /**
     * Checks token usage relative to budget and message counts.
     * If limits are exceeded, compacts older messages into a summary block by calling the LLM.
     * Emits a Compaction event.
     */
    suspend fun checkAndCompact(
        messages: MutableList<ChatMessage>,
        tokenBudget: Int,
        modelId: String,
        completionProvider: suspend (CompletionRequest) -> CompletionResponse,
        emitCompaction: suspend (AgentEvent.ContextCompaction) -> Unit
    ) = withContext(Dispatchers.Default) {
        val maxChars = (tokenBudget * 0.75).toInt() * 4
        val currentChars = messages.sumOf { it.content.length }

        val needsCompaction = currentChars > maxChars || messages.size > 30

        if (needsCompaction && messages.size > 10) {
            val firstMsg = messages.first()
            val recentWindow = messages.takeLast(10)
            val toCompact = messages.drop(1).dropLast(10)

            if (toCompact.isNotEmpty()) {
                // Prepare the payload to summarize
                val payloadToSummarize = buildString {
                    toCompact.forEach { msg ->
                        appendLine("[${msg.role.name}]: ${msg.content}")
                        appendLine("---")
                    }
                }

                val summarizationPrompt = """
                    You are an expert context compressor. Summarize the following conversation history into a structured episodic summary.
                    You MUST retain the core facts, key decisions, tool results, and the user's ultimate goals.

                    Format your response EXACTLY like this:
                    [COMPACTED CONTEXT SUMMARY]
                    Key Decisions & User Goals: <your summary here>
                    Important Tool Results: <your summary here>
                    Environment State: <your summary here>

                    Conversation to summarize:
                    $payloadToSummarize
                """.trimIndent()

                var summaryResponse: String? = null
                try {
                    val request = CompletionRequest(
                        modelId = modelId,
                        messages = listOf(
                            ChatMessage(role = MessageRole.USER, content = summarizationPrompt)
                        ),
                        maxTokens = 1500,
                        temperature = 0.3
                    )
                    val response = completionProvider(request)
                    summaryResponse = response.content
                } catch (e: Exception) {
                    // Fallback to raw truncation if LLM call fails
                }

                val finalSummary = if (!summaryResponse.isNullOrBlank()) {
                    summaryResponse
                } else {
                    // DEGRADED FALLBACK
                    buildString {
                        appendLine("[COMPACTED CONTEXT SUMMARY] (DEGRADED FALLBACK)")
                        appendLine("Key Decisions & User Goals: Extracted from ${toCompact.size} previous turns.")
                        appendLine("Important Tool Results: Preserved intent.")
                        appendLine("Environment State: Retained.")
                        appendLine("Summary of removed messages (Truncated):")
                        toCompact.forEach { msg ->
                            appendLine("- ${msg.role.name}: ${msg.content.take(150).replace("\n", " ")}")
                        }
                    }
                }

                messages.clear()
                messages.add(firstMsg)
                messages.add(ChatMessage(
                    role = MessageRole.SYSTEM,
                    content = finalSummary
                ))
                messages.addAll(recentWindow)

                emitCompaction(AgentEvent.ContextCompaction(finalSummary))
            }
        }
    }
}
