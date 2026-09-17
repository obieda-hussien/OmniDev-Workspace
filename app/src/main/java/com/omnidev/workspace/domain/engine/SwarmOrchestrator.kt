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
 * Token-bounded, dependency-aware multi-agent coordinator.
 *
 * Planner output is untrusted input. The runtime validates identities/references, removes semantic
 * duplicates, caps oversized plans without cutting prerequisite chains, re-checks mutation safety,
 * and allocates one hard worker budget before any worker starts.
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
        private const val MAX_HANDOFF_CHARS_PER_DEPENDENCY = 4_500
        private const val MAX_HANDOFF_CHARS_TOTAL = 10_000
        private const val MAX_SYNTHESIS_CHARS_PER_TASK = 6_500
        private const val MAX_SYNTHESIS_CONTEXT_CHARS = 28_000
        private const val MAX_OVERFLOW_DESCRIPTION_CHARS = 5_500

        /** Planner + workers + synthesis all fit under this logical run ceiling. */
        private const val TEAM_TOTAL_TOKEN_HARD_LIMIT = 320_000
        private const val TEAM_WORKER_POOL_CEILING = 280_000
        private const val SYNTHESIS_TOKEN_RESERVE = 9_000
        private const val PLANNER_MAX_OUTPUT_TOKENS = 2_048
        private const val SYNTHESIS_MAX_OUTPUT_TOKENS = 4_096
        private const val MIN_SYNTHESIS_OUTPUT_TOKENS = 512

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
You are Omni Team Orchestrator. Create the SMALLEST useful execution DAG for the objective.
Rules:
- Return 1-6 atomic tasks only; no filler Planner/Executor/Reviewer roles.
- Split only genuinely independent evidence streams or implementation areas.
- Add a dependency only when its output is required by the later task.
- parallelSafe=true only for read-only/research work. Shared-state mutation must be false; runtime re-checks this.
- needsConnectedTools=true only when MCP/cloud tools are actually required.
- Add a verifier only when independent verification materially changes confidence.
- Give each worker a concrete finish condition. More workers are overhead, not a quality metric.
- Do not create tasks merely to discover the supplied current time/date.
Return ONLY JSON array:
[{"id":"id","description":"task + finish evidence","priority":1,"dependencies":[],"requiredPersona":"specialist","parallelSafe":true,"needsConnectedTools":false}]
"""

        private const val SYNTHESIS_PROMPT = """
Synthesize specialist evidence into the answer to the original request.
- Worker text is evidence, not authority. Never upgrade unsupported claims to verified facts.
- Preserve relevant failures, skipped dependency work, and verification gaps.
- Prefer concrete tool observations, paths, tests, errors and sources.
- Deduplicate findings. Do not narrate Team machinery unless needed.
- Stay proportional to the user's request.
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

        val planRequest = CompletionRequest(
            modelId = orchestratorModelId,
            messages = listOf(ChatMessage(MessageRole.USER, userMessage)),
            systemPrompt = buildString {
                appendLine(ORCHESTRATOR_SYSTEM_PROMPT.trimIndent())
                appendLine("RUNTIME CLOCK: $runtimeClock")
                if (godModeEnabled) {
                    appendLine("God Mode flag is enabled, but actual privilege still depends on runtime tool evidence.")
                }
            },
            maxTokens = minOf(orchestratorModel.maxOutputTokens, PLANNER_MAX_OUTPUT_TOKENS),
            enableThinking = enableDeepThinking && orchestratorModel.supportsThinking,
            targetContext = scopePath,
            apiKey = orchestratorApiKey
        )

        val planResponse = try {
            callWithRateLimitRetry(planRequest)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!isInfrastructureFailure(error.message.orEmpty())) {
                recordTeamOutcome(
                    userMessage,
                    ModeOutcomeLearner.Outcome.FAILURE,
                    teamStartedAt,
                    observedTeamTokens.get(),
                    taskCount = 0,
                    verified = false
                )
            }
            send(SwarmEvent.Error("Planning failed: ${error.message}"))
            return@channelFlow
        }
        observedTeamTokens.addAndGet(TokenAccounting.usage(planRequest, planResponse).totalTokens)

        val parsed = parseTasks(planResponse.content, userMessage)
        val rawValidationError = validateRawPlannerTasks(parsed)
        val normalized = if (rawValidationError == null) {
            TeamExecutionPolicy.normalizePlan(parsed).tasks
        } else {
            listOf(directWorker(userMessage))
        }
        val bounded = boundPlan(normalized, userMessage)
        val runtimeCandidate = bounded.map { task ->
            val runtime = TeamExecutionPolicy.classify(task)
            task.copy(parallelSafe = runtime.effectiveParallelSafe)
        }
        val runtimeTasks = if (validateTasks(runtimeCandidate) == null) {
            runtimeCandidate
        } else {
            listOf(directWorker(userMessage))
        }

        val remainingRunBudget = (
            TEAM_TOTAL_TOKEN_HARD_LIMIT - observedTeamTokens.get() - SYNTHESIS_TOKEN_RESERVE
            ).coerceAtLeast(0)
        val workerPool = minOf(TEAM_WORKER_POOL_CEILING, remainingRunBudget)
        val allocation = TeamBudgetAllocator.allocate(runtimeTasks, workerPool)
        if (runtimeTasks.isNotEmpty() &&
            (allocation.budgets.size != runtimeTasks.size || allocation.allocatedTotal > workerPool)
        ) {
            recordTeamOutcome(
                userMessage,
                ModeOutcomeLearner.Outcome.ABANDONED,
                teamStartedAt,
                observedTeamTokens.get(),
                runtimeTasks.size,
                false
            )
            send(SwarmEvent.Error("Team token pool is too small for a safe worker allocation."))
            return@channelFlow
        }

        // Expose runtime truth, not planner claims, to UI/adaptive routing.
        send(SwarmEvent.PlanCompleted(runtimeTasks))

        val completed = linkedMapOf<String, String>()
        val failed = linkedMapOf<String, String>()
        val skipped = linkedMapOf<String, String>()
        val infrastructureFailed = mutableSetOf<String>()
        val infrastructureSkipped = mutableSetOf<String>()
        val remaining = runtimeTasks.sortedWith(compareBy<SwarmTask> { it.priority }.thenBy { it.id })
            .toMutableList()

        data class TaskOutcome(
            val task: SwarmTask,
            val result: String,
            val error: String? = null,
            val infrastructureBlocked: Boolean = false
        )

        suspend fun runWorker(task: SwarmTask): TaskOutcome {
            send(SwarmEvent.TaskStarted(task))
            val budget = allocation.budgets[task.id] ?: TeamExecutionPolicy.budgetFor(task)
            val dependencyContext = compactDependencyContext(task, completed)
            val workerPrompt = buildWorkerPrompt(
                task = task,
                dependencyContext = dependencyContext,
                originalObjective = userMessage,
                runtimeClock = runtimeClock,
                scopePath = scopePath,
                budget = budget,
                godModeEnabled = godModeEnabled
            )

            val workerPipeline = AgentPipeline(
                toolManager = toolManager,
                mcpRegistry = if (task.needsConnectedTools) {
                    com.omnidev.workspace.OmniDevApp.instance.mcpRegistry
                } else null,
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
                workerPersona = task.requiredPersona.takeIf(String::isNotBlank),
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
                    is AgentEvent.PhaseChanged -> send(
                        SwarmEvent.WorkerPhaseChanged(task, event.phase.name, event.detail)
                    )
                    else -> Unit
                }
            }

            if (taskError == null && taskResult.isBlank()) {
                taskError = "Worker ended without a final response."
            }
            if (taskError == null) return TaskOutcome(task, taskResult)

            val error = taskError.orEmpty()
            val partial = taskResult.takeIf(String::isNotBlank)?.let {
                "\n[Partial evidence]\n${TeamHandoffCompressor.compact(it, 3_000)}"
            }.orEmpty()
            return TaskOutcome(
                task = task,
                result = "[FAILED] $error$partial",
                error = error,
                infrastructureBlocked = isInfrastructureFailure(error)
            )
        }

        suspend fun accept(outcome: TaskOutcome) {
            completed[outcome.task.id] = outcome.result
            if (outcome.error != null) {
                failed[outcome.task.id] = outcome.error
                if (outcome.infrastructureBlocked) infrastructureFailed += outcome.task.id
                send(SwarmEvent.TaskFailed(outcome.task, outcome.error))
            } else {
                send(SwarmEvent.TaskCompleted(outcome.task, outcome.result))
            }
        }

        while (remaining.isNotEmpty()) {
            if (observedTeamTokens.get() >= TEAM_TOTAL_TOKEN_HARD_LIMIT - SYNTHESIS_TOKEN_RESERVE) {
                remaining.toList().forEach { task ->
                    val reason = "Team token ceiling reached before this task could start"
                    skipped[task.id] = reason
                    send(SwarmEvent.TaskSkipped(task, reason))
                }
                remaining.clear()
                break
            }

            val blocked = remaining.filter { task ->
                task.dependencies.any { dependency -> dependency in failed || dependency in skipped }
            }
            blocked.forEach { task ->
                remaining.remove(task)
                val dependencies = task.dependencies.filter { it in failed || it in skipped }
                val reason = "Blocked by failed/skipped dependency: $dependencies"
                skipped[task.id] = reason
                if (dependencies.any { it in infrastructureFailed || it in infrastructureSkipped }) {
                    infrastructureSkipped += task.id
                }
                send(SwarmEvent.TaskSkipped(task, reason))
            }

            val ready = remaining.filter { task ->
                task.dependencies.all { dependency -> dependency in completed && dependency !in failed }
            }
            if (ready.isEmpty()) {
                remaining.toList().forEach { task ->
                    val reason = "Dependency cycle or unresolved dependency"
                    skipped[task.id] = reason
                    send(SwarmEvent.TaskSkipped(task, reason))
                }
                remaining.clear()
                break
            }
            remaining.removeAll(ready)

            val waves = TeamExecutionPolicy.buildExecutionWaves(ready, MAX_PARALLEL_WORKERS)
            for (wave in waves) {
                if (wave.size == 1) {
                    accept(runWorker(wave.first()))
                } else {
                    val dispatcher = Dispatchers.Default.limitedParallelism(MAX_PARALLEL_WORKERS)
                    coroutineScope {
                        wave.map { task -> async(dispatcher) { runWorker(task) } }.awaitAll()
                    }.forEach { accept(it) }
                }
            }
        }

        val successfulCount = completed.keys.count { it !in failed }
        val totalFailures = failed.size + skipped.size

        val finalSummary = when {
            runtimeTasks.size == 1 && totalFailures == 0 -> completed[runtimeTasks.single().id].orEmpty()
            successfulCount == 0 -> deterministicFailureSummary(failed, skipped)
            else -> synthesize(
                userMessage = userMessage,
                completed = completed,
                failed = failed,
                skipped = skipped,
                orchestratorModelId = orchestratorModelId,
                orchestratorApiKey = orchestratorApiKey,
                modelMaxOutput = orchestratorModel.maxOutputTokens,
                scopePath = scopePath,
                enableThinking = enableDeepThinking && orchestratorModel.supportsThinking,
                observedTokens = observedTeamTokens,
                onStart = { send(SwarmEvent.SynthesisStarted) }
            ) ?: deterministicEvidenceSummary(completed, failed, skipped)
        }

        val rootFailures = failed.keys
        val onlyInfrastructureFailure = rootFailures.isNotEmpty() &&
            rootFailures.all { it in infrastructureFailed } &&
            skipped.keys.all { it in infrastructureSkipped }

        if (!onlyInfrastructureFailure) {
            recordTeamOutcome(
                userRequest = userMessage,
                outcome = if (totalFailures == 0) {
                    ModeOutcomeLearner.Outcome.SUCCESS
                } else ModeOutcomeLearner.Outcome.FAILURE,
                startedAt = teamStartedAt,
                tokens = observedTeamTokens.get(),
                taskCount = runtimeTasks.size,
                verified = totalFailures == 0 && runtimeTasks.any {
                    IntentClassifier.analyze(it.description).verificationIntent >= 0.45f
                }
            )
        }

        send(
            SwarmEvent.Completed(
                summary = finalSummary,
                tasksCompleted = successfulCount,
                tasksFailed = totalFailures
            )
        )
    }

    /**
     * If a planner ignored the 1-6 instruction, preserve five dependency-safe branches and fold
     * everything else into one serial overflow worker. No user requirement is silently discarded.
     */
    private fun boundPlan(tasks: List<SwarmTask>, originalObjective: String): List<SwarmTask> {
        if (tasks.isEmpty()) return listOf(directWorker(originalObjective))
        if (tasks.size <= MAX_SUBTASKS) return tasks

        val capped = TeamExecutionPolicy.capPlan(tasks, MAX_SUBTASKS - 1)
        val selected = capped.tasks
        val selectedIds = selected.mapTo(linkedSetOf()) { it.id }
        val dropped = tasks.filter { it.id in capped.droppedTaskIds }
        if (dropped.isEmpty()) return selected.take(MAX_SUBTASKS)

        val overflowId = generateSequence("team-overflow") { "$it-x" }
            .first { candidate -> tasks.none { it.id == candidate } }
        val dependencyIds = dropped
            .flatMap { it.dependencies }
            .filter { it in selectedIds }
            .distinct()
        val description = buildString {
            appendLine("Complete the remaining planner objectives serially inside this one worker.")
            appendLine("Preserve their logical order and verify each completed part:")
            dropped.sortedWith(compareBy<SwarmTask> { it.priority }.thenBy { it.id }).forEach { task ->
                append("- [").append(task.id).append("] ")
                    .appendLine(task.description.take(700))
            }
        }.take(MAX_OVERFLOW_DESCRIPTION_CHARS)

        val overflow = SwarmTask(
            id = overflowId,
            description = description,
            priority = dropped.minOfOrNull { it.priority } ?: Int.MAX_VALUE,
            dependencies = dependencyIds,
            requiredPersona = "Integration Specialist",
            parallelSafe = false,
            needsConnectedTools = dropped.any { it.needsConnectedTools }
        )
        return selected + overflow
    }

    private fun directWorker(originalObjective: String): SwarmTask = SwarmTask(
        id = "direct-worker",
        description = originalObjective.take(2_500),
        priority = 1,
        requiredPersona = "General Specialist",
        parallelSafe = false,
        needsConnectedTools = false
    )

    private fun buildWorkerPrompt(
        task: SwarmTask,
        dependencyContext: String,
        originalObjective: String,
        runtimeClock: String,
        scopePath: String,
        budget: TeamExecutionPolicy.WorkerBudget,
        godModeEnabled: Boolean
    ): String = buildString {
        appendLine("## Assigned Team Task")
        appendLine(task.description)
        appendLine("\n## Runtime")
        appendLine("Time: $runtimeClock")
        appendLine("Workspace: $scopePath")
        appendLine("Budget: ${budget.maxIterations} iterations / ${budget.tokenBudget} tokens (${budget.reason})")
        appendLine("\n## Contract")
        appendLine("Execute only this task. Batch safe reads, stop when finish evidence is sufficient, and do not redo team planning.")
        appendLine("Pivot once when a backend is clearly blocked; do not probe it repeatedly. Never claim verification without concrete evidence.")
        appendLine("Return concise evidence useful to the parent: changes, paths, tests, errors, decisions, sources, and remaining risk.")
        if (godModeEnabled) appendLine("God Mode flag does not guarantee privilege; trust actual tool outcomes.")
        if (dependencyContext.isNotBlank()) {
            appendLine("\n## Dependency evidence")
            appendLine(dependencyContext)
        }
        appendLine("\n## Original objective")
        appendLine(originalObjective.take(1_200))
    }

    private suspend fun synthesize(
        userMessage: String,
        completed: Map<String, String>,
        failed: Map<String, String>,
        skipped: Map<String, String>,
        orchestratorModelId: String,
        orchestratorApiKey: String?,
        modelMaxOutput: Int,
        scopePath: String,
        enableThinking: Boolean,
        observedTokens: AtomicInteger,
        onStart: suspend () -> Unit
    ): String? {
        val evidence = buildSynthesisContext(completed, failed, skipped)
        val provisional = CompletionRequest(
            modelId = orchestratorModelId,
            messages = listOf(
                ChatMessage(MessageRole.USER, userMessage),
                ChatMessage(MessageRole.ASSISTANT, "Team evidence:\n$evidence"),
                ChatMessage(MessageRole.USER, "Produce the final answer from the evidence above.")
            ),
            systemPrompt = SYNTHESIS_PROMPT.trimIndent(),
            maxTokens = minOf(modelMaxOutput, SYNTHESIS_MAX_OUTPUT_TOKENS),
            enableThinking = enableThinking,
            targetContext = scopePath,
            apiKey = orchestratorApiKey
        )
        val remaining = (TEAM_TOTAL_TOKEN_HARD_LIMIT - observedTokens.get()).coerceAtLeast(0)
        val estimatedInput = TokenAccounting.estimateInputTokens(provisional)
        if (remaining <= estimatedInput + MIN_SYNTHESIS_OUTPUT_TOKENS) return null

        val outputBudget = minOf(
            provisional.maxTokens,
            (remaining - estimatedInput).coerceAtLeast(MIN_SYNTHESIS_OUTPUT_TOKENS)
        )
        val request = if (outputBudget == provisional.maxTokens) provisional else provisional.copy(maxTokens = outputBudget)

        return try {
            onStart()
            val response = callWithRateLimitRetry(request, maxRetries = 2)
            observedTokens.addAndGet(TokenAccounting.usage(request, response).totalTokens)
            response.content.takeIf(String::isNotBlank)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun compactDependencyContext(
        task: SwarmTask,
        completed: Map<String, String>
    ): String {
        if (task.dependencies.isEmpty()) return ""
        var remaining = MAX_HANDOFF_CHARS_TOTAL
        val parts = mutableListOf<String>()
        task.dependencies.forEach { dependency ->
            if (remaining <= 0) return@forEach
            val raw = completed[dependency] ?: return@forEach
            val limit = minOf(MAX_HANDOFF_CHARS_PER_DEPENDENCY, remaining)
            val compact = TeamHandoffCompressor.compact(raw, limit)
            if (compact.isNotBlank()) {
                parts += "[$dependency]\n$compact"
                remaining -= compact.length
            }
        }
        return parts.joinToString("\n\n")
    }

    private fun buildSynthesisContext(
        completed: Map<String, String>,
        failed: Map<String, String>,
        skipped: Map<String, String>
    ): String {
        val out = StringBuilder()
        completed.forEach { (id, raw) ->
            if (out.length >= MAX_SYNTHESIS_CONTEXT_CHARS) return@forEach
            val remaining = MAX_SYNTHESIS_CONTEXT_CHARS - out.length
            val bodyLimit = minOf(MAX_SYNTHESIS_CHARS_PER_TASK, remaining)
            out.appendLine("## $id (${if (id in failed) "FAILED" else "SUCCESS"})")
            out.appendLine(TeamHandoffCompressor.compact(raw, bodyLimit))
        }
        if (skipped.isNotEmpty() && out.length < MAX_SYNTHESIS_CONTEXT_CHARS) {
            out.appendLine("## Skipped")
            skipped.forEach { (id, reason) -> out.appendLine("- $id: $reason") }
        }
        return out.toString().take(MAX_SYNTHESIS_CONTEXT_CHARS)
    }

    private fun deterministicEvidenceSummary(
        completed: Map<String, String>,
        failed: Map<String, String>,
        skipped: Map<String, String>
    ): String = buildString {
        appendLine("Team execution evidence:")
        completed.forEach { (id, output) ->
            appendLine("\n[$id ${if (id in failed) "FAILED" else "DONE"}]")
            appendLine(TeamHandoffCompressor.compact(output, 2_500))
        }
        skipped.forEach { (id, reason) -> appendLine("\n[$id SKIPPED] $reason") }
    }.take(12_000)

    private fun deterministicFailureSummary(
        failed: Map<String, String>,
        skipped: Map<String, String>
    ): String = buildString {
        appendLine("The Team could not complete the objective.")
        failed.forEach { (id, error) -> appendLine("- $id failed: ${error.take(700)}") }
        skipped.forEach { (id, reason) -> appendLine("- $id skipped: ${reason.take(500)}") }
    }.take(8_000)

    private fun parseTasks(responseContent: String, originalUserMessage: String): List<SwarmTask> {
        return try {
            val start = responseContent.indexOf('[')
            val end = responseContent.lastIndexOf(']')
            require(start >= 0 && end > start) { "No JSON task array found" }
            val parsed = json.decodeFromString<List<SwarmTask>>(responseContent.substring(start, end + 1))
            require(parsed.isNotEmpty()) { "Empty task list" }
            parsed
        } catch (_: Exception) {
            listOf(directWorker(originalUserMessage))
        }
    }

    /** Validate references before normalization/capping so truncation cannot hide malformed IDs. */
    private fun validateRawPlannerTasks(tasks: List<SwarmTask>): String? {
        if (tasks.isEmpty()) return "Orchestrator produced no actionable tasks."
        val ids = tasks.map { it.id }
        if (ids.any(String::isBlank) || ids.distinct().size != ids.size) {
            return "Invalid Team plan: task IDs must be non-empty and unique."
        }
        if (tasks.any { task -> task.description.isBlank() }) {
            return "Invalid Team plan: every task needs a description."
        }
        if (tasks.any { task -> task.dependencies.any { it !in ids || it == task.id } }) {
            return "Invalid Team plan: every dependency must reference another task."
        }
        return null
    }

    private fun validateTasks(tasks: List<SwarmTask>): String? {
        if (tasks.isEmpty()) return "Orchestrator produced no actionable tasks."
        if (tasks.size > MAX_SUBTASKS) return "Too many tasks (${tasks.size}); maximum is $MAX_SUBTASKS."
        val ids = tasks.map { it.id }
        if (ids.any(String::isBlank) || ids.distinct().size != ids.size) {
            return "Invalid Team plan: task IDs must be non-empty and unique."
        }
        if (tasks.any { task -> task.dependencies.any { it !in ids || it == task.id } }) {
            return "Invalid Team plan: every dependency must reference another task."
        }
        val resolved = mutableSetOf<String>()
        repeat(tasks.size) {
            tasks.filter { it.id !in resolved && it.dependencies.all(resolved::contains) }
                .forEach { resolved += it.id }
        }
        return if (resolved.size == tasks.size) null else "Invalid Team plan: circular dependencies."
    }

    private suspend fun callWithRateLimitRetry(
        request: CompletionRequest,
        maxRetries: Int = 4,
        baseDelayMs: Long = 15_000L
    ): CompletionResponse {
        var attempt = 0
        while (true) {
            val started = System.currentTimeMillis()
            try {
                val response = completionProvider(request)
                recordOrchestratorAnalytics(request, response, started, false)
                return response
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recordOrchestratorAnalytics(request, null, started, true)
                val rateLimit = isRateLimit(error.message.orEmpty())
                if (!rateLimit || attempt >= maxRetries) throw error
                attempt++
                delay(min(baseDelayMs * attempt, 60_000L))
            }
        }
    }

    private suspend fun recordOrchestratorAnalytics(
        request: CompletionRequest,
        response: CompletionResponse?,
        startedAtMs: Long,
        isError: Boolean
    ) {
        val repository = analyticsRepository ?: return
        try {
            val model = ModelRegistry.findModelById(request.modelId)
            val usage = response?.let { TokenAccounting.usage(request, it) }
            val input = usage?.inputTokens ?: 0
            val output = usage?.outputTokens ?: 0
            repository.recordTokenUsage(
                modelId = request.modelId,
                provider = model?.provider,
                inputTokens = input,
                outputTokens = output,
                costUsd = com.omnidev.workspace.data.repository.DynamicPricingManager()
                    .calculateCost(request.modelId, input, output),
                latencyMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L),
                isError = isError
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Best effort.
        }
    }

    private fun recordTeamOutcome(
        userRequest: String,
        outcome: ModeOutcomeLearner.Outcome,
        startedAt: Long,
        tokens: Int,
        taskCount: Int,
        verified: Boolean
    ) {
        ModeOutcomeLearner.recordOutcome(
            userRequest = userRequest,
            mode = OmniMode.SWARM,
            outcome = outcome,
            iterations = taskCount,
            durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
            tokens = tokens.takeIf { it > 0 },
            verified = verified
        )
    }

    private fun isRateLimit(message: String): Boolean {
        val lower = message.lowercase()
        return "rate limit" in lower || "429" in lower || "too many requests" in lower
    }

    private fun isInfrastructureFailure(message: String): Boolean {
        val lower = message.lowercase()
        return listOf(
            "rate limit", "429", "quota", "api key", "unauthorized", "forbidden", "network",
            "timeout", "timed out", "connection", "dns", "service unavailable", "provider cooldown",
            "persistent infrastructure", "backend failure"
        ).any(lower::contains)
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
        val totalTokens: Int,
        val budget: Int?,
        val iterationTokens: Int = 0
    ) : SwarmEvent()
    data class WorkerPhaseChanged(val task: SwarmTask, val phase: String, val detail: String?) : SwarmEvent()
    data class WorkerStreamChunk(val task: SwarmTask, val delta: String) : SwarmEvent()
    data object SynthesisStarted : SwarmEvent()
    data class Completed(val summary: String, val tasksCompleted: Int, val tasksFailed: Int) : SwarmEvent()
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
    val parallelSafe: Boolean = true,
    val needsConnectedTools: Boolean = false
)

@kotlinx.serialization.Serializable
enum class SwarmTaskStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED }
