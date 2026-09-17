package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.model.ExecutionModeRequest
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.model.ToolCallResult
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolExecutionResult
import com.omnidev.workspace.data.tools.ToolManager
import com.omnidev.workspace.data.tools.ToolParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Small native-tool loop for Chat.
 *
 * Chat can research with a tiny read-only web set. Before spending a model call, a deterministic
 * local preflight detects obvious execution-capability gaps and proposes Agent/Team directly.
 * Completed Chat runs feed local mode-outcome learning so AUTO routing can compare real outcomes
 * instead of assuming execution modes are always superior.
 */
class ChatToolLoop(private val tools: ToolManager?) {
    companion object {
        val WEB_TOOLS = setOf(
            "web_search", "web_search_deep", "web_scraper", "fetch_page", "scrape_multiple"
        )
        const val REQUEST_MODE = "request_execution_mode"
        val MODE_TOOL = ToolDefinition(
            REQUEST_MODE,
            "Ask the user to enable execution. Never needed for web research. Use AGENT for sequential execution; SWARM only for independent parallel tasks. This only proposes a mode; it does not execute anything.",
            listOf(
                ToolParameter("mode", "string", "AGENT or SWARM"),
                ToolParameter(
                    "reason",
                    "string",
                    "Explain the missing capability and ask permission, in the user's language."
                )
            )
        )
    }

    data class Result(val content: String, val request: ExecutionModeRequest? = null)

    suspend fun run(
        base: CompletionRequest,
        disabled: Set<String>,
        originMessageId: String,
        complete: suspend (CompletionRequest) -> CompletionResponse,
        event: suspend (AgentEvent) -> Unit
    ): Result {
        val userRequest = base.messages.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()
        val startedAt = System.currentTimeMillis()
        var roundsObserved = 0
        var calls = 0
        var tokens = 0
        var hadToolError = false

        fun learnedResult(
            content: String,
            outcome: ModeOutcomeLearner.Outcome,
            request: ExecutionModeRequest? = null,
            verified: Boolean = false
        ): Result {
            try {
                ModeOutcomeLearner.recordOutcome(
                    userRequest = userRequest,
                    mode = OmniMode.CHAT,
                    outcome = outcome,
                    iterations = roundsObserved,
                    durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
                    tokens = tokens.takeIf { it > 0 },
                    verified = verified
                )
            } catch (_: Throwable) {
                // Learning is advisory and must never affect the primary response path.
            }
            return Result(content, request)
        }

        // Local preflight: deterministic, zero network/token cost. Only high-confidence capability
        // gaps are short-circuited; ordinary coding questions remain answerable in Chat.
        val localSuggestion = AdaptiveModeRouter.fromChatRequest(userRequest)
        if (localSuggestion != null && localSuggestion.confidence >= 0.72f) {
            val request = ExecutionModeRequest(
                mode = localSuggestion.to.name,
                reason = localSuggestion.reason,
                originMessageId = originMessageId,
                sourceMode = OmniMode.CHAT.name,
                confidence = localSuggestion.confidence,
                trigger = localSuggestion.trigger.name
            )
            // Correct capability-gap detection is not a failed Chat attempt. Do not contaminate
            // the outcome posterior by recording a synthetic failure here.
            return Result(localSuggestion.reason, request)
        }

        val definitions = tools?.getToolDefinitions().orEmpty()
            .filter { it.name in WEB_TOOLS && it.name !in disabled }
        val allowed = definitions.map { it.name }.toSet()
        val history = base.messages.toMutableList()
        val seen = mutableSetOf<Pair<String, Map<String, String>>>()

        for (round in 1..7) {
            roundsObserved = round
            val canUseTools = round <= 6 && calls < 8 && tokens < 32_000
            event(AgentEvent.Thinking(round))
            val response = complete(
                base.copy(
                    messages = history.toList(),
                    tools = if (canUseTools) definitions + MODE_TOOL else null,
                    systemPrompt = base.systemPrompt.orEmpty() + if (canUseTools) "" else
                        "\nTool budget exhausted. Summarize verified results and any remaining limitations; do not claim unfinished work is complete."
                )
            )
            tokens += response.tokensUsed?.totalTokens ?: 0
            event(
                AgentEvent.TokenUsageUpdate(
                    response.tokensUsed?.totalTokens ?: 0,
                    tokens,
                    32_000
                )
            )
            if (response.toolCalls.isEmpty()) {
                return learnedResult(
                    content = response.content,
                    outcome = ModeOutcomeLearner.Outcome.SUCCESS,
                    verified = calls > 0 && !hadToolError
                )
            }
            if (!canUseTools) {
                return learnedResult(
                    content = response.content.ifBlank {
                        "Chat tool budget reached. Continue the research in a new message if needed."
                    },
                    outcome = ModeOutcomeLearner.Outcome.ABANDONED,
                    verified = false
                )
            }

            history += ChatMessage(
                MessageRole.ASSISTANT,
                response.content,
                toolCalls = response.toolCalls,
                thinkingContent = response.thinkingContent
            )
            val results = mutableListOf<ToolCallResult>()

            for (call in response.toolCalls) {
                event(AgentEvent.ToolExecution(call.name, call.arguments, round))
                val mode = call.arguments["mode"]?.takeIf { it == "AGENT" || it == "SWARM" }
                val reason = call.arguments["reason"]?.trim()?.take(1200)
                if (call.name == REQUEST_MODE && mode != null && !reason.isNullOrBlank()) {
                    event(
                        AgentEvent.ToolResult(
                            call.name,
                            "Awaiting user approval; no execution started.",
                            false,
                            round
                        )
                    )
                    // A handoff proposal is correct behavior, not a failed Chat run.
                    return Result(
                        reason,
                        ExecutionModeRequest(
                            mode = mode,
                            reason = reason,
                            originMessageId = originMessageId,
                            sourceMode = OmniMode.CHAT.name,
                            confidence = 0.90f,
                            trigger = AdaptiveModeRouter.Trigger.CHAT_CAPABILITY_GAP.name
                        )
                    )
                }

                val result = when {
                    calls >= 8 -> ToolExecutionResult("Chat tool budget exhausted.", true)
                    call.name !in allowed -> ToolExecutionResult(
                        "Tool unavailable in Chat. Use request_execution_mode only if execution is required.",
                        true
                    )
                    !seen.add(call.name to call.arguments) -> ToolExecutionResult(
                        "Identical request already executed. Use its previous result or change the query.",
                        true
                    )
                    else -> {
                        calls++
                        try {
                            withTimeoutOrNull(60_000) {
                                tools!!.executeTool(call.name, call.arguments, null)
                            } ?: ToolExecutionResult("Tool timed out after 60 seconds.", true)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            ToolExecutionResult(error.message ?: "Tool failed.", true)
                        }
                    }
                }
                if (result.isError) hadToolError = true
                val output = result.output.take(12_000)
                event(AgentEvent.ToolResult(call.name, output, result.isError, round))
                results += ToolCallResult(
                    call.id,
                    call.name,
                    output.take(4000),
                    result.isError
                )
            }

            history += ChatMessage(
                MessageRole.TOOL,
                results.joinToString("\n") { it.output },
                toolResults = results
            )
        }

        return learnedResult(
            content = "Chat tool budget reached. Continue the research in a new message if needed.",
            outcome = ModeOutcomeLearner.Outcome.ABANDONED,
            verified = false
        )
    }
}
