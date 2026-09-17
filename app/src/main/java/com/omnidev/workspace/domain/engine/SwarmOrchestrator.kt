package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.tools.ToolManager
import com.omnidev.workspace.registry.ModelRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Team/Swarm coordinator.
 *
 * The orchestrator model only plans and synthesizes. Worker model instances execute atomic tasks.
 * Runtime scheduling does not blindly trust planner metadata: task descriptions are independently
 * classified for mutation/resource conflicts before concurrency is allowed, and each worker gets
 * an adaptive token/iteration budget instead of a fixed oversized allowance.
 */
class SwarmOrchestrator(
    private val toolManager: ToolManager,
    private val completionProvider: suspend (CompletionRequest) -> CompletionResponse,
    private val apiKeyRepository: com.omnidev.workspace.data.repository.ApiKeyRepository? = null,
    private val memoryManager: com.omnidev.workspace.data.tools.MemoryManager? = null,
    private val streamingCompletionProvider: (suspend (CompletionRequest, suspend (String) -> Unit) -> CompletionResponse)? = null,
    private val smartLearningBridge: com.omnidev.workspace.data.brain.SmartLearningBridge? = null,
    private val analyticsRepository: com.omnidev.workspace.data.repository.AnalyticsRepository? = null
) {

    companion object {
        private const val MAX_SUBTASKS = 6
        private const val MAX_PARALLEL_WORKERS = 3
        private const val MAX_HANDOFF_CHARS_PER_DEPENDENCY = 6_000
        private const val MAX_HANDOFF_CHARS_TOTAL = 14_000
        private const val MAX_SYNTHESIS_CHARS_PER_TASK = 9_000
        private const val MAX_SYNTHESIS_CONTEXT_CHARS = 36_000
        /** Hard configured ceiling across all worker budgets before actual usage. */
        private const val TEAM_CONFIGURED_WORKER_TOKEN_CEILING = 300_000
        private const val MIN_SCALED_WORKER_BUDGET = 18_000

        /**
         * Base worker settings. Per-task maxIterations/tokenBudget/repetition are replaced by
         * [TeamExecutionPolicy] at runtime.
         */
        private val TEAM_WORKER_CONFIG = AgentConfig(
            maxIterations = 10,
            tokenBudget = 48_000,
            maxRepeatedToolCalls = 3,
            enableParallelToolExecution = true,
            enableSelfReflection = false,
            recentMessagesWindow = 10,
            sessionDigestUpdateEveryNMessages = 5,
            sessionDigestMaxChars = 1_200,
            sessionDigestMaxMessages = 24,
            toolExecutionTimeoutMs = 35_000L,
            toolExecutionMaxRetries = 1
        )

        private const val ORCHESTRATOR_SYSTEM_PROMPT = """
You are Omni Team Orchestrator. You coordinate specialist agents; you do not perform their work yourself.

Create the SMALLEST useful set of atomic tasks for the user's objective.

TEAM DESIGN RULES:
1. Produce 1-6 tasks. Never create filler tasks merely to satisfy a fixed Planner/Executor/Verifier template.
2. Do NOT create a worker whose only job is to repeat planning that you have already done.
3. Split work by genuinely independent evidence streams or implementation areas. Independent tasks should have no dependency so they can run concurrently.
4. A dependency is allowed only when the later task truly needs the earlier task's output.
5. Set parallelSafe=true only for read-only/research/analysis work that can safely overlap. Any task that edits files, changes device state, writes a repository, deploys, installs, or otherwise mutates shared state MUST use parallelSafe=false. Runtime will independently verify this flag and may serialize the task anyway.
6. Set needsConnectedTools=true only when the task explicitly needs MCP/connected cloud services such as GitHub, Render, Notion, etc. Ordinary web research and local work do not need it.
7. A verifier is optional. Create one only when independent verification materially improves correctness. A verifier MUST actually inspect evidence/use appropriate tools; it must never claim something is verified solely because another worker said it.
8. For time-sensitive research, obey the exact requested date window. Do not include archival events merely because a page was recently retrieved.
9. Runtime clock information is supplied below. Never create shell/Python/tool tasks merely to discover the current date or perform trivial date arithmetic that can be derived from it.
10. Give each worker a concrete finish condition. Prefer one focused worker with several parallel tool calls over many tiny conversational turns.
11. Do not split a task only to create more workers. Every additional worker has context/token overhead and must provide real specialization or safe parallelism.

Return ONLY a JSON array with this shape:
[
  {
    "id": "short-stable-id",
    "description": "Concrete task and expected evidence/output",
    "priority": 1,
    "dependencies": [],
    "requiredPersona": "Best specialist persona",
    "parallelSafe": true,
    "needsConnectedTools": false
  }
]
"""

        private const val SYNTHESIS_PROMPT = """
You are Omni Team Orchestrator producing the final answer from specialist-worker evidence.

Rules:
1. Answer the user's original request directly. Do not narrate the team machinery unless relevant.
2. Treat worker outputs as evidence, not unquestionable truth. Do not upgrade an unsupported worker assertion into a verified fact.
3. If a worker failed, timed out, or could not verify something, say so where it matters and never claim overall verification for that part.
4. For time-windowed research, exclude evidence outside the requested window even if a worker included it.
5. Prefer concrete tool-observed facts and source metadata over worker opinion.
6. Remove duplicate findings from workers and synthesize rather than concatenate.
7. Keep the final answer proportional to the user's request; do not repeat huge intermediate reports.
8. A dependent task skipped because its prerequisite failed is NOT evidence of completion. Preserve that limitation explicitly.
"""
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun execute(
        userMessage: String,
        orchestratorModelId: String,
        workerModelId: String,
        scopePath: String,
        enableDeepThinking: Boolean = false,
        godModeEnabled: Boolean = false
    ): Flow<SwarmEvent> = channelFlow {
        val teamStartedAt = System.currentTimeMillis()
        val observedTeamTokens = AtomicInteger(0)
        send(SwarmEvent.PlanningStarted)

        val orchestratorModel = ModelRegistry.findModelById(orchestratorModelId)
            ?: ModelRegistry.getModelById(orchestratorModelId)
        val orchestratorApiKey = apiKeyRepository?.getApiKey(orchestratorModel.provider)
        val runtimeClock = currentRuntimeClock()
        val godModeNote = if (godModeEnabled) {
            "\n[GOD MODE ENABLED]: Extended file tools are enabled. Actual privileged access still depends on the available backend."
        } else ""

        val planRequest = CompletionRequest(
            modelId = orchestratorModelId,
            messages = listOf(ChatMessage(role = MessageRole.USER, content = userMessage)),
            systemPrompt = buildString {
                append(ORCHESTRATOR_SYSTEM_PROMPT.trimIndent())
                append(godModeNote)
                appendLine()
                appendLine()
                appendLine("RUNTIME CLOCK (device): $runtimeClock")
            },
            maxTokens = minOf(orchestratorModel.maxOutputTokens, 4_096),
            enableThinking = enableDeepThinking && orchestratorModel.supportsThinking,
            targetContext = scopePath,
            apiKey = orchestratorApiKey
        )

        val planResponse = try {
            callWithRateLimitRetry(planRequest)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ModeOutcomeLearner.recordOutcome(
                userRequest = userMessage,
                mode = OmniMode.SWARM,
                outcome = ModeOutcomeLearner.Outcome.FAILURE,
                durationMs = (System.currentTimeMillis() - teamStartedAt).coerceAtLeast(0L)
            )
            send(SwarmEvent.Error("Planning failed: ${e.message}"))
            return@channelFlow
        }
        observedTeamTokens.addAndGet(planResponse.tokensUsed?.totalTokens ?: 0)

        val tasks = parseTasks(planResponse.content, userMessage)
        val validationError = validateTasks(tasks)
        if (validationError != null) {
            ModeOutcomeLearner.recordOutcome(
                userRequest = userMessage,
                mode = OmniMode.SWARM,
                outcome = ModeOutcomeLearner.Outcome.FAILURE,
                durationMs = (System.currentTimeMillis() - teamStartedAt).coerceAtLeast(0L),
                tokens = observedTeamTokens.get().takeIf { it > 0 }
            )
            send(SwarmEvent.Error(validationError))
            return@channelFlow
        }

        // Allocate worker budgets once for this plan, then scale them so a 6-worker plan cannot
        // silently configure ~1M tokens of maximum spend.
        val requestedBudgets = tasks.associate { it.id to TeamExecutionPolicy.budgetFor(it) }
        val requestedTotal = requestedBudgets.values.sumOf { it.tokenBudget }.coerceAtLeast(1)
        val budgetScale = minOf(
            1f,
            TEAM_CONFIGURED_WORKER_TOKEN_CEILING.toFloat() / requestedTotal.toFloat()
        )
        val workerBudgets = requestedBudgets.mapValues { (_, budget) ->
            if (budgetScale >= 0.999f) budget else budget.copy(
                tokenBudget = (budget.tokenBudget * budgetScale).toInt()
                    .coerceAtLeast(MIN_SCALED_WORKER_BUDGET)
            )
        }

        send(SwarmEvent.PlanCompleted(tasks))

        val completedTasks = linkedMapOf<String, String>()
        val failedTasks = linkedMapOf<String, String>()
        val skippedTasks = linkedMapOf<String, String>()
        val remaining = tasks.sortedBy { it.priority }.toMutableList()

        data class TaskOutcome(
            val task: SwarmTask,
            val result: String,
            val error: String? = null
        )

        suspend fun runWorker(task: SwarmTask): TaskOutcome {
            send(SwarmEvent.TaskStarted(task))

            val dependencyContext = compactDependencyContext(task, completedTasks)
            val budget = workerBudgets[task.id] ?: TeamExecutionPolicy.budgetFor(task)
            val workerPrompt = buildString {
                appendLine("## Assigned Team Task")
                appendLine(task.description)
                appendLine()
                appendLine("## Runtime")
                appendLine("Device time: $runtimeClock")
                appendLine("Active workspace: $scopePath")
                appendLine()
                appendLine("## Worker budget")
                appendLine("- Max iterations: ${budget.maxIterations}")
                appendLine("- Token ceiling: ${budget.tokenBudget}")
                appendLine("- Budget class: ${budget.reason}; finish as soon as evidence is sufficient.")
                appendLine()
                appendLine("## Execution contract")
                appendLine("- Execute this assigned task directly; do not redo team decomposition.")
                appendLine("- Batch independent tool calls in the same model turn whenever possible.")
                appendLine("- Stop probing a failed backend after the failure clearly states it is unavailable; pivot to a viable tool once.")
                appendLine("- Do not use shell/Python merely for trivial current-date arithmetic; the runtime clock above is authoritative for this run.")
                appendLine("- Keep tool observations focused. Return the evidence/result needed by the parent task, not a second giant report.")
                appendLine("- Never claim verification without concrete evidence. If verification is incomplete, state exactly what remains unverified.")
                appendLine("- Do not spend remaining budget simply because it exists. Stop when the assigned finish condition is satisfied.")
                if (godModeEnabled) {
                    appendLine("- God Mode is enabled, but privileged access is not guaranteed; trust actual tool outcomes.")
                }
                if (dependencyContext.isNotBlank()) {
                    appendLine()
                    appendLine("## Compact dependency handoff")
                    appendLine(dependencyContext)
                }
                appendLine()
                appendLine("## Original objective (for alignment only)")
                appendLine(userMessage.take(1_500))
            }

            val mcpRegistry = if (task.needsConnectedTools) {
                com.omnidev.workspace.OmniDevApp.instance.mcpRegistry
            } else null

            val workerPipeline = AgentPipeline(
                toolManager = toolManager,
                mcpRegistry = mcpRegistry,
                completionProvider = completionProvider,
                streamingCompletionProvider = streamingCompletionProvider,
                config = TEAM_WORKER_CONFIG.copy(
                    maxIterations = budget.maxIterations,
                    tokenBudget = budget.tokenBudget,
                    maxRepeatedToolCalls = budget.repeatedToolLimit
                ),
                apiKeyRepository = apiKeyRepository,
                memoryManager = memoryManager,
                smartLearningBridge = smartLearningBridge,
                analyticsRepository = analyticsRepository
            )

            var taskResult = ""
            var taskError: String? = null

            workerPipeline.execute(
                userMessage = workerPrompt,
                modelId = workerModelId,
                scopePath = scopePath,
                enableDeepThinking = enableDeepThinking,
                workerPersona = task.requiredPersona.takeIf { it.isNotBlank() },
                toolAccessMode = "ON_DEMAND"
            ).collect { event ->
                when (event) {
                    is AgentEvent.FinalAnswer -> taskResult = event.content
                    is AgentEvent.Error -> taskError = event.message
                    is AgentEvent.StreamChunk -> send(SwarmEvent.WorkerStreamChunk(task, event.delta))
                    is AgentEvent.ToolExecution -> send(SwarmEvent.WorkerToolUse(task, event.toolName, event.arguments))
                    is AgentEvent.ToolResult -> send(SwarmEvent.WorkerToolResult(task, event.toolName, event.output, event.isError))
                    is AgentEvent.Thinking -> send(SwarmEvent.WorkerThinking(task, event.iteration))
                    is AgentEvent.ThinkingBlock -> send(SwarmEvent.WorkerThinkingBlock(task, event.content))
                    is AgentEvent.TokenUsageUpdate -> {
                        observedTeamTokens.addAndGet(event.iterationTokens.coerceAtLeast(0))
                        send(
                            SwarmEvent.WorkerTokenUsage(
                                task = task,
                                totalTokens = event.totalTokens,
                                budget = event.budget,
                                iterationTokens = event.iterationTokens
                            )
                        )
                    }
                    is AgentEvent.PhaseChanged -> send(SwarmEvent.WorkerPhaseChanged(task, event.phase.name, event.detail))
                    else -> Unit
                }
            }

            if (taskError == null && taskResult.isBlank()) {
                taskError = "Worker ended without a final response."
            }
            return if (taskError != null) {
                val error = if (taskResult.isNotBlank()) {
                    "$taskError\n[Partial output]: ${taskResult.take(4_000)}"
                } else taskError!!
                TaskOutcome(task, result = "[FAILED] $error", error = error)
            } else {
                TaskOutcome(task, result = taskResult)
            }
        }

        suspend fun acceptOutcome(outcome: TaskOutcome) {
            if (outcome.error != null) {
                failedTasks[outcome.task.id] = outcome.error
                // Keep partial/failure evidence for synthesis, but failedTasks remains the source
                // of truth for dependency blocking.
                completedTasks[outcome.task.id] = outcome.result
                send(SwarmEvent.TaskFailed(outcome.task, outcome.error))
            } else {
                completedTasks[outcome.task.id] = outcome.result
                send(SwarmEvent.TaskCompleted(outcome.task, outcome.result))
            }
        }

        while (remaining.isNotEmpty()) {
            val permanentlyBlocked = remaining.filter { task ->
                task.dependencies.any { dep -> dep in skippedTasks || dep in failedTasks }
            }
            permanentlyBlocked.forEach { task ->
                remaining.remove(task)
                val blockedDeps = task.dependencies.filter { it in skippedTasks || it in failedTasks }
                val reason = "Blocked by failed/skipped dependency: $blockedDeps"
                skippedTasks[task.id] = reason
                send(SwarmEvent.TaskSkipped(task, reason))
            }

            val readyNow = remaining.filter { task ->
                task.dependencies.all { dep -> dep in completedTasks && dep !in failedTasks }
            }
            if (readyNow.isEmpty()) {
                if (remaining.isNotEmpty()) {
                    remaining.toList().forEach { task ->
                        val reason = "Dependency cycle or unresolved dependency"
                        skippedTasks[task.id] = reason
                        send(SwarmEvent.TaskSkipped(task, reason))
                    }
                    remaining.clear()
                }
                break
            }
            remaining.removeAll(readyNow)

            // Planner parallelSafe is advisory. The local policy re-checks mutation/resource
            // conflicts and produces waves where each inner list can safely run concurrently.
            val waves = TeamExecutionPolicy.buildExecutionWaves(
                readyTasks = readyNow,
                maxParallelWorkers = MAX_PARALLEL_WORKERS
            )
            for (wave in waves) {
                if (wave.size <= 1) {
                    wave.firstOrNull()?.let { acceptOutcome(runWorker(it)) }
                } else {
                    val dispatcher = Dispatchers.Default.limitedParallelism(MAX_PARALLEL_WORKERS)
                    val outcomes = coroutineScope {
                        wave.map { task -> async(dispatcher) { runWorker(task) } }.awaitAll()
                    }
                    outcomes.forEach { acceptOutcome(it) }
                }
            }
        }

        val summaryContent = buildSynthesisContext(completedTasks, failedTasks, skippedTasks)

        val synthesisRequest = CompletionRequest(
            modelId = orchestratorModelId,
            messages = listOf(
                ChatMessage(role = MessageRole.USER, content = userMessage),
                ChatMessage(role = MessageRole.ASSISTANT, content = "Team evidence:\n\n$summaryContent"),
                ChatMessage(
                    role = MessageRole.USER,
                    content = "Synthesize the evidence into the final answer for my original request. Do not invent verification that the workers did not perform."
                )
            ),
            systemPrompt = SYNTHESIS_PROMPT.trimIndent(),
            maxTokens = minOf(orchestratorModel.maxOutputTokens, 8_192),
            enableThinking = enableDeepThinking && orchestratorModel.supportsThinking,
            targetContext = scopePath,
            apiKey = orchestratorApiKey
        )

        val synthesisResponse = try {
            callWithRateLimitRetry(synthesisRequest)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ModeOutcomeLearner.recordOutcome(
                userRequest = userMessage,
                mode = OmniMode.SWARM,
                outcome = ModeOutcomeLearner.Outcome.FAILURE,
                iterations = tasks.size,
                durationMs = (System.currentTimeMillis() - teamStartedAt).coerceAtLeast(0L),
                tokens = observedTeamTokens.get().takeIf { it > 0 }
            )
            send(SwarmEvent.Error("Synthesis failed: ${e.message}"))
            return@channelFlow
        }
        observedTeamTokens.addAndGet(synthesisResponse.tokensUsed?.totalTokens ?: 0)

        val taskFailures = failedTasks.size + skippedTasks.size
        ModeOutcomeLearner.recordOutcome(
            userRequest = userMessage,
            mode = OmniMode.SWARM,
            outcome = if (taskFailures == 0) {
                ModeOutcomeLearner.Outcome.SUCCESS
            } else {
                ModeOutcomeLearner.Outcome.FAILURE
            },
            iterations = tasks.size,
            durationMs = (System.currentTimeMillis() - teamStartedAt).coerceAtLeast(0L),
            tokens = observedTeamTokens.get().takeIf { it > 0 },
            verified = taskFailures == 0 && tasks.any {
                IntentClassifier.analyze(it.description).verificationIntent >= 0.45f
            }
        )

        send(
            SwarmEvent.Completed(
                summary = synthesisResponse.content,
                tasksCompleted = completedTasks.size - failedTasks.size,
                tasksFailed = taskFailures
            )
        )
    }

    /**
     * One logical orchestration call may retry rate limits, but every actual provider attempt
     * is recorded so request analytics reflects what really happened.
     */
    private suspend fun callWithRateLimitRetry(
        request: CompletionRequest,
        maxRetries: Int = 5,
        baseDelayMs: Long = 15_000L
    ): CompletionResponse {
        var attempt = 0
        while (true) {
            val attemptStarted = System.currentTimeMillis()
            try {
                val response = completionProvider(request)
                recordOrchestratorAnalytics(request, response, attemptStarted, isError = false)
                return response
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                recordOrchestratorAnalytics(request, null, attemptStarted, isError = true)
                val isRateLimit = e is java.io.IOException &&
                    (e.message?.contains("Rate limit exceeded", ignoreCase = true) == true ||
                        e.message?.contains("429", ignoreCase = true) == true)
                if (isRateLimit && attempt < maxRetries) {
                    val delayMs = min(baseDelayMs * (attempt + 1), 60_000L)
                    attempt++
                    delay(delayMs)
                } else {
                    throw e
                }
            }
        }
    }

    private suspend fun recordOrchestratorAnalytics(
        request: CompletionRequest,
        response: CompletionResponse?,
        startedAtMs: Long,
        isError: Boolean
    ) {
        val repo = analyticsRepository ?: return
        try {
            val model = ModelRegistry.findModelById(request.modelId)
            val usage = response?.tokensUsed
            val inputTokens = usage?.promptTokens ?: 0
            val outputTokens = usage?.completionTokens ?: 0
            val cost = com.omnidev.workspace.data.repository.DynamicPricingManager()
                .calculateCost(request.modelId, inputTokens, outputTokens)
            repo.recordTokenUsage(
                modelId = request.modelId,
                provider = model?.provider,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                costUsd = cost,
                latencyMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L),
                isError = isError
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Telemetry must never break orchestration.
        }
    }

    private fun parseTasks(responseContent: String, originalUserMessage: String): List<SwarmTask> {
        return try {
            val start = responseContent.indexOf('[')
            val end = responseContent.lastIndexOf(']')
            require(start >= 0 && end > start) { "No JSON task array found" }
            val tasks = json.decodeFromString<List<SwarmTask>>(responseContent.substring(start, end + 1))
            require(tasks.isNotEmpty()) { "Empty task list" }
            tasks
        } catch (_: Exception) {
            // Graceful fallback: execute the user's real objective, not malformed planner prose.
            listOf(
                SwarmTask(
                    id = "direct-worker",
                    description = originalUserMessage.take(2_000),
                    priority = 1,
                    requiredPersona = "General Specialist",
                    parallelSafe = false,
                    needsConnectedTools = false
                )
            )
        }
    }

    private fun validateTasks(tasks: List<SwarmTask>): String? {
        if (tasks.isEmpty()) return "Orchestrator produced no actionable sub-tasks."
        if (tasks.size > MAX_SUBTASKS) return "Too many sub-tasks (${tasks.size}); maximum is $MAX_SUBTASKS."
        val ids = tasks.map { it.id }
        if (ids.any { it.isBlank() } || ids.distinct().size != ids.size) {
            return "Invalid task plan: task IDs must be non-empty and unique."
        }
        if (tasks.any { task -> task.dependencies.any { it !in ids || it == task.id } }) {
            return "Invalid task plan: every dependency must reference another task."
        }
        val resolved = mutableSetOf<String>()
        repeat(tasks.size) {
            tasks.filter { task -> task.dependencies.all(resolved::contains) }.forEach { resolved += it.id }
        }
        return if (resolved.size == tasks.size) null else "Invalid task plan: circular dependencies."
    }

    private fun compactDependencyContext(
        task: SwarmTask,
        completedTasks: Map<String, String>
    ): String {
        if (task.dependencies.isEmpty()) return ""
        var remaining = MAX_HANDOFF_CHARS_TOTAL
        val parts = mutableListOf<String>()
        for (dependency in task.dependencies) {
            if (remaining <= 0) break
            val result = completedTasks[dependency] ?: continue
            val allowed = minOf(MAX_HANDOFF_CHARS_PER_DEPENDENCY, remaining)
            val compact = compactText(result, allowed)
            parts += "[$dependency]\n$compact"
            remaining -= compact.length
        }
        return parts.joinToString("\n\n")
    }

    private fun buildSynthesisContext(
        completedTasks: Map<String, String>,
        failedTasks: Map<String, String>,
        skippedTasks: Map<String, String>
    ): String {
        val out = StringBuilder()
        completedTasks.forEach { (id, result) ->
            if (out.length >= MAX_SYNTHESIS_CONTEXT_CHARS) return@forEach
            val status = if (id in failedTasks) "FAILED" else "SUCCESS"
            val remaining = MAX_SYNTHESIS_CONTEXT_CHARS - out.length
            val bodyLimit = minOf(MAX_SYNTHESIS_CHARS_PER_TASK, remaining)
            out.appendLine("## $id ($status)")
            out.appendLine(compactText(result, bodyLimit))
            out.appendLine()
        }
        if (skippedTasks.isNotEmpty() && out.length < MAX_SYNTHESIS_CONTEXT_CHARS) {
            out.appendLine("## Skipped tasks")
            skippedTasks.forEach { (id, reason) -> out.appendLine("- $id: $reason") }
        }
        return out.toString().take(MAX_SYNTHESIS_CONTEXT_CHARS)
    }

    private fun compactText(value: String, maxChars: Int): String {
        if (value.length <= maxChars) return value
        if (maxChars <= 80) return value.take(maxChars)
        val headSize = (maxChars * 3) / 4
        val tailSize = maxChars - headSize - 45
        return buildString(maxChars) {
            append(value.take(headSize))
            append("\n[...handoff compacted...]\n")
            append(value.takeLast(tailSize.coerceAtLeast(0)))
        }.take(maxChars)
    }

    private fun currentRuntimeClock(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        return "${formatter.format(Date())} (${TimeZone.getDefault().id})"
    }
}

sealed class SwarmEvent {
    data object PlanningStarted : SwarmEvent()
    data class PlanCompleted(val tasks: List<SwarmTask>) : SwarmEvent()
    data class TaskStarted(val task: SwarmTask) : SwarmEvent()
    data class TaskCompleted(val task: SwarmTask, val result: String) : SwarmEvent()
    data class TaskFailed(val task: SwarmTask, val error: String) : SwarmEvent()
    data class TaskSkipped(val task: SwarmTask, val reason: String) : SwarmEvent()
    data class WorkerToolUse(
        val task: SwarmTask,
        val toolName: String,
        val arguments: Map<String, String>
    ) : SwarmEvent()
    data class WorkerToolResult(
        val task: SwarmTask,
        val toolName: String,
        val output: String,
        val isError: Boolean
    ) : SwarmEvent()
    data class WorkerThinking(val task: SwarmTask, val iteration: Int) : SwarmEvent()
    data class WorkerThinkingBlock(val task: SwarmTask, val content: String) : SwarmEvent()
    data class WorkerTokenUsage(
        val task: SwarmTask,
        /** Cumulative tokens consumed by this worker run. */
        val totalTokens: Int,
        val budget: Int?,
        /** Tokens consumed by only the most recent model request. */
        val iterationTokens: Int = 0
    ) : SwarmEvent()
    data class WorkerPhaseChanged(
        val task: SwarmTask,
        val phase: String,
        val detail: String?
    ) : SwarmEvent()
    data class WorkerStreamChunk(val task: SwarmTask, val delta: String) : SwarmEvent()
    /** Kept for source compatibility with existing UI; new runs no longer emit fake iteration-99 synthesis events. */
    data object SynthesisStarted : SwarmEvent()
    data class Completed(
        val summary: String,
        val tasksCompleted: Int,
        val tasksFailed: Int
    ) : SwarmEvent()
    data class Error(val message: String) : SwarmEvent()
}

@kotlinx.serialization.Serializable
data class SwarmTask(
    val id: String,
    val description: String,
    val priority: Int = 0,
    val dependencies: List<String> = emptyList(),
    val status: SwarmTaskStatus = SwarmTaskStatus.PENDING,
    val requiredPersona: String = "",
    /** True only when the task is read-only / research and safe to overlap with peers. */
    val parallelSafe: Boolean = true,
    /** Opt-in to MCP/connected-service schemas; false avoids needless schema tokens for ordinary tasks. */
    val needsConnectedTools: Boolean = false
)

@kotlinx.serialization.Serializable
enum class SwarmTaskStatus {
    PENDING, IN_PROGRESS, COMPLETED, FAILED
}
