package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.brain.EpisodeOutcome
import com.omnidev.workspace.data.model.AttachmentMeta
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.model.ModelTier
import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.model.ToolCallResult
import com.omnidev.workspace.data.model.ToolSchemaCompactor
import com.omnidev.workspace.data.tools.ToolExecutionResult
import com.omnidev.workspace.data.tools.ToolManager
import com.omnidev.workspace.data.tools.orchestration.ToolOrchestrator
import com.omnidev.workspace.registry.ModelRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeout
import kotlin.math.min

/** Runtime configuration for one ReAct run. Public fields remain source-compatible. */
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

/**
 * Autonomous ReAct runtime with strict token accounting, safe tool batching and local loop guards.
 */
class AgentPipeline(
    private val toolManager: ToolManager,
    private val mcpRegistry: com.omnidev.workspace.data.mcp.McpRegistry? = null,
    private val completionProvider: suspend (CompletionRequest) -> CompletionResponse,
    private val streamingCompletionProvider: (suspend (CompletionRequest, suspend (String) -> Unit) -> CompletionResponse)? = null,
    private val config: AgentConfig = AgentConfig(),
    private val apiKeyRepository: com.omnidev.workspace.data.repository.ApiKeyRepository? = null,
    private val memoryManager: com.omnidev.workspace.data.tools.MemoryManager? = null,
    private val smartLearningBridge: com.omnidev.workspace.data.brain.SmartLearningBridge? = null,
    private val toolOrchestrator: ToolOrchestrator = ToolOrchestrator(),
    private val analyticsRepository: com.omnidev.workspace.data.repository.AnalyticsRepository? = null
) {

    companion object {
        private const val MAX_TOOLS_PER_REQUEST = 80
        private const val RATE_LIMIT_MAX_RETRIES = 4
        private const val RATE_LIMIT_BASE_DELAY_MS = 15_000L
        private const val RATE_LIMIT_MAX_DELAY_MS = 60_000L
        private const val MIN_COMPLETION_OUTPUT_RESERVE = 256
        private const val CRITIC_MIN_REMAINING_BUDGET = 6_000
        private const val CRITIC_MAX_OUTPUT_TOKENS = 2_048
        private const val CRITIC_MAX_DRAFT_CHARS = 24_000

        private const val CRITIC_SYSTEM_PROMPT = """
Review the draft only for substantive incompleteness, incorrect claims, missing failure disclosure, or missing verification.
Return exactly:
VERDICT: APPROVED | NEEDS_IMPROVEMENT
IMPROVED_ANSWER: <complete answer>
Do not use tools. Do not rewrite merely for style.
"""

        private fun interCallDelayFor(tier: ModelTier): Long = when (tier) {
            ModelTier.FAST -> 100L
            ModelTier.EXECUTOR -> 250L
            ModelTier.ORCHESTRATOR -> 500L
        }
    }

    fun execute(
        userMessage: String,
        conversationHistory: List<ChatMessage> = emptyList(),
        modelId: String,
        scopePath: String,
        enableDeepThinking: Boolean = false,
        userAttachments: List<AttachmentMeta> = emptyList(),
        customSystemPrompt: String? = null,
        workerPersona: String? = null,
        userContext: String? = null,
        disabledToolNames: Set<String> = emptySet(),
        toolAccessMode: String = "AUTO"
    ): Flow<AgentEvent> = channelFlow {
        val brain = smartLearningBridge?.forkForRun()
        val startedAt = System.currentTimeMillis()
        var activePhase: AgentExecutionPhase? = null

        suspend fun phase(next: AgentExecutionPhase, detail: String? = null) {
            if (activePhase != next) {
                activePhase = next
                send(AgentEvent.PhaseChanged(next, detail))
            }
        }

        suspend fun emitUsage(iterationTokens: Int, totalTokens: Int) {
            send(
                AgentEvent.TokenUsageUpdate(
                    iterationTokens = iterationTokens,
                    totalTokens = totalTokens,
                    budget = config.tokenBudget
                )
            )
        }

        send(AgentEvent.Started)
        phase(AgentExecutionPhase.ANALYZE, "Reviewing objective, context and constraints")
        brain?.onTaskStart(userMessage)
        try {
            analyticsRepository?.recordAgentRun(isSwarm = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Optional telemetry.
        }

        val model = ModelRegistry.findModelById(modelId) ?: ModelRegistry.getModelById(modelId)
        val resolvedApiKey = apiKeyRepository?.getApiKey(model.provider)

        // Team worker prompts contain the original broad objective after the assigned atomic task.
        // Route capabilities from the assigned slice only, otherwise every worker gets the whole
        // project's tool domains and pays for irrelevant schemas.
        val routingObjective = extractRoutingObjective(userMessage)
        val relevantDomains = IntentClassifier.getRelevantDomains(routingObjective)
        val localTools = toolManager.getToolDefinitions()
            .asSequence()
            .filter { it.name !in disabledToolNames }
            .filter { IntentClassifier.getToolDomain(it.name) in relevantDomains }
            .toList()
        val mcpTools = try {
            mcpRegistry?.fetchAllAvailableTools().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        val rawToolDefs = (localTools + mcpTools).distinctBy { it.name }
        val toolDefs = ToolSchemaCompactor.compact(
            tools = rawToolDefs,
            messages = listOf(ChatMessage(MessageRole.USER, routingObjective))
        ).orEmpty().take(MAX_TOOLS_PER_REQUEST)
        brain?.registerTools(toolDefs)

        val memoryContext = try { memoryManager?.buildKnowledgeContext() } catch (_: Exception) { null }
        val brainContext = try { brain?.buildFullContextEnrichment() } catch (_: Exception) { null }
        val systemPrompt = AgentPromptCompiler.compile(
            tier = model.tier,
            scopePath = scopePath,
            baseOverride = customSystemPrompt,
            workerPersona = workerPersona,
            userContext = userContext,
            memoryContext = memoryContext,
            brainContext = brainContext,
            toolDefinitions = toolDefs,
            toolAccessMode = toolAccessMode,
            enableDeepThinking = enableDeepThinking,
            supportsThinking = model.supportsThinking
        )

        val messages = mutableListOf<ChatMessage>().apply {
            addAll(conversationHistory)
            add(ChatMessage(MessageRole.USER, userMessage, attachments = userAttachments))
        }

        var iteration = 0
        var totalTokensUsed = 0
        val repetitionGuard = ToolRepetitionGuard(config.maxRepeatedToolCalls)
        val stagnationDetector = AgentStagnationDetector(
            windowSize = 8,
            minIterationsBeforeAbort = 3,
            abortThreshold = 0.76f
        )

        while (iteration < config.maxIterations) {
            iteration++
            brain?.onIterationStart()

            config.maxExecutionTimeMs?.let { timeoutMs ->
                if (System.currentTimeMillis() - startedAt >= timeoutMs) {
                    brain?.onTaskEnd(EpisodeOutcome.ABANDONED, "wall-clock timeout after ${timeoutMs / 1_000}s")
                    send(AgentEvent.Error("Agent execution timed out after ${timeoutMs / 1_000}s."))
                    return@channelFlow
                }
            }

            val remainingAtStart = config.tokenBudget?.minus(totalTokensUsed)
            if (remainingAtStart != null && remainingAtStart <= MIN_COMPLETION_OUTPUT_RESERVE) {
                brain?.onTaskEnd(EpisodeOutcome.ABANDONED, "token budget exhausted before iteration $iteration")
                send(AgentEvent.Error("Token budget of ${config.tokenBudget} tokens is exhausted."))
                return@channelFlow
            }

            if (iteration > 1) delay(interCallDelayFor(model.tier))
            send(AgentEvent.Thinking(iteration))

            val desiredOutputBudget = minOf(
                model.maxOutputTokens,
                8_192,
                remainingAtStart?.coerceAtLeast(MIN_COMPLETION_OUTPUT_RESERVE) ?: 8_192
            )
            // Tool definitions are already compacted to the exact set sent to the provider.
            val schemaEstimate = (toolDefs.sumOf { it.toString().length } / 3).coerceAtLeast(0)
            val inputBudget = model.contextWindow - desiredOutputBudget - config.contextWindowBuffer -
                systemPrompt.length / 3 - schemaEstimate
            if (inputBudget <= 0) {
                brain?.onTaskEnd(EpisodeOutcome.FAILURE, "system/tool schema exceeds context window")
                send(AgentEvent.Error("System instructions and tool definitions exceed this model's context window."))
                return@channelFlow
            }

            if (config.enableMemoryTrimming) {
                val report = ContextCompressor.checkAndCompact(
                    messages = messages,
                    tokenBudget = inputBudget,
                    modelId = modelId,
                    completionProvider = completionProvider,
                    apiKey = resolvedApiKey,
                    remainingTokenBudget = config.tokenBudget?.minus(totalTokensUsed),
                    allowModelSummary = config.tokenBudget?.let { budget ->
                        totalTokensUsed < (budget * 0.70f).toInt()
                    } ?: true
                ) { send(it) }
                if (report.tokensUsed > 0) {
                    totalTokensUsed += report.tokensUsed
                    emitUsage(report.tokensUsed, totalTokensUsed)
                }
            }

            val requestMessages = if (config.enableMemoryTrimming) {
                ContextCompressor.trim(messages, inputBudget)
            } else messages.toList()
            if (requestMessages.sumOf(ContextCompressor::estimatedTokens) > inputBudget) {
                brain?.onTaskEnd(EpisodeOutcome.FAILURE, "latest context still exceeds input budget")
                send(AgentEvent.Error("The latest message/tool result exceeds this model's context window."))
                return@channelFlow
            }

            val requestPrototype = CompletionRequest(
                modelId = modelId,
                messages = requestMessages,
                systemPrompt = systemPrompt,
                maxTokens = desiredOutputBudget,
                enableThinking = enableDeepThinking && model.supportsThinking,
                targetContext = scopePath,
                apiKey = resolvedApiKey,
                tools = toolDefs
            )
            val estimatedRequestInput = TokenAccounting.estimateInputTokens(requestPrototype)
            val remainingAfterCompaction = config.tokenBudget?.minus(totalTokensUsed)
            if (remainingAfterCompaction != null &&
                remainingAfterCompaction <= estimatedRequestInput + MIN_COMPLETION_OUTPUT_RESERVE
            ) {
                brain?.onTaskEnd(EpisodeOutcome.ABANDONED, "insufficient remaining token budget for next request")
                send(
                    AgentEvent.Error(
                        "Token budget is too low for another safe model request after accounting for its input context."
                    )
                )
                return@channelFlow
            }

            val outputBudget = minOf(
                desiredOutputBudget,
                remainingAfterCompaction?.let {
                    (it - estimatedRequestInput).coerceAtLeast(MIN_COMPLETION_OUTPUT_RESERVE)
                } ?: desiredOutputBudget
            )
            val request = requestPrototype.copy(
                maxTokens = outputBudget,
                onReasoning = { send(AgentEvent.ThinkingBlock(it)) }
            )

            val response = callWithRetry(
                request = request,
                iteration = iteration,
                onStreamChunk = { send(AgentEvent.StreamChunk(it)) },
                onFatalError = { error ->
                    val outcome = if (isInfrastructureError(error)) EpisodeOutcome.BLOCKED else EpisodeOutcome.FAILURE
                    brain?.onTaskEnd(outcome, "API/runtime failure: ${error.take(240)}")
                    send(AgentEvent.Error(error))
                }
            ) ?: return@channelFlow

            // Usage is never allowed to disappear merely because a provider omitted metadata.
            val responseUsage = TokenAccounting.usage(request, response)
            totalTokensUsed += responseUsage.totalTokens
            emitUsage(responseUsage.totalTokens, totalTokensUsed)

            response.thinkingContent
                ?.takeIf { streamingCompletionProvider == null }
                ?.let { send(AgentEvent.ThinkingBlock(it)) }

            if (response.toolCalls.isEmpty()) {
                phase(AgentExecutionPhase.VERIFY, "Checking final completeness")
                messages += ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = response.content,
                    thinkingContent = response.thinkingContent
                )

                val finalContent = maybeCritique(
                    draft = response.content,
                    originalUserMessage = userMessage,
                    modelId = modelId,
                    modelMaxOutputTokens = model.maxOutputTokens,
                    scopePath = scopePath,
                    apiKey = resolvedApiKey,
                    totalTokensUsed = totalTokensUsed,
                    onUsage = { used ->
                        totalTokensUsed += used
                        emitUsage(used, totalTokensUsed)
                    },
                    onReflecting = { send(AgentEvent.Reflecting(it)) }
                )

                phase(AgentExecutionPhase.REPORT, "Publishing verified result")
                brain?.onTaskEnd(EpisodeOutcome.SUCCESS, finalContent.take(500))
                send(
                    AgentEvent.FinalAnswer(
                        content = finalContent,
                        totalIterations = iteration,
                        totalTokensUsed = totalTokensUsed,
                        conversationHistory = messages.toList()
                    )
                )
                return@channelFlow
            }

            phase(AgentExecutionPhase.IMPLEMENT, "Executing planned tool operations")
            messages += ChatMessage(
                role = MessageRole.ASSISTANT,
                content = response.content,
                toolCalls = response.toolCalls,
                thinkingContent = response.thinkingContent
            )

            for (call in response.toolCalls) {
                if (!repetitionGuard.allow(call.name, call.arguments)) {
                    brain?.onTaskEnd(EpisodeOutcome.ABANDONED, "semantic duplicate tool-call limit reached: ${call.name}")
                    send(
                        AgentEvent.Error(
                            "Agent no-progress loop detected: ${call.name} repeated with equivalent arguments more than ${config.maxRepeatedToolCalls} times."
                        )
                    )
                    return@channelFlow
                }
            }

            response.toolCalls.forEach { call ->
                send(AgentEvent.ToolExecution(call.name, call.arguments, iteration))
                brain?.onToolExecutionStart(call.name, call.id)
            }

            val rawResults = executeToolBatch(
                calls = response.toolCalls,
                scopePath = scopePath,
                allowParallel = config.enableParallelToolExecution
            )

            val toolResults = mutableListOf<ToolCallResult>()
            val modelSafeResults = mutableListOf<ToolExecutionResult>()
            for ((call, result) in response.toolCalls.zip(rawResults)) {
                // Authentication secrets are transient by design. Do not persist them in model
                // context, Agent Brain, checkpoints, or the user-visible execution console.
                val modelSafe = result.copy(
                    output = SensitiveObservationRedactor.redact(result.output)
                )
                send(AgentEvent.ToolResult(call.name, modelSafe.output, modelSafe.isError, iteration))
                modelSafeResults += modelSafe
                toolResults += ToolCallResult(call.id, call.name, modelSafe.output, modelSafe.isError)
                brain?.onToolExecutionEnd(
                    toolName = call.name,
                    parameters = call.arguments,
                    result = modelSafe,
                    agentContext = redact(userMessage.take(240)),
                    callId = call.id
                )
            }

            val stagnation = stagnationDetector.observe(response.toolCalls, modelSafeResults)
            if (stagnation.shouldAbort) {
                val outcome = if (stagnation.kind == AgentStagnationDetector.Kind.INFRASTRUCTURE_BLOCK) {
                    EpisodeOutcome.BLOCKED
                } else EpisodeOutcome.ABANDONED
                brain?.onTaskEnd(
                    outcome,
                    buildString {
                        append("stagnation kind=${stagnation.kind} score=${"%.2f".format(stagnation.score)}")
                        if (stagnation.reasons.isNotEmpty()) {
                            append(" reasons=").append(stagnation.reasons.joinToString("; ").take(320))
                        }
                    }
                )
                send(AgentEvent.Error(stagnation.errorMessage()))
                return@channelFlow
            }

            val hasErrors = toolResults.any { it.isError }
            val toolContent = buildString {
                append(
                    toolResults.joinToString("\n\n") { result ->
                        "[${result.toolName}] ${if (result.isError) "ERROR: " else ""}${result.output}"
                    }
                )
                if (hasErrors) {
                    append("\n\nOne or more tools failed. Do not claim those operations succeeded; pivot strategy or report the blocker explicitly.")
                } else if (stagnation.noActionStreak >= 2 && stagnation.readOnlyRatio >= 0.75f) {
                    append(
                        "\n\n[Omni runtime guidance] You already have multiple successful read-only " +
                            "observations. Prefer answering/synthesizing from the evidence now. Run another " +
                            "read-only probe only if you can name a specific missing fact that the next query " +
                            "will materially resolve; do not re-query the same dataset with cosmetic filters."
                    )
                }
            }
            messages += ChatMessage(MessageRole.TOOL, toolContent, toolResults = toolResults)

            // Full observations have already been emitted to UI + learning. Keep only the latest
            // tool group verbatim in the next model request; older evidence is compacted locally.
            ContextCompressor.compactHistoricalToolEvidence(messages, keepRecentToolGroups = 1)
        }

        brain?.onTaskEnd(EpisodeOutcome.ABANDONED, "max iterations reached after ${config.maxIterations} loops")
        send(AgentEvent.Error("Agent reached maximum iterations (${config.maxIterations}) without completing."))
    }

    private suspend fun executeToolBatch(
        calls: List<ToolCall>,
        scopePath: String,
        allowParallel: Boolean
    ): List<ToolExecutionResult> {
        suspend fun executeOne(call: ToolCall): ToolExecutionResult {
            val started = System.nanoTime()
            val orchestrated = toolOrchestrator.executeTool(
                toolName = call.name,
                cacheKey = null,
                timeoutMs = config.toolExecutionTimeoutMs,
                maxRetries = config.toolExecutionMaxRetries,
                baseRetryDelayMs = config.toolExecutionBaseRetryDelayMs
            ) {
                val result = if (call.name.startsWith("mcp_")) {
                    val output = mcpRegistry?.executeMcpTool(call.name, call.arguments)
                        ?: "Error: MCP Registry not configured"
                    ToolExecutionResult(
                        output = output,
                        isError = output.startsWith("Error", ignoreCase = true),
                        classification = if (output.startsWith("Error", true)) "MCP_ERROR" else null,
                        backend = "mcp"
                    )
                } else {
                    toolManager.executeTool(call.name, call.arguments, scopePath)
                }
                try {
                    analyticsRepository?.recordToolUsage(
                        call.name,
                        !result.isError,
                        (System.nanoTime() - started) / 1_000_000
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Telemetry cannot break execution.
                }
                result
            }

            return if (orchestrated.isSuccess) orchestrated.getOrThrow() else {
                ToolExecutionResult(
                    output = orchestrated.exceptionOrNull()?.message ?: "Tool execution failed",
                    isError = true,
                    classification = "TOOL_TRANSPORT_FAILURE",
                    persistentFailure = true
                )
            }
        }

        val parallel = allowParallel && ToolBatchPolicy.canRunBatchInParallel(calls)
        return if (parallel) {
            coroutineScope { calls.map { async { executeOne(it) } }.awaitAll() }
        } else calls.map { executeOne(it) }
    }

    private suspend fun maybeCritique(
        draft: String,
        originalUserMessage: String,
        modelId: String,
        modelMaxOutputTokens: Int,
        scopePath: String,
        apiKey: String?,
        totalTokensUsed: Int,
        onUsage: suspend (Int) -> Unit,
        onReflecting: suspend (Int) -> Unit
    ): String {
        if (!config.enableSelfReflection || draft.isBlank()) return draft
        val remaining = config.tokenBudget?.minus(totalTokensUsed)
        if (remaining != null && remaining < CRITIC_MIN_REMAINING_BUDGET) return draft

        onReflecting(draft.length)
        val criticOutputBudget = minOf(
            modelMaxOutputTokens,
            CRITIC_MAX_OUTPUT_TOKENS,
            remaining?.coerceAtLeast(512) ?: CRITIC_MAX_OUTPUT_TOKENS
        )
        val criticRequest = CompletionRequest(
            modelId = modelId,
            messages = listOf(
                ChatMessage(
                    MessageRole.USER,
                    buildString {
                        appendLine("Original request:")
                        appendLine(originalUserMessage.take(6_000))
                        appendLine("\nDraft:")
                        appendLine(draft.take(CRITIC_MAX_DRAFT_CHARS))
                    }
                )
            ),
            systemPrompt = CRITIC_SYSTEM_PROMPT.trimIndent(),
            maxTokens = criticOutputBudget,
            enableThinking = false,
            targetContext = scopePath,
            apiKey = apiKey,
            tools = emptyList()
        )

        val started = System.currentTimeMillis()
        val critic = try {
            val response = withTimeout(config.maxIterationTimeMs ?: 180_000L) {
                completionProvider(criticRequest)
            }
            recordAnalytics(criticRequest, response, started, false)
            response
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recordAnalytics(criticRequest, null, started, true)
            null
        } ?: return draft

        onUsage(TokenAccounting.usage(criticRequest, critic).totalTokens)
        val text = critic.content.trim()
        if (!text.contains("VERDICT: NEEDS_IMPROVEMENT", ignoreCase = true)) return draft
        val marker = "IMPROVED_ANSWER:"
        val index = text.indexOf(marker, ignoreCase = true)
        return if (index >= 0) {
            text.substring(index + marker.length).trim().takeIf(String::isNotBlank) ?: draft
        } else draft
    }

    private suspend fun callWithRetry(
        request: CompletionRequest,
        iteration: Int,
        onStreamChunk: suspend (String) -> Unit = {},
        onFatalError: suspend (String) -> Unit
    ): CompletionResponse? {
        val normalMaxAttempts = if (config.enableRetry) config.maxRetries + 1 else 1
        var rateLimitAttemptsRemaining = if (config.enableRetry) RATE_LIMIT_MAX_RETRIES else 0
        var normalAttempt = 0
        var emitted = false

        while (true) {
            val callStarted = System.currentTimeMillis()
            try {
                val chunkHandler: suspend (String) -> Unit = {
                    emitted = true
                    onStreamChunk(it)
                }
                val streamRequest = request.copy(
                    onReasoning = {
                        emitted = true
                        request.onReasoning?.invoke(it)
                    }
                )
                val response = if (config.maxIterationTimeMs != null) {
                    withTimeout(config.maxIterationTimeMs) {
                        if (streamingCompletionProvider != null) {
                            streamingCompletionProvider.invoke(streamRequest, chunkHandler)
                        } else completionProvider(request)
                    }
                } else {
                    if (streamingCompletionProvider != null) {
                        streamingCompletionProvider.invoke(streamRequest, chunkHandler)
                    } else completionProvider(request)
                }
                recordAnalytics(request, response, callStarted, false)
                return response
            } catch (_: TimeoutCancellationException) {
                recordAnalytics(request, null, callStarted, true)
                val seconds = (config.maxIterationTimeMs ?: 180_000L) / 1_000L
                onFatalError("LLM call timed out after ${seconds}s at iteration $iteration.")
                return null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recordAnalytics(request, null, callStarted, true)
                if (emitted) {
                    onFatalError("Response interrupted after partial output: ${error.message}")
                    return null
                }

                val rateLimit = error as? com.omnidev.workspace.data.network.RateLimitException
                val rateLimited = rateLimit != null || containsAny(
                    error.message.orEmpty().lowercase(),
                    "rate limit", "too many requests", "http 429"
                )
                if ((rateLimit?.retryAfterMs ?: 0L) > RATE_LIMIT_MAX_DELAY_MS) {
                    onFatalError(rateLimit?.message ?: "Provider cooldown exceeds the retry window.")
                    return null
                }
                if (rateLimited && rateLimitAttemptsRemaining > 0) {
                    rateLimitAttemptsRemaining--
                    val retryNumber = RATE_LIMIT_MAX_RETRIES - rateLimitAttemptsRemaining
                    delay(
                        maxOf(
                            rateLimit?.retryAfterMs ?: 0L,
                            min(RATE_LIMIT_BASE_DELAY_MS * retryNumber, RATE_LIMIT_MAX_DELAY_MS)
                        )
                    )
                    continue
                }

                val permanent = isPermanentRequestError(error.message.orEmpty())
                if (rateLimited || permanent || normalAttempt >= normalMaxAttempts - 1) {
                    val message = when {
                        rateLimited -> "Rate limit reached after all retry attempts (iteration $iteration)."
                        isNetworkException(error) -> "Network failure after $normalMaxAttempts attempt(s) (iteration $iteration): ${error.message}"
                        else -> "API call failed after $normalMaxAttempts attempt(s) (iteration $iteration): ${error.message}"
                    }
                    onFatalError(message)
                    return null
                }

                delay(min(config.baseRetryDelayMs * (1L shl normalAttempt), 30_000L))
                normalAttempt++
            }
        }
    }

    private suspend fun recordAnalytics(
        request: CompletionRequest,
        response: CompletionResponse?,
        callStartMs: Long,
        isError: Boolean
    ) {
        val repository = analyticsRepository ?: return
        try {
            val model = ModelRegistry.findModelById(request.modelId)
            val usage = response?.let { TokenAccounting.usage(request, it) }
            val inputTokens = usage?.inputTokens ?: 0
            val outputTokens = usage?.outputTokens ?: 0
            repository.recordTokenUsage(
                modelId = request.modelId,
                provider = model?.provider,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                costUsd = com.omnidev.workspace.data.repository.DynamicPricingManager()
                    .calculateCost(request.modelId, inputTokens, outputTokens),
                latencyMs = (System.currentTimeMillis() - callStartMs).coerceAtLeast(0L),
                isError = isError
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Analytics is best effort.
        }
    }

    private fun extractRoutingObjective(userMessage: String): String {
        val marker = "## Assigned Team Task"
        val start = userMessage.indexOf(marker, ignoreCase = true)
        if (start < 0) return userMessage.take(4_000)
        val afterMarker = userMessage.substring(start + marker.length)
        val boundaries = listOf(
            "## Runtime", "## Worker budget", "## Contract", "## Execution contract",
            "## Dependency evidence", "## Original objective"
        ).mapNotNull { heading ->
            afterMarker.indexOf(heading, ignoreCase = true).takeIf { it >= 0 }
        }
        val end = boundaries.minOrNull() ?: afterMarker.length
        return afterMarker.substring(0, end).trim().takeIf(String::isNotBlank)
            ?.take(4_000)
            ?: userMessage.take(4_000)
    }

    private fun isPermanentRequestError(message: String): Boolean = containsAny(
        message.lowercase(),
        "api error 400", "api key is invalid", "no api key", "unauthorized", "forbidden",
        "model not found", "invalid request format", "insufficient quota"
    )

    private fun isInfrastructureError(message: String): Boolean = containsAny(
        message.lowercase(),
        "rate limit", "429", "api key", "unauthorized", "forbidden", "quota", "network",
        "timed out", "timeout", "connection", "dns", "service unavailable", "provider cooldown"
    )

    private fun isNetworkException(error: Exception): Boolean =
        error is java.net.SocketTimeoutException ||
            error is java.net.SocketException ||
            error is java.io.IOException

    private fun containsAny(haystack: String, vararg needles: String): Boolean =
        needles.any(haystack::contains)

    private fun redact(value: String): String = value
        .replace(Regex("(?i)(key|token|secret|password|otp|bearer)[=:\\s]+\\S+"), "$1=[REDACTED]")
        .replace(
            Regex("(?i)\"(api_?key|token|secret|password|otp)\"\\s*:\\s*\"[^\"]+\""),
            "\"$1\":\"[REDACTED]\""
        )
        .replace(Regex("(?i)(key|token|secret|password|otp)=([^&\\s\"]+)"), "$1=[REDACTED]")
}

enum class AgentExecutionPhase { ANALYZE, IMPLEMENT, VERIFY, REPORT }

sealed class AgentEvent {
    data object Started : AgentEvent()
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
