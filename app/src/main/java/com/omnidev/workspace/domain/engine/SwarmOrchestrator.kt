package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.tools.ToolManager
import com.omnidev.workspace.registry.ModelRegistry
import kotlinx.coroutines.CancellationException
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
 */
class SwarmOrchestrator(
    private val toolManager: ToolManager,
    private val completionProvider: suspend (CompletionRequest) -> CompletionResponse,
    private val apiKeyRepository: com.omnidev.workspace.data.repository.ApiKeyRepository? = null
) {

    companion object {
        /** Maximum sub-tasks the orchestrator can generate. */
        private const val MAX_SUBTASKS = 10

        /** System prompt for the Orchestrator (planner) role. */
        private const val ORCHESTRATOR_SYSTEM_PROMPT = """
You are a Swarm Orchestrator — an expert project planner for coding tasks.

Your job is to analyze the user's request and decompose it into a prioritized list of 
independent or sequential sub-tasks. Each sub-task should be:
1. Self-contained enough for a single agent to execute
2. Ordered by dependency (tasks that must complete first should have lower priority numbers)
3. Clearly described with specific file paths and expected outcomes

Respond with a JSON array of task objects:
[
  {"id": "task-1", "description": "...", "priority": 1, "dependencies": []},
  {"id": "task-2", "description": "...", "priority": 2, "dependencies": ["task-1"]}
]

Keep task count reasonable (max 10). Merge trivial steps into larger tasks.
Do NOT include code — just planning and task descriptions.
"""

        private const val SYNTHESIS_PROMPT = """
You are a Swarm Orchestrator synthesizing the results of your worker agents. 
Review the completed sub-tasks and their outcomes below.

Produce a concise summary for the user that covers:
1. What was accomplished
2. Files created or modified
3. Any issues encountered or tasks that failed
4. Suggested next steps (if applicable)

CRITICAL DIRECTIVE: If any sub-task failed or was skipped, you MUST explicitly state
what failed and why. Never claim the overall task was successful if sub-tasks failed.
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

        // ── Phase 2: Delegate ──
        val completedTasks = mutableMapOf<String, String>()
        val failedTasks = mutableMapOf<String, String>()
        val skippedTasks = mutableMapOf<String, String>()
        val sortedTasks = tasks.sortedBy { it.priority }

        for (task in sortedTasks) {
            // Check dependencies are met
            val unmetDeps = task.dependencies.filter { it !in completedTasks }
            if (unmetDeps.isNotEmpty()) {
                val reason = "Unmet dependencies: $unmetDeps"
                skippedTasks[task.id] = reason
                send(SwarmEvent.TaskSkipped(task, reason))
                continue
            }

            send(SwarmEvent.TaskStarted(task))

            // Build context from completed dependencies
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

            // Execute via a Worker AgentPipeline
            val workerPipeline = AgentPipeline(toolManager, completionProvider, apiKeyRepository = apiKeyRepository)
            var taskResult = ""
            var taskError: String? = null

            workerPipeline.execute(
                userMessage = workerPrompt,
                modelId = workerModelId,
                scopePath = scopePath,
                enableDeepThinking = enableDeepThinking
            ).collect { event ->
                when (event) {
                    is AgentEvent.FinalAnswer -> {
                        taskResult = event.content
                    }
                    is AgentEvent.Error -> {
                        taskError = event.message
                    }
                    is AgentEvent.ToolExecution -> {
                        send(SwarmEvent.WorkerToolUse(task, event.toolName, event.arguments))
                    }
                    else -> { /* Forward other events as needed */ }
                }
            }

            if (taskError != null) {
                failedTasks[task.id] = taskError!!
                send(SwarmEvent.TaskFailed(task, taskError!!))
            } else {
                completedTasks[task.id] = taskResult
                send(SwarmEvent.TaskCompleted(task, taskResult))
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
                    content = "Synthesize these results into a final summary."
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
     * Handles common formatting issues gracefully.
     */
    private fun parseTasks(responseContent: String): List<SwarmTask> {
        return try {
            // Extract JSON array from the response (may be wrapped in markdown code blocks)
            val jsonStr = responseContent
                .replace("```json", "").replace("```", "")
                .trim()

            json.decodeFromString<List<SwarmTask>>(jsonStr)
        } catch (e: Exception) {
            // If parsing fails, try to create a single task from the response
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
    data object SynthesisStarted : SwarmEvent()
    data class Completed(
        val summary: String,
        val tasksCompleted: Int,
        val tasksFailed: Int
    ) : SwarmEvent()
    data class Error(val message: String) : SwarmEvent()
}
