package com.omnidev.workspace.domain.engine

sealed class SwarmEvent {
    data object PlanningStarted : SwarmEvent()
    data class SteeringApplied(val revision: Long) : SwarmEvent()
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
