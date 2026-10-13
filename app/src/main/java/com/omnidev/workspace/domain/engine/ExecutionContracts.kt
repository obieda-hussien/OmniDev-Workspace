package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ChatMessage

data class AgentConfig(
    val maxIterations: Int = 50,
    val enableRetry: Boolean = true,
    val maxRetries: Int = 3,
    val baseRetryDelayMs: Long = 500L,
    val tokenBudget: Int? = null,
    val contextWindowBuffer: Int = 4_096,
    val enableMemoryTrimming: Boolean = true,
    val maxExecutionTimeMs: Long? = null,
    val maxIterationTimeMs: Long? = 3 * 60 * 1_000L,
    val maxRepeatedToolCalls: Int = 15,
    val enableParallelToolExecution: Boolean = true,
    val enableSelfReflection: Boolean = false,
    val recentMessagesWindow: Int = 18,
    val sessionDigestUpdateEveryNMessages: Int = 6,
    val sessionDigestMaxChars: Int = 1_800,
    val sessionDigestMaxMessages: Int = 40,
    val toolExecutionTimeoutMs: Long = 30_000L,
    val toolExecutionMaxRetries: Int = 1,
    val toolExecutionBaseRetryDelayMs: Long = 500L
) {
    companion object {
        val BUDGET = AgentConfig(maxIterations = 10, tokenBudget = 50_000, enableRetry = false)
        val THOROUGH = AgentConfig(
            maxIterations = 100,
            maxRetries = 5,
            baseRetryDelayMs = 1_000L,
            contextWindowBuffer = 8_192,
            enableSelfReflection = true
        )
        val INLINE = AgentConfig(maxIterations = 1, enableRetry = false, tokenBudget = 8_192)
    }
}

enum class AgentExecutionPhase { ANALYZE, IMPLEMENT, VERIFY, REPORT }

sealed class AgentEvent {
    data object Started : AgentEvent()
    data class SteeringApplied(val revision: Long) : AgentEvent()
    data class Thinking(val iteration: Int) : AgentEvent()
    data class ThinkingBlock(val content: String) : AgentEvent()
    data class ToolExecution(
        val toolName: String,
        val arguments: Map<String, String>,
        val iteration: Int
    ) : AgentEvent()
    data class ToolResult(
        val toolName: String,
        val output: String,
        val isError: Boolean,
        val iteration: Int
    ) : AgentEvent()
    data class TokenUsageUpdate(
        val iterationTokens: Int,
        val totalTokens: Int,
        val budget: Int?
    ) : AgentEvent()
    data class PhaseChanged(val phase: AgentExecutionPhase, val detail: String? = null) : AgentEvent()
    data class StreamChunk(val delta: String) : AgentEvent()
    data class FinalAnswer(
        val content: String,
        val totalIterations: Int,
        val totalTokensUsed: Int,
        val conversationHistory: List<ChatMessage>
    ) : AgentEvent()
    data class Reflecting(val draftLength: Int) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    data class ContextCompaction(val summary: String) : AgentEvent()
}
