package com.omnidev.workspace.domain.planning

import com.omnidev.workspace.domain.taskstate.TaskRecoverySnapshot
import com.omnidev.workspace.domain.taskstate.TaskStatus

data class TaskPlanStepSummary(val key: String, val description: String, val state: StepReadiness,
    val dependencies: List<String>, val expectedOutput: String, val condition: String, val evidenceCount: Int, val approval: String?)
data class TaskPlanSummary(val objective: String, val summary: String, val revision: Int, val reason: String,
    val constraints: List<String>, val steps: List<TaskPlanStepSummary>, val activeKey: String?, val stalled: Boolean) {
    companion object {
        fun from(snapshot: TaskRecoverySnapshot): TaskPlanSummary? {
            val plan = snapshot.latestPlan ?: return null
            val metadata = PlanCodec.metadata(plan.metadataJson) ?: return null
            val schedule = TaskExecutionScheduler.schedule(snapshot.currentSteps, snapshot.task.status == TaskStatus.AWAITING_APPROVAL)
            return TaskPlanSummary(PlanningPrivacy.safe(metadata.objective).take(240), PlanningPrivacy.safe(metadata.summary).take(400),
                plan.revision, PlanningPrivacy.safe(plan.reason).take(240), metadata.constraints.map { PlanningPrivacy.safe(it).take(200) },
                schedule.steps.map { TaskPlanStepSummary(it.step.key, PlanningPrivacy.safe(it.step.description).take(300), it.state,
                    it.step.dependsOnKeys, PlanningPrivacy.safe(it.step.expectedOutput).take(300), PlanningPrivacy.safe(it.step.completionCondition).take(300),
                    it.step.evidenceInvocationIds.size, metadata.policies[it.step.key]?.approvalKind) }, schedule.next?.key, schedule.stalled)
        }
    }
}
