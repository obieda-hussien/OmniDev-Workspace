package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.model.ModelRole
import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.model.ToolCallResult
import com.omnidev.workspace.data.tools.ToolManager
import com.omnidev.workspace.registry.ModelRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable

/**
 * Core ReAct (Reason + Act) agent pipeline that drives autonomous tool-use loops.
 *
 * The pipeline operates as follows:
 * 1. **Reason**: Send the conversation context + tool schemas to the model.
 * 2. **Act**: If the model returns tool calls, execute them via [ToolManager].
 * 3. **Observe**: Feed tool results back into the conversation and loop.
 * 4. **Terminate**: When the model responds with plain text (no tool calls), emit the final answer.
 *
 * The loop enforces a maximum iteration count to prevent infinite loops and emits
 * [AgentEvent]s as a [Flow] for real-time UI streaming.
 *
 * @param toolManager The [ToolManager] that provides tool definitions and execution.
 * @param completionProvider A suspend function that calls the AI completion API.
 *                           Abstracted to allow swapping between providers.
 */
class AgentPipeline(
    private val toolManager: ToolManager,
    private val completionProvider: suspend (CompletionRequest) -> CompletionResponse
) {

    companion object {
        /** Maximum ReAct iterations before the agent is forcibly stopped. */
        const val MAX_ITERATIONS = 25

        /** System prompt template injected when Deep Thinking mode is enabled. */
        private const val DEEP_THINKING_PROMPT = """
You are an expert autonomous coding agent. Before executing any action, think step-by-step 
inside <thinking> tags. Reason carefully about the user's request, break it into sub-problems, 
and plan your tool calls before acting. After each tool observation, reflect on the result 
and decide the next action or whether the goal is complete.
"""

        /** Base system prompt for the agent with tool-use instructions. */
        private const val AGENT_SYSTEM_PROMPT = """
You are an autonomous coding agent with access to file-system tools. You can read files, 
search codebases, patch files, create files, and delete files within the user's scoped project.

IMPORTANT RULES:
1. Only operate on files within the user's active Target Context scope.
2. Use read_file_lines to read specific line ranges — NEVER request entire large files.
3. Use search_codebase to locate code before making edits.
4. Use patch_file_content for surgical edits — do NOT rewrite entire files.
5. Validate your changes by reading the modified lines after patching.
6. If a task requires multiple steps, execute them one at a time and verify each step.
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

        // Build the system prompt with tool definitions
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
            append(AGENT_SYSTEM_PROMPT.trimIndent())
            appendLine()
            appendLine()
            appendLine("## Available Tools")
            appendLine(toolSchemaText)
            if (enableDeepThinking && model.supportsThinking) {
                appendLine()
                append(DEEP_THINKING_PROMPT.trimIndent())
            }
        }

        // Initialize the conversation with the user's message
        val messages = mutableListOf<ChatMessage>().apply {
            addAll(conversationHistory)
            add(ChatMessage(role = MessageRole.USER, content = userMessage))
        }

        var iteration = 0

        // ── ReAct Loop ──
        while (iteration < MAX_ITERATIONS) {
            iteration++
            emit(AgentEvent.Thinking(iteration = iteration))

            val request = CompletionRequest(
                modelId = modelId,
                messages = messages.toList(),
                systemPrompt = systemPrompt,
                maxTokens = model.maxOutputTokens,
                enableThinking = enableDeepThinking && model.supportsThinking,
                targetContext = scopePath
            )

            val response: CompletionResponse
            try {
                response = completionProvider(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(AgentEvent.Error("API call failed (iteration $iteration): ${e.message}"))
                return@flow
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
            "Agent reached maximum iterations ($MAX_ITERATIONS) without completing. " +
                "The task may be too complex for a single agent run."
        ))
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

    /** The agent has produced a final answer. */
    data class FinalAnswer(
        val content: String,
        val totalIterations: Int,
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
