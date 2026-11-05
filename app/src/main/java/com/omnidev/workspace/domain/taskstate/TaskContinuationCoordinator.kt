package com.omnidev.workspace.domain.taskstate

import com.omnidev.workspace.domain.engine.SensitiveObservationRedactor
import com.omnidev.workspace.domain.engine.completion.RunIdentity
import com.omnidev.workspace.domain.engine.completion.TaskKind
import java.util.UUID

/** Redacted list/detail projection; full invocation results are loaded only for an explicit resume/review. */
data class TaskRecoverySummary(
    val taskId: String,
    val sessionId: Long?,
    val originalRequest: String,
    val objective: String,
    val status: TaskStatus,
    val planRevision: Int,
    val completedSteps: Int,
    val totalSteps: Int,
    val openSteps: List<String>,
    val latestCycle: Int?,
    val latestActivityAt: Long,
    val counts: TaskCounts,
    val unresolvedInvocations: List<RecoveryInvocationSummary>,
    val decision: RecoveryDecision,
    val reasons: List<String>,
    val canResume: Boolean
)

data class RecoveryInvocationSummary(
    val invocationId: String,
    val toolName: String,
    val target: String,
    val status: InvocationStatus,
    val requestedAt: Long,
    val resultSummary: String,
    val requiresReconciliation: Boolean,
    val reason: String
)

/** The exact identity and prompt required to continue an existing task through the normal AgentPipeline. */
data class TaskContinuation(
    val taskId: String,
    val sessionId: Long,
    val originalRequest: String,
    val prompt: String,
    val identity: RunIdentity,
    /** Cycle counter at claim time; used to release only an unstarted claim. */
    val claimedCycle: Int
)

sealed class TaskResumeResult {
    data class Ready(val continuation: TaskContinuation) : TaskResumeResult()
    data class Rejected(val reason: String, val decision: RecoveryDecision? = null) : TaskResumeResult()
}

/**
 * Stores only the small piece of P0 policy metadata not represented by the P1 plan.
 * It uses the existing constraintsJson column, so P2 does not require a Room migration.
 */
object TaskResumeMetadata {
    fun encode(identity: RunIdentity, policy: com.omnidev.workspace.domain.engine.completion.FinalizationPolicy): String = buildString {
        append("{\"p2FinishPolicy\":{\"enforce\":true,\"requireExecution\":")
        append(policy.requireExecution.toString())
        append(",\"taskKind\":\"")
        append(identity.taskKind.name)
        append("\"}}")
    }

    fun requireExecution(raw: String): Boolean? =
        Regex("\"requireExecution\"\\s*:\\s*(true|false|null)", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.getOrNull(1)?.lowercase()?.let {
                when (it) { "true" -> true; "false" -> false; else -> null }
            }

    fun forceFinishProtocol(raw: String): Boolean =
        Regex("\"enforce\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.getOrNull(1)?.equals("true", ignoreCase = true) ?: true

    fun taskKind(raw: String): TaskKind =
        Regex("\"taskKind\"\\s*:\\s*\"([A-Z_]+)\"", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.getOrNull(1)?.let { runCatching { TaskKind.valueOf(it.uppercase()) }.getOrNull() }
            ?: TaskKind.GENERAL
}

/**
 * User-controlled recovery use case. Listing is paged and returns bounded summaries; resume is a
 * separate explicit operation and obtains its exclusive claim inside TaskStateStore.
 */
class TaskContinuationCoordinator(
    private val store: TaskStateStore,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun tasksForSession(sessionId: Long, pageSize: Int = 30): List<TaskRecoverySummary> {
        require(pageSize > 0)
        TaskRecoveryBootstrap.awaitReady()
        val records = ArrayList<TaskRecord>()
        var offset = 0
        val visible = setOf(TaskStatus.INTERRUPTED, TaskStatus.INCOMPLETE, TaskStatus.BLOCKED,
            TaskStatus.AWAITING_APPROVAL, TaskStatus.RUNNING, TaskStatus.COMPLETED,
            TaskStatus.FAILED, TaskStatus.CANCELLED)
        while (true) {
            val page = store.tasksForSession(sessionId, pageSize, offset)
            if (page.isEmpty()) break
            records += page
            if (page.size < pageSize) break
            offset += page.size
        }
        val summaries = ArrayList<TaskRecoverySummary>(records.size)
        for (record in records) {
            if (record.status !in visible) continue
            store.recoverySnapshot(record.taskId)?.let { summaries += summarize(it) }
        }
        return summaries
    }

    suspend fun summary(taskId: String): TaskRecoverySummary? =
        store.recoverySnapshot(taskId)?.let(::summarize)

    suspend fun reconcile(
        taskId: String,
        invocationId: String,
        outcome: InvocationStatus,
        verification: String
    ): TransitionResult = TaskRecovery(store, now).reconcile(taskId, invocationId, outcome, verification)

    /**
     * Claims and normalizes the task atomically. No work is dispatched here; the caller must explicitly
     * launch the returned identity through AgentPipeline. Unknown side effects are rejected.
     */
    suspend fun resume(taskId: String): TaskResumeResult {
        TaskRecoveryBootstrap.awaitReady()
        val before = store.task(taskId) ?: return TaskResumeResult.Rejected("Task no longer exists.")
        val sessionId = before.sessionId ?: return TaskResumeResult.Rejected("This task is not linked to a chat session.")
        val eventKey = "resume-claim:${UUID.randomUUID()}"
        return when (val result = store.claimForResume(taskId, now(), eventKey)) {
            is TaskResumeClaimResult.Rejected -> TaskResumeResult.Rejected(result.reason, result.decision)
            is TaskResumeClaimResult.Claimed -> {
                val snapshot = result.claim.snapshot
                val task = snapshot.task
                val criteria = snapshot.currentSteps.filter { it.key.matches(Regex("A[0-9]+")) }
                    .sortedBy { it.ordinal }.map { it.description }
                val metadata = task.constraintsJson
                val identity = RunIdentity(
                    runId = task.taskId,
                    workerId = task.workerId,
                    acceptance = criteria,
                    requireExecution = TaskResumeMetadata.requireExecution(metadata),
                    taskKind = TaskResumeMetadata.taskKind(metadata),
                    sessionId = task.sessionId,
                    parentTaskId = task.parentTaskId,
                    resume = TaskRunRecorder.seedFrom(snapshot),
                    resumeClaimed = true,
                    forceFinishProtocol = TaskResumeMetadata.forceFinishProtocol(metadata)
                )
                TaskResumeResult.Ready(TaskContinuation(
                    taskId = task.taskId,
                    sessionId = sessionId,
                    originalRequest = task.originalRequest,
                    prompt = continuationPrompt(snapshot),
                    identity = identity,
                    claimedCycle = task.currentCycle
                ))
            }
        }
    }

    /** Release only a claim that failed before any new cycle was committed. */
    suspend fun releaseIfNotStarted(continuation: TaskContinuation): TransitionResult =
        store.releaseResumeClaim(
            continuation.taskId, continuation.claimedCycle, now(),
            "resume-release:${continuation.taskId}:${continuation.claimedCycle}"
        )

    private fun summarize(snapshot: TaskRecoverySnapshot): TaskRecoverySummary {
        val task = snapshot.task
        val uncertain = snapshot.unresolvedSideEffects.filter {
            it.status == InvocationStatus.RUNNING || it.status == InvocationStatus.INTERRUPTED || it.status == InvocationStatus.UNKNOWN
        }
        val decisionAndReasons = when (task.status) {
            TaskStatus.COMPLETED -> RecoveryDecision.ALREADY_COMPLETED to listOf("Task is already completed and verified.")
            TaskStatus.FAILED, TaskStatus.CANCELLED -> RecoveryDecision.PERMANENTLY_FAILED to listOf("Task has permanently ended as ${task.status}.")
            TaskStatus.BLOCKED -> RecoveryDecision.REQUIRES_USER_INTERVENTION to listOf("Task is blocked and needs user action.")
            TaskStatus.AWAITING_APPROVAL -> RecoveryDecision.REQUIRES_USER_INTERVENTION to listOf("Task is waiting for an approval decision.")
            TaskStatus.RUNNING -> RecoveryDecision.REQUIRES_USER_INTERVENTION to listOf("Task is still marked RUNNING; it cannot be resumed concurrently.")
            TaskStatus.INTERRUPTED, TaskStatus.INCOMPLETE -> if (uncertain.isNotEmpty()) {
                RecoveryDecision.REQUIRES_RECONCILIATION to listOf("One or more side effects have an unknown outcome.")
            } else {
                RecoveryDecision.SAFE_TO_RESUME to listOf("No unresolved side effect requires reconciliation.")
            }
            else -> RecoveryDecision.REQUIRES_USER_INTERVENTION to listOf("Task is not ready for recovery.")
        }
        val currentSteps = snapshot.currentSteps
        val open = currentSteps.filter { !it.status.terminal }.map { SensitiveObservationRedactor.redact(it.description).take(160) }
        val invocationSummaries = snapshot.unresolvedInvocations.map { inv ->
            val uncertain = inv.status == InvocationStatus.RUNNING || inv.status == InvocationStatus.INTERRUPTED || inv.status == InvocationStatus.UNKNOWN
            val recon = inv.effect == Effect.SIDE_EFFECT && uncertain
            RecoveryInvocationSummary(
                invocationId = inv.invocationId,
                toolName = inv.toolName.take(80),
                target = TaskRunRecorder.pathOf(inv.inputSanitized)?.substringAfterLast('/')?.take(80).orEmpty(),
                status = inv.status,
                requestedAt = inv.startedAt ?: inv.requestedAt,
                resultSummary = SensitiveObservationRedactor.redact(inv.resultSummary).take(180),
                requiresReconciliation = recon,
                reason = when {
                    recon -> "The original side effect may or may not have happened. A later read alone is not proof."
                    inv.effect == Effect.READ_ONLY && uncertain -> "This read-only operation was interrupted and can be safely repeated."
                    else -> "The invocation was durably requested but never marked RUNNING; recovery will not treat it as executed."
                }
            )
        }
        val latestAt = snapshot.latestCycle?.endedAt ?: snapshot.latestCycle?.startedAt ?: task.updatedAt
        val resumable = task.status in setOf(TaskStatus.INTERRUPTED, TaskStatus.INCOMPLETE) &&
            decisionAndReasons.first in setOf(RecoveryDecision.SAFE_TO_RESUME, RecoveryDecision.SAFE_TO_RETRY)
        return TaskRecoverySummary(
            taskId = task.taskId,
            sessionId = task.sessionId,
            originalRequest = SensitiveObservationRedactor.redact(task.originalRequest).take(240),
            objective = SensitiveObservationRedactor.redact(task.normalizedObjective.ifBlank { task.originalRequest }).take(240),
            status = task.status,
            planRevision = snapshot.latestPlan?.revision ?: task.planRevision,
            completedSteps = snapshot.counts.stepsCompleted,
            totalSteps = snapshot.counts.stepsTotal,
            openSteps = open,
            latestCycle = snapshot.latestCycle?.seq,
            latestActivityAt = maxOf(task.updatedAt, latestAt),
            counts = snapshot.counts,
            unresolvedInvocations = invocationSummaries,
            decision = decisionAndReasons.first,
            reasons = decisionAndReasons.second,
            canResume = resumable
        )
    }

    private fun continuationPrompt(snapshot: TaskSnapshot): String = buildString {
        appendLine("Continue the existing persistent task. This is a user-authorized resume, not a new task.")
        appendLine("Original request (preserve it exactly as the governing request):")
        appendLine(snapshot.task.originalRequest.take(4_000))
        appendLine("Latest objective:")
        appendLine(snapshot.task.normalizedObjective.ifBlank { snapshot.task.originalRequest }.take(2_000))
        appendLine("Persisted plan revision: ${snapshot.latestPlan?.revision ?: snapshot.task.planRevision}.")
        appendLine("Verified completed steps:")
        snapshot.currentSteps.filter { it.status == StepStatus.COMPLETED }.forEach { appendLine("- ${it.key}: ${it.description.take(240)}") }
        appendLine("Open steps (continue from the earliest dependency-ready step; do not restart completed work):")
        snapshot.currentSteps.filter { !it.status.terminal }.forEach { appendLine("- ${it.key} [${it.status}]: ${it.description.take(240)}") }
        appendLine("Historical tool invocations are evidence only. Never replay them merely because this task is resuming.")
        appendLine("Only SUCCEEDED invocations count as successful evidence. FAILED, UNKNOWN and interrupted side effects are not success.")
        appendLine("Do not claim task completion; the runtime finish gate will verify the result.")
    }.take(8_000)
}
