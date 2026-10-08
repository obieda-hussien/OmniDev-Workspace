package com.omnidev.workspace.data.routines

import com.omnidev.workspace.data.tools.ToolExecutionResult

/** Per-invocation evidence, never a mutable manager-wide "current user request". */
object RoutineCallGuard {
    suspend fun check(message: String, routines: List<LearnedRoutine>, routineId: String,
        parameters: Map<String, String>, run: RoutineRun? = null, resuming: Boolean = false,
        requireReview: Boolean = false,
        confirm: suspend (String) -> Boolean): ToolExecutionResult? {
        val routine = routines.singleOrNull { it.id == routineId && it.enabled }
            ?: return denied("The selected learned task is missing or disabled.")
        if (resuming && (run == null || run.routineId != routine.id || run.revision != routine.revision ||
                run.status != RoutineRunStatus.PAUSED)) return denied("The selected checkpoint is not a current paused run.")
        val intent = RoutineInvocationPolicy.decide(message, routines, routineId, parameters, resuming)
        val decision = if (requireReview && intent == RoutineInvocationPolicy.Decision.DIRECT)
            RoutineInvocationPolicy.Decision.REVIEW else intent
        return when (decision) {
            RoutineInvocationPolicy.Decision.BLOCKED -> denied(
                "This message does not authorize executing the selected learned task. Explain or inspect it instead; do not try another recipe.")
            RoutineInvocationPolicy.Decision.DIRECT -> null
            RoutineInvocationPolicy.Decision.REVIEW -> {
                val preview = buildString {
                    appendLine("${if (resuming) "Resume" else "Run"} learned task: ${routine.name}")
                    appendLine("Current request: ${message.take(1_200)}")
                    appendLine("This saved task may cover only part of your request. Review its actions and values:")
                    parameters.forEach { (key, value) -> appendLine("$key = ${value.take(300)}") }
                    val start = run?.nextStep ?: 0
                    routine.steps.drop(start).forEachIndexed { index, step ->
                        appendLine("${start + index + 1}. ${step.label}")
                    }
                    append("Additional conditions in your request must still be handled by the agent.")
                }
                if (confirm(preview)) null else denied("Learned task was not approved. No saved actions executed; do not retry it.")
            }
        }
    }

    private fun denied(message: String) = ToolExecutionResult(message, true,
        classification = "ROUTINE_INTENT_MISMATCH", retryable = false, persistentFailure = true)
}
