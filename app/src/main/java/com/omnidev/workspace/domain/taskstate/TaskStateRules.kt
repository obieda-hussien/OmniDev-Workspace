package com.omnidev.workspace.domain.taskstate

/** Store-independent invariants shared by the Room and in-memory stores. */
object TaskStateRules {
    fun planId(taskId: String, revision: Int) = "$taskId:r$revision"
    fun stepId(taskId: String, revision: Int, key: String) = "$taskId:r$revision:$key"
    fun cycleId(taskId: String, seq: Int) = "$taskId:c$seq"

    fun taskTransition(from: TaskStatus, to: TaskStatus): TransitionResult = when {
        from == to -> TransitionResult.Duplicate
        TaskStatus.allowed(from, to) -> TransitionResult.Applied
        else -> TransitionResult.Rejected("task cannot move $from -> $to")
    }

    fun stepTransition(from: StepStatus, to: StepStatus): TransitionResult = when {
        from == to -> TransitionResult.Duplicate
        StepStatus.allowed(from, to) -> TransitionResult.Applied
        else -> TransitionResult.Rejected("step cannot move $from -> $to")
    }

    fun invocationTransition(from: InvocationStatus, to: InvocationStatus): TransitionResult = when {
        from == to -> TransitionResult.Duplicate
        InvocationStatus.allowed(from, to) -> TransitionResult.Applied
        else -> TransitionResult.Rejected("invocation cannot move $from -> $to")
    }

    /**
     * A step is COMPLETED only with proof: invocations of this task that really SUCCEEDED, or an explicit,
     * non-blank justification for a non-tool path. A plan entry, a selected tool, a requested call or a model
     * statement never qualifies.
     */
    fun completionProof(taskInvocationStatus: Map<String, InvocationStatus>, completion: StepCompletion?): String? {
        if (completion == null) return "completion needs evidence or a justification"
        if (completion.evidenceInvocationIds.isNotEmpty()) {
            val bad = completion.evidenceInvocationIds.filter { taskInvocationStatus[it] != InvocationStatus.SUCCEEDED }
            return if (bad.isEmpty()) null else "evidence is not a SUCCEEDED invocation of this task: ${bad.joinToString()}"
        }
        return if (completion.nonToolJustification.isNotBlank()) null else "completion needs evidence or a justification"
    }

    /** New revision steps inherit COMPLETED only for an identical key and description, with the old proof. */
    fun carriedOver(previous: List<StepRecord>, step: NewStep): StepRecord? =
        previous.firstOrNull { it.key == step.key && it.description == step.description && it.dependsOnKeys == step.dependsOnKeys &&
            it.expectedOutput == step.expectedOutput && it.completionCondition == step.completionCondition && it.status == StepStatus.COMPLETED }

    fun initialStatus(step: NewStep, byKey: Map<String, StepStatus>): StepStatus =
        if (step.dependsOnKeys.all { byKey[it] == StepStatus.COMPLETED }) StepStatus.READY else StepStatus.PENDING

    fun clamp(text: String, max: Int) = if (text.length <= max) text else text.take(max - 1) + "…"
}
