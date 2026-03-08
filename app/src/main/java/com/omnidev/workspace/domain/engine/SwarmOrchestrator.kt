package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.tools.ToolManager
import com.omnidev.workspace.registry.ModelRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json

/**
 * Swarm Orchestrator that decomposes complex user prompts into sub-tasks,
 * delegates each to Worker agents, and synthesizes the final result.
 *
 * The orchestration flow:
 * 1. **Plan**: The Orchestrator model analyzes the prompt and produces a task breakdown.
 * 2. **Delegate**: Each sub-task is executed by a Worker [AgentPipeline] instance.
 * 3. **Synthesize**: Worker results are collected and the Orchestrator produces a final summary.
 *
 * @param toolManager Shared [ToolManager] instance for all Worker agents.
 * @param completionProvider The AI API call abstraction, used for both Orchestrator and Workers.
 * @param apiKeyRepository Optional key store. When provided, the API key for the model's
 *        provider is injected into each [CompletionRequest] automatically.
 * @param memoryManager Optional long-term memory store passed to every Worker [AgentPipeline]
 *        so Workers can recall facts and persist observations across sub-task iterations.
 * @param streamingCompletionProvider Optional streaming variant of the completion provider.
 *        When provided, Workers stream text chunks in real-time via [SwarmEvent.WorkerStreamChunk].
 */
class SwarmOrchestrator(
    private val toolManager: ToolManager,
    private val completionProvider: suspend (CompletionRequest) -> CompletionResponse,
    private val apiKeyRepository: com.omnidev.workspace.data.repository.ApiKeyRepository? = null,
    private val memoryManager: com.omnidev.workspace.data.tools.MemoryManager? = null,
    private val streamingCompletionProvider: (suspend (CompletionRequest, suspend (String) -> Unit) -> CompletionResponse)? = null
) {

    companion object {
        /** Maximum sub-tasks the orchestrator can generate. */
        private const val MAX_SUBTASKS = 10

        /** System prompt for the Orchestrator (planner) role. */
        private const val ORCHESTRATOR_SYSTEM_PROMPT = """
You are Omni-Orchestrator, a Universal AI Manager.

Your job is to:
1. Analyze the user's request and determine the DOMAIN: Coding, Web Research, System/OS Control, or General Assistance.
2. Decompose the request into a prioritized list of independent or sequential sub-tasks.
3. For EACH sub-task, assign the most appropriate specialist worker persona.

Worker persona examples (choose the best fit per task):
- "Senior Android/Kotlin Developer" — for code, build, debugging tasks
- "Senior Web Researcher and News Analyst" — for search, research, summarization
- "System Administrator and DevOps Engineer" — for OS control, shell, deployment
- "General Assistant" — for writing, planning, Q&A, creative tasks
- "Data Analyst" — for processing data, creating reports
- "Security Engineer" — for vulnerability analysis, penetration testing concepts

Respond with a JSON array of task objects:
[
  {"id": "task-1", "description": "...", "priority": 1, "dependencies": [], "requiredPersona": "Senior Web Researcher and News Analyst"},
  {"id": "task-2", "description": "...", "priority": 2, "dependencies": ["task-1"], "requiredPersona": "Senior Android/Kotlin Developer"}
]

Keep task count reasonable (max 10). Merge trivial steps into larger tasks.
Do NOT include code — just planning, task descriptions, and persona assignments.
"""

        private const val SYNTHESIS_PROMPT = """
You are Omni-Orchestrator synthesizing the results of your specialist worker agents.
Review the completed sub-tasks and their outcomes below.

CRITICAL INSTRUCTIONS:
1. Format your response based ENTIRELY on the user's original intent and domain:
   - Web research / news → Present findings as a clear summary with key points.
   - Coding task → Describe changes made, files modified, and next steps.
   - OS/system task → Confirm actions taken (toggles, app launches, etc.).
   - General Q&A → Provide a direct, natural conversational answer.
2. Do NOT use a coding-specific template (e.g., "Files Created or Modified: None") for non-coding tasks.
3. If any sub-task failed or was skipped, you MUST explicitly state what failed and why.
4. Never claim overall success if sub-tasks failed.
"""
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Executes the full Swarm workflow: Plan → Delegate → Synthesize.
     *
     * @param userMessage The user's original complex request.
     * @param orchestratorModelId Model ID for the Orchestrator (planning) role.
     * @param workerModelId Model ID for the Worker (coding) role.
     * @param scopePath Active Target Context directory.
     * @param enableDeepThinking Whether workers should use extended thinking.
     * @return A [Flow] of [SwarmEvent]s for real-time progress updates.
     */
    fun execute(
        userMessage: String,
        orchestratorModelId: String,
        workerModelId: String,
        scopePath: String,
        enableDeepThinking: Boolean = false
    ): Flow<SwarmEvent> = channelFlow {
        send(SwarmEvent.PlanningStarted)

        // ── Phase 1: Plan ──
        val orchestratorModel = ModelRegistry.findModelById(orchestratorModelId)
            ?: run {
                send(SwarmEvent.Error("Unknown orchestrator model: $orchestratorModelId"))
                return@channelFlow
            }

        val orchestratorApiKey = apiKeyRepository?.getApiKey(orchestratorModel.provider)

        val planRequest = CompletionRequest(
            modelId = orchestratorModelId,
            messages = listOf(ChatMessage(role = MessageRole.USER, content = userMessage)),
            systemPrompt = ORCHESTRATOR_SYSTEM_PROMPT.trimIndent(),
            maxTokens = orchestratorModel.maxOutputTokens,
            enableThinking = enableDeepThinking && orchestratorModel.supportsThinking,
            targetContext = scopePath,
            apiKey = orchestratorApiKey
        )

        val planResponse: CompletionResponse
        try {
            planResponse = completionProvider(planRequest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(SwarmEvent.Error("Planning failed: ${e.message}"))
            return@channelFlow
        }

        // Parse sub-tasks from the orchestrator's response
        val tasks = parseTasks(planResponse.content)
        if (tasks.isEmpty()) {
            send(SwarmEvent.Error("Orchestrator produced no actionable sub-tasks."))
            return@channelFlow
        }
        if (tasks.size > MAX_SUBTASKS) {
            send(SwarmEvent.Error("Too many sub-tasks (${tasks.size}). Maximum is $MAX_SUBTASKS."))
            return@channelFlow
        }

        send(SwarmEvent.PlanCompleted(tasks))

        // ── Phase 2: Delegate (concurrent execution respecting dependency order) ──
        // Tasks are grouped into "waves" using a topological level sort:
        //   wave 0 = tasks with no dependencies
        //   wave 1 = tasks whose only dependencies are in wave 0, etc.
        // All tasks in the same wave are executed concurrently; waves are processed sequentially
        // so that results from wave N are available before wave N+1 begins.
        val completedTasks = mutableMapOf<String, String>()
        val failedTasks = mutableMapOf<String, String>()
        val skippedTasks = mutableMapOf<String, String>()

        val remaining = tasks.sortedBy { it.priority }.toMutableList()

        while (remaining.isNotEmpty()) {
            // Identify tasks whose dependencies are all already completed (or have none)
            val readyNow = remaining.filter { task ->
                task.dependencies.all { dep -> dep in completedTasks }
            }

            // Tasks that are blocked on a failed/skipped dependency are permanently unrunnable —
            // detect and evict them so they don't stall the loop forever.
            val permanentlyBlocked = remaining.filter { task ->
                task.dependencies.any { dep -> dep in failedTasks || dep in skippedTasks }
            }
            permanentlyBlocked.forEach { task ->
                remaining.remove(task)
                val blockedDeps = task.dependencies.filter { it in failedTasks || it in skippedTasks }
                val reason = "Blocked: dependencies $blockedDeps failed or were skipped"
                skippedTasks[task.id] = reason
                send(SwarmEvent.TaskSkipped(task, reason))
            }

            if (readyNow.isEmpty()) {
                // All remaining tasks are stuck in a dependency cycle or all blocked — abort.
                if (remaining.isNotEmpty()) {
                    remaining.forEach { task ->
                        val reason = "Dependency cycle or unresolvable dependency"
                        skippedTasks[task.id] = reason
                        send(SwarmEvent.TaskSkipped(task, reason))
                    }
                    remaining.clear()
                }
                break
            }

            // Remove the ready tasks from the pending list before starting them
            remaining.removeAll(readyNow)

            // Execute all ready tasks concurrently within a coroutineScope
            coroutineScope {
                val deferredResults = readyNow.map { task ->
                    async {
                        send(SwarmEvent.TaskStarted(task))

                        val dependencyContext = task.dependencies.mapNotNull { depId ->
                            completedTasks[depId]?.let { result -> "[$depId result]: $result" }
                        }.joinToString("\n")

                        val workerPrompt = buildString {
                            appendLine("## Sub-Task: ${task.description}")
                            if (dependencyContext.isNotEmpty()) {
                                appendLine()
                                appendLine("## Context from prior tasks:")
                                appendLine(dependencyContext)
                            }
                        }

                        val workerPipeline = AgentPipeline(
                            toolManager = toolManager,
                            completionProvider = completionProvider,
                            streamingCompletionProvider = streamingCompletionProvider,
                            apiKeyRepository = apiKeyRepository,
                            memoryManager = memoryManager
                        )
                        var taskResult = ""
                        var taskError: String? = null

                        workerPipeline.execute(
                            userMessage = workerPrompt,
                            modelId = workerModelId,
                            scopePath = scopePath,
                            enableDeepThinking = enableDeepThinking,
                            workerPersona = task.requiredPersona.takeIf { it.isNotBlank() }
                        ).collect { event ->
                            when (event) {
                                is AgentEvent.FinalAnswer -> taskResult = event.content
                                is AgentEvent.Error -> taskError = event.message
                                is AgentEvent.StreamChunk -> send(SwarmEvent.WorkerStreamChunk(task, event.delta))
                                is AgentEvent.ToolExecution -> send(SwarmEvent.WorkerToolUse(task, event.toolName, event.arguments))
                                else -> { /* other events handled internally by the worker */ }
                            }
                        }

                        Triple(task, taskResult, taskError)
                    }
                }

                // Collect results and update shared maps (sequentially after all tasks finish)
                deferredResults.awaitAll().forEach { (task, result, error) ->
                    if (error != null) {
                        // Preserve any partial output alongside the error for debugging
                        val errorMsg = if (result.isNotBlank()) "$error\n[Partial output]: $result" else error
                        failedTasks[task.id] = errorMsg
                        send(SwarmEvent.TaskFailed(task, errorMsg))
                    } else {
                        completedTasks[task.id] = result
                        send(SwarmEvent.TaskCompleted(task, result))
                    }
                }
            }
        }

        // ── Phase 3: Synthesize ──
        send(SwarmEvent.SynthesisStarted)

        val summaryContent = buildString {
            if (completedTasks.isNotEmpty()) {
                appendLine("## Completed Tasks")
                appendLine(completedTasks.entries.joinToString("\n\n") { (id, result) ->
                    "### $id (SUCCESS)\n$result"
                })
            }
            if (failedTasks.isNotEmpty()) {
                appendLine()
                appendLine("## Failed Tasks")
                appendLine(failedTasks.entries.joinToString("\n\n") { (id, error) ->
                    "### $id (FAILED)\nError: $error"
                })
            }
            if (skippedTasks.isNotEmpty()) {
                appendLine()
                appendLine("## Skipped Tasks")
                appendLine(skippedTasks.entries.joinToString("\n\n") { (id, reason) ->
                    "### $id (SKIPPED)\nReason: $reason"
                })
            }
        }

        val synthesisRequest = CompletionRequest(
            modelId = orchestratorModelId,
            messages = listOf(
                ChatMessage(role = MessageRole.USER, content = userMessage),
                ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = "Sub-task results:\n\n$summaryContent"
                ),
                ChatMessage(
                    role = MessageRole.USER,
                    // Truncate to 500 chars to limit prompt-injection surface from crafted input
                    content = "The user's original request was: \"${userMessage.take(500)}\"\n\nSynthesize these results into a final response formatted appropriately for the domain of that request."
                )
            ),
            systemPrompt = SYNTHESIS_PROMPT.trimIndent(),
            maxTokens = orchestratorModel.maxOutputTokens,
            targetContext = scopePath,
            apiKey = orchestratorApiKey
        )

        val synthesisResponse: CompletionResponse
        try {
            synthesisResponse = completionProvider(synthesisRequest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(SwarmEvent.Error("Synthesis failed: ${e.message}"))
            return@channelFlow
        }

        send(SwarmEvent.Completed(
            summary = synthesisResponse.content,
            tasksCompleted = completedTasks.size,
            tasksFailed = failedTasks.size + skippedTasks.size
        ))
    }

    /**
     * Parses the orchestrator's JSON response into a list of [SwarmTask]s.
     *
     * The model may wrap the JSON array in markdown code fences or surround it with prose.
     * Rather than blindly stripping backticks we locate the first `[` and the last `]` in the
     * response and extract that substring — this correctly handles all common formatting patterns.
     */
    private fun parseTasks(responseContent: String): List<SwarmTask> {
        return try {
            // Find the bounds of the JSON array, ignoring any surrounding markdown/prose
            val start = responseContent.indexOf('[')
            val end = responseContent.lastIndexOf(']')
            if (start == -1 || end == -1 || start >= end) {
                throw IllegalArgumentException("No JSON array found in orchestrator response")
            }
            val jsonStr = responseContent.substring(start, end + 1)
            val tasks = json.decodeFromString<List<SwarmTask>>(jsonStr)
            if (tasks.isEmpty()) throw IllegalArgumentException("Orchestrator returned an empty task list")
            tasks
        } catch (e: Exception) {
            // If structured parsing fails entirely, fall back to a single-task wrapper so the
            // Swarm still attempts the user's original request rather than aborting silently.
            listOf(SwarmTask(
                id = "task-1",
                description = responseContent.take(500),
                priority = 1
            ))
        }
    }
}

/**
 * Events emitted by the [SwarmOrchestrator] for real-time UI updates.
 */
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
    /** A streaming text delta chunk emitted by a Worker during its ReAct loop. */
    data class WorkerStreamChunk(val task: SwarmTask, val delta: String) : SwarmEvent()
    data object SynthesisStarted : SwarmEvent()
    data class Completed(
        val summary: String,
        val tasksCompleted: Int,
        val tasksFailed: Int
    ) : SwarmEvent()
    data class Error(val message: String) : SwarmEvent()
}
