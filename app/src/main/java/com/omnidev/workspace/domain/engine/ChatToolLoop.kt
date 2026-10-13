package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.model.ExecutionModeRequest
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.model.ToolCallResult
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolExecutionResult
import com.omnidev.workspace.data.tools.ToolExecutionSemantics
import com.omnidev.workspace.data.tools.ToolManager
import com.omnidev.workspace.data.tools.ToolParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Native-tool loop for Chat with no application-imposed run budget.
 *
 * Chat uses the permitted flavor/scoped tool set. Before spending a model call, a deterministic
 * local preflight detects obvious execution-capability gaps and proposes Agent/Team directly.
 * Completed Chat runs feed local mode-outcome learning so AUTO routing can compare real outcomes
 * instead of assuming execution modes are always superior.
 */
class ChatToolLoop(
    private val tools: ToolManager?,
    private val connected: com.omnidev.workspace.data.mcp.McpRegistry? = null,
    private val confirmConnected: (suspend (String, String?) -> Boolean)? = null
) {
    companion object {
        val WEB_TOOLS = setOf(
            "web_search", "web_search_deep", "web_scraper", "fetch_page", "scrape_multiple"
        )
        val CHAT_TOOLS = WEB_TOOLS + setOf("compress_context", "eval_expression", "brain_reflexion_search", "brain_episode_search", "brain_recent_episodes", "brain_stats", "get_trust_profile", "list_earned_capabilities", "media_generation", "headless_browser", "read_file", "read_file_lines", "create_file", "patch_file_content", "append_to_file", "delete_file", "remember_fact", "search_knowledge", "update_memory", "delete_memory", "vector_store", "vector_search", "vector_similar", "omni_link", "planner_tool", "list_chat_sessions", "search_messages", "read_chat_session", "read_chat_message")
        const val REQUEST_MODE = "request_execution_mode"

        val MODE_TOOL = ToolDefinition(
            REQUEST_MODE,
            "Ask the user to enable execution. Never needed for web research. Use AGENT for sequential execution; SWARM only for independent parallel tasks. This only proposes a mode; it does not execute anything.",
            listOf(
                ToolParameter("mode", "string", "AGENT or SWARM", allowedValues = listOf("AGENT", "SWARM")),
                ToolParameter(
                    "reason",
                    "string",
                    "Explain the missing capability and ask permission, in the user's language."
                )
            )
        )
    }

    private fun isMediaRequest(text: String): Boolean =
        !Regex("(?i)implement|debug|repository|project|script|build an app|create an app|video editor|تطبيق|مشروع|برمج|كود|واتساب|whatsapp|telegram|publish").containsMatchIn(text) &&
        Regex("(?i)image|picture|photo|video|audio|music|song|صورة|صوره|صور|فيديو|صوت|موسيقى|موسيقي|أغنية|اغنية|اغاني|أغاني").containsMatchIn(text) &&
            Regex("(?i)generat|create|draw|make|send|attach|generate|ارسم|اعمل|ولّد|ولد|انشئ|أنشئ|ابعت|اعرض").containsMatchIn(text)

    data class Result(val content: String, val request: ExecutionModeRequest? = null)

    suspend fun run(
        base: CompletionRequest,
        disabled: Set<String>,
        originMessageId: String,
        complete: suspend (CompletionRequest) -> CompletionResponse,
        event: suspend (AgentEvent) -> Unit,
        toolAccessMode: String = "ON_DEMAND",
        mentionFocus: MentionFocus = MentionFocus.parse(base.messages.lastOrNull { it.role == MessageRole.USER }?.userInput
            ?: base.messages.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty())
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

        val mediaRequest = isMediaRequest(userRequest)
        val localSuggestion = if (!OmniMode.permitted(OmniMode.AGENT) || mediaRequest || mentionFocus.active) null else AdaptiveModeRouter.fromChatRequest(userRequest)
        if (localSuggestion != null && localSuggestion.confidence >= 0.72f) {
            val request = ExecutionModeRequest(
                mode = localSuggestion.to.name,
                reason = localSuggestion.reason,
                originMessageId = originMessageId,
                sourceMode = OmniMode.CHAT.name,
                confidence = localSuggestion.confidence,
                trigger = localSuggestion.trigger.name
            )
            // Correct capability-gap detection is not a failed Chat attempt.
            return Result(localSuggestion.reason, request)
        }

        val remote = if (toolAccessMode != "DISABLED" && connected != null &&
            (toolAccessMode == "AUTO" || mentionFocus.tools.any { it.startsWith("mcp_") })) {
            kotlinx.coroutines.withTimeoutOrNull(8_000) { connected.fetchAllAvailableTools() }.orEmpty()
        } else emptyList()
        val remoteNames = remote.map { it.name }.toSet()
        val definitions = (tools?.getToolDefinitions().orEmpty() + remote)
            .filter { toolAccessMode != "DISABLED" && (it.name in CHAT_TOOLS || it.name in remoteNames) && it.name !in disabled }
        mentionFocus.validateTools(definitions.map { it.name }.toSet())
        val orderedDefinitions = definitions.sortedBy { it.name !in mentionFocus.tools }.take(48)
        val allowed = definitions.map { it.name }.toSet()
        val history = base.messages.toMutableList()
        val seen = mutableSetOf<String>()
        val answer = StringBuilder()
        var lastTruncatedChunk: String? = null
        var finalize = false
        var continuingAnswer = false
        var round = 0

        fun fingerprint(name: String, arguments: Map<String, String>): String {
            val encoded = kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive(name))
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    arguments.toSortedMap().forEach { (key, value) -> put(key, kotlinx.serialization.json.JsonPrimitive(value)) }
                })
            }.toString()
            return java.security.MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }

        while (true) {
            currentCoroutineContext().ensureActive()
            round = (round.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            roundsObserved = round
            if (history.lastOrNull()?.toolResults?.any { it.toolName == "compress_context" && !it.isError } == true)
                ContextCompressor.compactLocally(history) { event(it) }
            else ContextCompressor.compactHistoricalToolEvidence(history, keepRecentToolGroups = 1)
            // Keep the model working context compact without limiting task length.
            // Only the prompt copy is compacted; saved messages remain.
            ContextCompressor.checkAndCompact(history, tokenBudget = 12_000, modelId = base.modelId,
                completionProvider = { error("Chat compaction is local") }, allowModelSummary = false,
                emitCompaction = { event(it) })
            val request = base.copy(
                messages = history.toList(),
                tools = if (!finalize) orderedDefinitions + if (OmniMode.permitted(OmniMode.AGENT)) listOf(MODE_TOOL) else emptyList() else null,
                responsePrefix = answer.toString(),
                systemPrompt = base.systemPrompt.orEmpty() + "\n" + mentionFocus.prompt() + if (!finalize) "" else if (continuingAnswer)
                    "\nContinue the interrupted final answer without tools or repetition." else
                    "\nThe last tool batch made no executable progress. Give the best answer from verified evidence, explain blocked actions, and do not request more tools or claim unfinished work is complete."
            )
            val canUseTools = !finalize && request.tools?.isNotEmpty() == true

            event(AgentEvent.Thinking(round))
            val response = CompletionRetry.run(onRetry = { attempt ->
                event(AgentEvent.PhaseChanged(AgentExecutionPhase.ANALYZE, "Provider temporarily unavailable. Retrying the response ($attempt/2); completed tools will not run again."))
            }) { complete(request.copy(retryManagedByCaller = true)) }
            val usage = TokenAccounting.usage(request, response)
            tokens = (tokens.toLong() + usage.totalTokens).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            event(
                AgentEvent.TokenUsageUpdate(
                    iterationTokens = usage.totalTokens,
                    totalTokens = tokens,
                    budget = null
                )
            )

            if (response.toolCalls.isEmpty()) {
                val truncated = response.finishReason?.lowercase() in setOf("length", "max_tokens", "max_output_tokens")
                if (response.content.isBlank()) return learnedResult(
                    answer.toString().ifBlank { "The provider returned no answer. Check its response/output settings and retry." },
                    ModeOutcomeLearner.Outcome.ABANDONED)
                val chunk = fingerprint("continuation", mapOf("text" to response.content))
                if (truncated && chunk == lastTruncatedChunk) return learnedResult(
                    answer.toString() + "\n\nThe provider repeated an identical continuation; generation stopped to avoid a loop.",
                    ModeOutcomeLearner.Outcome.ABANDONED)
                answer.append(response.content)
                if (truncated) {
                    lastTruncatedChunk = chunk
                    // A provider's per-response limit is not the end of the user's task.
                    // Continue in the same saved response until EOS or user cancellation.
                    history += ChatMessage(MessageRole.ASSISTANT, response.content)
                    history += ChatMessage(MessageRole.SYSTEM,
                        "Continue the interrupted answer exactly where it ended. Do not restart, repeat previous text or use tools. Finish the original user's request.")
                    finalize = true
                    continuingAnswer = true
                    event(AgentEvent.PhaseChanged(AgentExecutionPhase.IMPLEMENT, "Continuing the response after the provider's output limit."))
                    continue
                }
                return learnedResult(
                    content = answer.toString(),
                    outcome = ModeOutcomeLearner.Outcome.SUCCESS,
                    verified = calls > 0 && !hadToolError
                )
            }
            if (!canUseTools) {
                return learnedResult(
                    content = answer.toString() + response.content.ifBlank {
                        "The model did not provide a final answer after a blocked or repeated tool batch. Saved tool results remain available."
                    },
                    outcome = ModeOutcomeLearner.Outcome.ABANDONED
                )
            }

            history += ChatMessage(
                MessageRole.ASSISTANT,
                response.content,
                toolCalls = response.toolCalls,
                thinkingContent = response.thinkingContent
            )
            val results = mutableListOf<ToolCallResult>()

            val callsBeforeBatch = calls
            val batchErrors = ToolCallPreflight(request.tools.orEmpty()).checkBatch(response.toolCalls)
            for ((callIndex, call) in response.toolCalls.withIndex()) {
                event(AgentEvent.ToolExecution(call.name, call.arguments, round))
                val mode = call.arguments["mode"]?.takeIf { it == "AGENT" || it == "SWARM" }
                val reason = call.arguments["reason"]?.trim()?.take(1200)
                if (batchErrors[callIndex] == null && call.name == REQUEST_MODE && mode != null && OmniMode.permitted(OmniMode.valueOf(mode)) && !reason.isNullOrBlank()) {
                    event(
                        AgentEvent.ToolResult(
                            call.name,
                            "Awaiting user approval; no execution started.",
                            false,
                            round
                        )
                    )
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

                val validationError = batchErrors[callIndex]
                val rawResult = when {
                    validationError != null -> validationError
                    call.name !in allowed -> ToolExecutionResult(
                        "Tool unavailable in Chat. Use request_execution_mode only if execution is required.",
                        true
                    )
                    !seen.add(fingerprint(call.name, call.arguments)) -> ToolExecutionResult(
                        "Identical request already executed. Use its previous result or change the query.",
                        true
                    )
                    else -> {
                        calls++
                        try {
                            withTimeoutOrNull(60_000) {
                                if (call.name in remoteNames) {
                                    val approved = confirmConnected?.invoke("Connected service action: ${call.name}",
                                        com.omnidev.workspace.ui.chat.ConsoleRedactor.text(call.arguments.toString())) == true
                                    if (!approved) ToolExecutionResult("Connected action denied. Do not retry.", true)
                                    else {
                                        val output = connected!!.executeMcpTool(call.name, call.arguments)
                                        ToolExecutionResult(output, output.startsWith("Error", true))
                                    }
                                } else tools!!.executeTool(call.name, call.arguments, null)
                            } ?: ToolExecutionResult("Tool timed out after 60 seconds.", true)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            ToolExecutionResult(error.message ?: "Tool failed.", true)
                        }
                    }
                }

                val normalized = ToolExecutionSemantics.normalize(call.name, rawResult)
                val modelSafe = normalized.copy(
                    output = SensitiveObservationRedactor.redact(normalized.output)
                )
                if (modelSafe.isError) hadToolError = true
                val output = modelSafe.output.take(12_000)
                event(AgentEvent.ToolResult(call.name, output, modelSafe.isError, round))
                results += ToolCallResult(
                    call.id,
                    call.name,
                    output,
                    modelSafe.isError
                )
            }

            // Finalize when every request was rejected or already executed. This is
            // a no-progress rule, not a token/call/round cap on useful work.
            if (calls == callsBeforeBatch) finalize = true
            history += ChatMessage(
                MessageRole.TOOL,
                results.joinToString("\n") { it.output },
                toolResults = results
            )
        }

    }
}
