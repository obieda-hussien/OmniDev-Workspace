package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.model.ModelTier
import com.omnidev.workspace.data.model.ToolCallResult
import com.omnidev.workspace.data.tools.ToolManager
import com.omnidev.workspace.registry.ModelRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlin.math.min

/**
 * Configures the behavior of an [AgentPipeline] run.
 *
 * @property maxIterations Maximum ReAct loop iterations before forced termination.
 * @property enableRetry Whether to retry failed API calls with exponential backoff.
 * @property maxRetries Maximum number of API retry attempts per iteration.
 * @property baseRetryDelayMs Initial delay before the first retry (doubles each attempt).
 * @property tokenBudget Maximum total tokens (input + output) across all iterations.
 *           Set to null for unlimited.
 * @property contextWindowBuffer Tokens to reserve as safety margin for system prompts.
 * @property enableMemoryTrimming Whether to trim old messages when context window fills up.
 */
data class AgentConfig(
    val maxIterations: Int = 25,
    val enableRetry: Boolean = true,
    val maxRetries: Int = 3,
    val baseRetryDelayMs: Long = 500L,
    val tokenBudget: Int? = null,
    val contextWindowBuffer: Int = 4_096,
    val enableMemoryTrimming: Boolean = true
) {
    companion object {
        /** Preset for cost-sensitive runs: fewer iterations, lower token budget. */
        val BUDGET = AgentConfig(
            maxIterations = 10,
            tokenBudget = 50_000,
            enableRetry = false
        )

        /** Preset for deep, thorough agentic runs with maximum capability. */
        val THOROUGH = AgentConfig(
            maxIterations = 50,
            maxRetries = 5,
            baseRetryDelayMs = 1_000L,
            contextWindowBuffer = 8_192
        )

        /** Preset for ultra-fast inline completions — single shot only. */
        val INLINE = AgentConfig(
            maxIterations = 1,
            enableRetry = false,
            tokenBudget = 8_192
        )
    }
}

/**
 * Core ReAct (Reason + Act) agent pipeline that drives autonomous tool-use loops.
 *
 * ### Architecture
 * The pipeline operates as follows:
 * 1. **Reason**: Send the conversation context + tool schemas to the model.
 * 2. **Act**: If the model returns tool calls, execute them via [ToolManager].
 * 3. **Observe**: Feed tool results back into the conversation and loop.
 * 4. **Terminate**: When the model responds with plain text (no tool calls), emit the final answer.
 *
 * ### Reliability Features
 * - Exponential backoff retry on API failures (configurable via [AgentConfig]).
 * - Token budget enforcement to prevent runaway cost accumulation.
 * - Context window memory trimming to avoid hitting provider limits.
 * - Tier-aware system prompts that adapt to the selected model's capability tier.
 *
 * @param toolManager The [ToolManager] that provides tool definitions and execution.
 * @param completionProvider A suspend function that calls the AI completion API.
 * @param config Behavioral configuration (iteration limits, retry policy, token budget).
 */
class AgentPipeline(
    private val toolManager: ToolManager,
    private val completionProvider: suspend (CompletionRequest) -> CompletionResponse,
    private val config: AgentConfig = AgentConfig()
) {

    companion object {
        /** System prompt for ORCHESTRATOR-tier models — complex planning and deep analysis. */
        private const val ORCHESTRATOR_SYSTEM_PROMPT = """
You are an elite autonomous coding agent powered by a frontier reasoning model.

Your strengths: architectural analysis, complex multi-file refactoring, long-horizon planning.
Use your full reasoning capacity. Think deeply before each action.

OPERATIONAL RULES:
1. Operate ONLY within the user's active Target Context scope — never access files outside it.
2. Use read_file_lines with precise line ranges — reading entire large files wastes context.
3. Use search_codebase FIRST to understand the codebase structure before editing.
4. Use patch_file_content for all edits — never rewrite complete files.
5. Verify every change by reading back the modified lines.
6. When uncertain, prefer smaller, reversible changes and report your reasoning.
7. Break complex tasks into explicit steps and validate each step before proceeding.
"""

        /** System prompt for EXECUTOR-tier models — fast, practical code generation. */
        private const val EXECUTOR_SYSTEM_PROMPT = """
You are an autonomous coding agent optimized for fast, precise code execution.

Your strengths: implementing features, refactoring, bug fixes, code generation.
Be concise in your reasoning. Act decisively with minimal back-and-forth.

OPERATIONAL RULES:
1. Operate ONLY within the user's active Target Context scope.
2. Use read_file_lines for targeted reads — specify exact line ranges.
3. Use search_codebase to find relevant code before editing.
4. Use patch_file_content for surgical edits — no full file rewrites.
5. Verify changes by reading back affected lines.
6. Complete tasks in as few tool calls as reasonably possible.
"""

        /** System prompt for FAST-tier models — minimal overhead for quick queries. */
        private const val FAST_SYSTEM_PROMPT = """
You are a fast-response coding assistant. Be brief and direct.
All file operations must stay within the user's Target Context scope.
Use read_file_lines for targeted reads. Use patch_file_content for edits.
"""

        /** Extended thinking injection appended when Deep Mode is active. */
        private const val DEEP_THINKING_SUFFIX = """

DEEP THINKING MODE ACTIVE:
Before each action, emit your internal reasoning inside <thinking>...</thinking> tags.
Analyze tradeoffs, consider edge cases, and plan your exact tool call sequence.
After each observation, reflect: "Did this achieve the intended result? What's next?"
"""
    }

    /**
     * Executes the full ReAct loop for a given user prompt.
     *
     * @param userMessage The user's original request.
     * @param conversationHistory Prior messages in the conversation (for context).
     * @param modelId The AI model ID to use (from the Agent role assignment).
     * @param scopePath The active Target Context directory path.
     * @param enableDeepThinking Whether to inject extended thinking prompts.
     * @return A [Flow] of [AgentEvent]s representing the agent's progress.
     */
    fun execute(
        userMessage: String,
        conversationHistory: List<ChatMessage> = emptyList(),
        modelId: String,
        scopePath: String,
        enableDeepThinking: Boolean = false
    ): Flow<AgentEvent> = flow {
        emit(AgentEvent.Started)

        val model = ModelRegistry.findModelById(modelId)
            ?: run {
                emit(AgentEvent.Error("Unknown model: $modelId"))
                return@flow
            }

        // Select tier-appropriate system prompt
        val baseSystemPrompt = when (model.tier) {
            ModelTier.ORCHESTRATOR -> ORCHESTRATOR_SYSTEM_PROMPT
            ModelTier.EXECUTOR -> EXECUTOR_SYSTEM_PROMPT
            ModelTier.FAST -> FAST_SYSTEM_PROMPT
        }

        // Build the complete system prompt with tool definitions
        val toolDefs = toolManager.getToolDefinitions()
        val toolSchemaText = toolDefs.joinToString("\n\n") { tool ->
            buildString {
                appendLine("### Tool: ${tool.name}")
                appendLine(tool.description)
                appendLine("Parameters:")
                tool.parameters.forEach { param ->
                    val reqTag = if (param.required) " (required)" else " (optional)"
                    appendLine("  - ${param.name}: ${param.type}$reqTag — ${param.description}")
                }
            }
        }

        val systemPrompt = buildString {
            append(baseSystemPrompt.trimIndent())
            appendLine()
            appendLine()
            appendLine("## Available Tools")
            appendLine(toolSchemaText)
            if (enableDeepThinking && model.supportsThinking) {
                append(DEEP_THINKING_SUFFIX.trimIndent())
            }
        }

        // Initialize the conversation
        val messages = mutableListOf<ChatMessage>().apply {
            addAll(conversationHistory)
            add(ChatMessage(role = MessageRole.USER, content = userMessage))
        }

        var iteration = 0
        var totalTokensUsed = 0

        // ── ReAct Loop ──
        while (iteration < config.maxIterations) {
            iteration++
            emit(AgentEvent.Thinking(iteration = iteration))

            // Token budget enforcement
            if (config.tokenBudget != null && totalTokensUsed >= config.tokenBudget) {
                emit(AgentEvent.Error(
                    "Token budget of ${config.tokenBudget} tokens exhausted after $iteration iterations."
                ))
                return@flow
            }

            // Context window trimming — drop oldest non-system messages when approaching limit
            val trimmedMessages = if (config.enableMemoryTrimming) {
                trimMessagesForContextWindow(messages, model.contextWindow - config.contextWindowBuffer)
            } else {
                messages.toList()
            }

            val request = CompletionRequest(
                modelId = modelId,
                messages = trimmedMessages,
                systemPrompt = systemPrompt,
                maxTokens = model.maxOutputTokens,
                enableThinking = enableDeepThinking && model.supportsThinking,
                targetContext = scopePath
            )

            // ── API call with retry/backoff ──
            val response = callWithRetry(request, iteration) { errorMsg ->
                emit(AgentEvent.Error(errorMsg))
            } ?: return@flow

            // Track token usage
            response.tokensUsed?.let { usage ->
                totalTokensUsed += usage.totalTokens
                emit(AgentEvent.TokenUsageUpdate(
                    iterationTokens = usage.totalTokens,
                    totalTokens = totalTokensUsed,
                    budget = config.tokenBudget
                ))
            }

            // ── Emit thinking content if present ──
            response.thinkingContent?.let { thinking ->
                emit(AgentEvent.ThinkingBlock(thinking))
            }

            // ── No tool calls → Final answer ──
            if (response.toolCalls.isEmpty()) {
                val assistantMessage = ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = response.content,
                    thinkingContent = response.thinkingContent
                )
                messages.add(assistantMessage)

                emit(AgentEvent.FinalAnswer(
                    content = response.content,
                    totalIterations = iteration,
                    totalTokensUsed = totalTokensUsed,
                    conversationHistory = messages.toList()
                ))
                return@flow
            }

            // ── Tool calls present → Execute and observe ──
            val assistantMessage = ChatMessage(
                role = MessageRole.ASSISTANT,
                content = response.content,
                toolCalls = response.toolCalls,
                thinkingContent = response.thinkingContent
            )
            messages.add(assistantMessage)

            val toolResults = mutableListOf<ToolCallResult>()

            for (toolCall in response.toolCalls) {
                emit(AgentEvent.ToolExecution(
                    toolName = toolCall.name,
                    arguments = toolCall.arguments,
                    iteration = iteration
                ))

                val result = toolManager.executeTool(
                    name = toolCall.name,
                    arguments = toolCall.arguments,
                    scopePath = scopePath
                )

                val toolCallResult = ToolCallResult(
                    toolCallId = toolCall.id,
                    toolName = toolCall.name,
                    output = result.output,
                    isError = result.isError
                )
                toolResults.add(toolCallResult)

                emit(AgentEvent.ToolResult(
                    toolName = toolCall.name,
                    output = result.output,
                    isError = result.isError,
                    iteration = iteration
                ))
            }

            // Add tool results as a TOOL message for the next iteration
            val toolMessage = ChatMessage(
                role = MessageRole.TOOL,
                content = toolResults.joinToString("\n\n") { r ->
                    "[${r.toolName}] ${if (r.isError) "ERROR: " else ""}${r.output}"
                },
                toolResults = toolResults
            )
            messages.add(toolMessage)
        }

        // ── Max iterations reached ──
        emit(AgentEvent.Error(
            "Agent reached maximum iterations (${config.maxIterations}) without completing. " +
                "Consider using a Swarm run for complex tasks, or increase maxIterations in AgentConfig."
        ))
    }

    /**
     * Wraps an API call with exponential backoff retry logic.
     *
     * @param request The completion request.
     * @param iteration The current loop iteration number (for error messages).
     * @param onFatalError Called with the error message if all retries are exhausted.
     * @return The [CompletionResponse] on success, or null if all retries failed.
     */
    private suspend fun callWithRetry(
        request: CompletionRequest,
        iteration: Int,
        onFatalError: suspend (String) -> Unit
    ): CompletionResponse? {
        val maxAttempts = if (config.enableRetry) config.maxRetries + 1 else 1

        repeat(maxAttempts) { attempt ->
            try {
                return completionProvider(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val isLastAttempt = attempt == maxAttempts - 1
                if (isLastAttempt) {
                    onFatalError("API call failed after $maxAttempts attempts (iteration $iteration): ${e.message}")
                    return null
                }
                // Exponential backoff: 500ms, 1s, 2s, 4s, ... (bit-shift for integer powers of 2)
                val delayMs = config.baseRetryDelayMs * (1L shl attempt)
                delay(min(delayMs, 30_000L))
            }
        }
        return null
    }

    /**
     * Trims the conversation message list to fit within [maxTokens] by removing
     * the oldest non-system messages first. Always preserves the first (user) message
     * and the last [RECENT_MESSAGES_TO_PRESERVE] messages to maintain continuity.
     *
     * Note: This is a heuristic approach using character counts as a proxy for token counts.
     * A production implementation would use the provider's tokenizer for exact counts.
     */
    private fun trimMessagesForContextWindow(
        messages: List<ChatMessage>,
        maxTokens: Int
    ): List<ChatMessage> {
        // Rough estimate: 4 characters ≈ 1 token
        val maxChars = maxTokens * 4
        val totalChars = messages.sumOf { it.content.length }

        if (totalChars <= maxChars) return messages

        // Keep first message (original task) and recent messages
        val result = messages.toMutableList()
        val keepFirst = result.removeFirst()

        // Remove oldest messages (index 0 after removeFirst) until we're within budget
        while (result.sumOf { it.content.length } + keepFirst.content.length > maxChars && result.size > 2) {
            result.removeFirst()
        }

        result.add(0, keepFirst)
        return result
    }
}

/**
 * Events emitted by the [AgentPipeline] during ReAct loop execution.
 * These drive the UI's real-time streaming display.
 */
sealed class AgentEvent {
    /** The agent loop has started. */
    data object Started : AgentEvent()

    /** The agent is reasoning (sending to model). */
    data class Thinking(val iteration: Int) : AgentEvent()

    /** Extended thinking content from the model. */
    data class ThinkingBlock(val content: String) : AgentEvent()

    /** A tool is being executed. */
    data class ToolExecution(
        val toolName: String,
        val arguments: Map<String, String>,
        val iteration: Int
    ) : AgentEvent()

    /** The result of a tool execution. */
    data class ToolResult(
        val toolName: String,
        val output: String,
        val isError: Boolean,
        val iteration: Int
    ) : AgentEvent()

    /** Token usage stats after an API call. */
    data class TokenUsageUpdate(
        val iterationTokens: Int,
        val totalTokens: Int,
        val budget: Int?
    ) : AgentEvent()

    /** The agent has produced a final answer. */
    data class FinalAnswer(
        val content: String,
        val totalIterations: Int,
        val totalTokensUsed: Int,
        val conversationHistory: List<ChatMessage>
    ) : AgentEvent()

    /** An unrecoverable error occurred. */
    data class Error(val message: String) : AgentEvent()
}

/**
 * Represents a sub-task in Swarm mode, assigned by the Orchestrator to a Worker.
 */
@Serializable
data class SwarmTask(
    val id: String,
    val description: String,
    val priority: Int = 0,
    val dependencies: List<String> = emptyList(),
    val status: SwarmTaskStatus = SwarmTaskStatus.PENDING
)

@Serializable
enum class SwarmTaskStatus {
    PENDING, IN_PROGRESS, COMPLETED, FAILED
}

