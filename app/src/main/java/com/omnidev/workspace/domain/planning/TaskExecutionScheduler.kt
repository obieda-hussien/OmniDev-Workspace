package com.omnidev.workspace.domain.planning

import com.omnidev.workspace.domain.taskstate.*

enum class StepReadiness { READY, BLOCKED_BY_DEPENDENCY, RUNNING, COMPLETED, FAILED, INTERRUPTED, SKIPPED, AWAITING_APPROVAL }
data class ScheduledStep(val step: StepRecord, val state: StepReadiness, val blockedBy: List<String>)
data class Schedule(val steps: List<ScheduledStep>, val next: StepRecord?, val stalled: Boolean)
data class StepExecutionContext(val taskId: String, val planId: String, val stepId: String, val cycleId: String)

/** Sequential by design. SKIPPED is never proof that a dependency was completed. */
object TaskExecutionScheduler {
    fun schedule(steps: List<StepRecord>, awaitingApproval: Boolean = false): Schedule {
        val completed = steps.filter { it.status == StepStatus.COMPLETED &&
            (it.evidenceInvocationIds.isNotEmpty() || it.nonToolJustification.isNotBlank()) }.map { it.key }.toSet()
        val states = steps.sortedWith(compareBy<StepRecord> { it.ordinal }.thenBy { it.key }).map { s ->
            val blocked = s.dependsOnKeys.filter { it !in completed }
            val state = when {
                s.status == StepStatus.COMPLETED -> if (s.key in completed) StepReadiness.COMPLETED else StepReadiness.FAILED
                s.status == StepStatus.SKIPPED -> StepReadiness.SKIPPED
                s.status == StepStatus.FAILED -> StepReadiness.FAILED
                awaitingApproval && s.status == StepStatus.BLOCKED -> StepReadiness.AWAITING_APPROVAL
                blocked.isNotEmpty() -> StepReadiness.BLOCKED_BY_DEPENDENCY
                s.status == StepStatus.BLOCKED -> StepReadiness.FAILED
                s.status == StepStatus.RUNNING -> StepReadiness.RUNNING
                s.status == StepStatus.INTERRUPTED -> StepReadiness.INTERRUPTED
                else -> StepReadiness.READY
            }
            ScheduledStep(s, state, blocked)
        }
        val next = states.firstOrNull { it.state == StepReadiness.RUNNING }?.step ?: states.firstOrNull {
            it.state == StepReadiness.READY || it.state == StepReadiness.INTERRUPTED
        }?.step
        return Schedule(states, next, next == null && states.any { it.state != StepReadiness.COMPLETED })
    }

    fun valid(context: StepExecutionContext, snapshot: TaskSnapshot): Boolean =
        context.taskId == snapshot.task.taskId && context.planId == snapshot.latestPlan?.planId &&
            snapshot.task.currentStepId == context.stepId && snapshot.cycles.any {
                it.cycleId == context.cycleId && it.seq == snapshot.task.currentCycle && it.stepId == context.stepId && it.endedAt == null
            } &&
            schedule(snapshot.currentSteps).next?.stepId == context.stepId
}
