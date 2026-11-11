package com.omnidev.workspace.domain.taskstate

/**
 * Persistent task state, in four layers that are never interchangeable:
 *  1. PLAN: what the runtime intends to do (plan revisions and steps).
 *  2. EXECUTION: what was actually attempted (cycles and tool invocations).
 *  3. RESULT: what the tool returned (bounded, redacted result on the invocation).
 *  4. VERIFICATION: what the P0 finish protocol accepted (task finish record, step completion proof).
 * Hidden model reasoning is never stored; only operational decisions and concise summaries.
 */
@kotlinx.serialization.Serializable
enum class TaskStatus {
    CREATED, RUNNING, INTERRUPTED, AWAITING_APPROVAL, BLOCKED, INCOMPLETE, COMPLETED, FAILED, CANCELLED;

    val terminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED

    companion object {
        private val NEXT = mapOf(
            CREATED to setOf(RUNNING, CANCELLED, INTERRUPTED),
            RUNNING to setOf(INTERRUPTED, AWAITING_APPROVAL, BLOCKED, INCOMPLETE, COMPLETED, FAILED, CANCELLED),
            INTERRUPTED to setOf(RUNNING, FAILED, CANCELLED),
            AWAITING_APPROVAL to setOf(RUNNING, FAILED, CANCELLED),
            BLOCKED to setOf(RUNNING, FAILED, CANCELLED),
            INCOMPLETE to setOf(RUNNING, FAILED, CANCELLED),
            COMPLETED to emptySet(), FAILED to emptySet(), CANCELLED to emptySet()
        )
        fun allowed(from: TaskStatus, to: TaskStatus) = to in NEXT.getValue(from)
    }
}

enum class StepStatus {
    PENDING, READY, RUNNING, COMPLETED, FAILED, BLOCKED, SKIPPED, INTERRUPTED;

    val terminal: Boolean get() = this == COMPLETED || this == SKIPPED

    companion object {
        private val NEXT = mapOf(
            // COMPLETED is reachable from every open state because the proof (a SUCCEEDED invocation or a written
            // non-tool justification) is enforced by the store, not by the path: a task that needed no tool, or a
            // resumed run that finishes an interrupted step, must be able to complete.
            PENDING to setOf(READY, RUNNING, COMPLETED, SKIPPED, BLOCKED, FAILED, INTERRUPTED),
            READY to setOf(RUNNING, COMPLETED, SKIPPED, BLOCKED, FAILED, INTERRUPTED),
            RUNNING to setOf(COMPLETED, FAILED, BLOCKED, INTERRUPTED, SKIPPED),
            INTERRUPTED to setOf(READY, RUNNING, COMPLETED, FAILED, SKIPPED, BLOCKED),
            FAILED to setOf(READY, RUNNING, COMPLETED, SKIPPED),
            BLOCKED to setOf(READY, RUNNING, COMPLETED, FAILED, SKIPPED),
            COMPLETED to emptySet(), SKIPPED to emptySet()
        )
        fun allowed(from: StepStatus, to: StepStatus) = to in NEXT.getValue(from)
    }
}

enum class InvocationStatus {
    PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED, UNKNOWN;

    /** Final: no later event may change the outcome. UNKNOWN/INTERRUPTED stay open for reconciliation. */
    val final: Boolean get() = this == SUCCEEDED || this == FAILED || this == CANCELLED

    companion object {
        private val NEXT = mapOf(
            PENDING to setOf(RUNNING, CANCELLED, INTERRUPTED),
            RUNNING to setOf(SUCCEEDED, FAILED, CANCELLED, INTERRUPTED, UNKNOWN),
            INTERRUPTED to setOf(UNKNOWN, SUCCEEDED, FAILED, CANCELLED),
            UNKNOWN to setOf(SUCCEEDED, FAILED, CANCELLED),
            SUCCEEDED to emptySet(), FAILED to emptySet(), CANCELLED to emptySet()
        )
        fun allowed(from: InvocationStatus, to: InvocationStatus) = to in NEXT.getValue(from)
    }
}

/** Whether repeating an invocation is harmless. Derived from the existing ToolBatchPolicy read-only rule. */
enum class Effect { READ_ONLY, SIDE_EFFECT }

sealed class TransitionResult {
    data object Applied : TransitionResult()
    /** The same event or target state was already recorded. Safe no-op (events may be delivered twice). */
    data object Duplicate : TransitionResult()
    data class Rejected(val reason: String) : TransitionResult()
    val ok: Boolean get() = this !is Rejected
}

data class NewTask(
    val taskId: String,
    val sessionId: Long?,
    /** Immutable. Never rewritten by any store operation. */
    val originalRequest: String,
    val normalizedObjective: String = "",
    val parentTaskId: String? = null,
    val workerId: String? = null,
    val constraintsJson: String = "{}",
    val createdAt: Long
)

data class TaskRecord(
    val taskId: String, val sessionId: Long?, val originalRequest: String, val normalizedObjective: String,
    val parentTaskId: String?, val workerId: String?, val constraintsJson: String,
    val status: TaskStatus, val currentCycle: Int, val currentStepId: String?, val planRevision: Int,
    val createdAt: Long, val updatedAt: Long, val heartbeatAt: Long,
    val finishStatus: String?, val finishSummary: String, val completionDigest: String
)

data class NewStep(
    /** Stable within a task across revisions, e.g. "S1" or "A2". */
    val key: String,
    val description: String,
    val dependsOnKeys: List<String> = emptyList(),
    val expectedOutput: String = "",
    val completionCondition: String = ""
)

data class PlanRevisionRecord(
    val planId: String, val taskId: String, val revision: Int, val reason: String,
    val createdAt: Long, val supersededAt: Long?, val metadataJson: String = ""
)

data class StepRecord(
    val stepId: String, val planId: String, val taskId: String, val key: String, val ordinal: Int,
    val description: String, val dependsOnKeys: List<String>, val expectedOutput: String, val completionCondition: String,
    val status: StepStatus, val statusReason: String,
    /** Invocation ids proving the step, or empty when [nonToolJustification] explains a non-tool completion. */
    val evidenceInvocationIds: List<String>, val nonToolJustification: String,
    val createdAt: Long, val updatedAt: Long
)

data class StepCompletion(val evidenceInvocationIds: List<String> = emptyList(), val nonToolJustification: String = "")

data class CycleRecord(
    val cycleId: String, val taskId: String, val seq: Int, val planRevision: Int, val stepId: String?,
    val startedAt: Long, val endedAt: Long?, val outcome: String, val summary: String, val decisionsJson: String
)

data class NewInvocation(
    /** "taskId/cycleId/callId": unique across resumes, so a replayed event is a duplicate and a new call never is. */
    val invocationId: String, val callId: String, val taskId: String, val cycleId: String, val stepId: String?,
    val toolName: String, val schemaId: String, val inputSanitized: String, val inputHash: String,
    val effect: Effect, val idempotencyKey: String, val attempt: Int = 1, val retryOfInvocationId: String? = null,
    val requestedAt: Long
)

data class InvocationRecord(
    val invocationId: String, val callId: String, val taskId: String, val cycleId: String, val stepId: String?,
    val toolName: String, val schemaId: String, val inputSanitized: String, val inputHash: String,
    val effect: Effect, val idempotencyKey: String, val attempt: Int, val retryOfInvocationId: String?,
    val status: InvocationStatus, val requestedAt: Long, val startedAt: Long?, val completedAt: Long?,
    val resultSummary: String, val resultHash: String, val resultTruncated: Boolean, val resultChars: Int,
    val isError: Boolean, val errorDetail: String, val classification: String,
    /** Set only by verification, never by the tool result itself. */
    val verification: String, val seq: Long
)

data class InvocationPatch(
    val startedAt: Long? = null, val completedAt: Long? = null,
    val resultSummary: String? = null, val resultHash: String? = null, val resultTruncated: Boolean? = null,
    val resultChars: Int? = null, val isError: Boolean? = null, val errorDetail: String? = null,
    val classification: String? = null, val verification: String? = null
)

data class FinishRecord(val finishStatus: String, val summary: String, val digest: String)

data class TransitionEvent(
    val seq: Long, val taskId: String, val entityType: String, val entityId: String,
    val from: String, val to: String, val reason: String, val at: Long, val eventKey: String
)

data class TaskCounts(
    val cycles: Int, val invocations: Int, val succeeded: Int, val failed: Int, val unresolved: Int,
    val stepsTotal: Int, val stepsCompleted: Int
)

/** Bounded list/detail projection. It deliberately excludes the full history of successful tool results. */
data class TaskRecoverySnapshot(
    val task: TaskRecord,
    val latestPlan: PlanRevisionRecord?,
    val currentSteps: List<StepRecord>,
    val latestCycle: CycleRecord?,
    val counts: TaskCounts,
    /** All pending or interrupted invocations, read-only and side-effecting; no successful history. */
    val unresolvedInvocations: List<InvocationRecord>,
    /** Only pending/uncertain side effects are eligible for reconciliation or mutation blocking. */
    val unresolvedSideEffects: List<InvocationRecord>
)

data class TaskResumeClaim(
    val snapshot: TaskSnapshot,
    val recoveryPlan: RecoveryPlan
)

sealed class TaskResumeClaimResult {
    data class Claimed(val claim: TaskResumeClaim) : TaskResumeClaimResult()
    data class Rejected(val reason: String, val decision: RecoveryDecision? = null) : TaskResumeClaimResult()
}

data class TaskSnapshot(
    val task: TaskRecord,
    val plans: List<PlanRevisionRecord>,
    /** Steps of every revision, oldest first. Historical revisions are kept intact. */
    val steps: List<StepRecord>,
    val cycles: List<CycleRecord>,
    val invocations: List<InvocationRecord>
) {
    val latestPlan: PlanRevisionRecord? get() = plans.maxByOrNull { it.revision }
    val currentSteps: List<StepRecord> get() = latestPlan?.let { p -> steps.filter { it.planId == p.planId }.sortedBy { it.ordinal } }.orEmpty()
}

/** Everything the recorder and recovery need from persistence. Implemented by Room and by an in-memory store. */
interface TaskStateStore {
    /** Idempotent: false when the task already exists (the original request is never overwritten). */
    suspend fun createTask(task: NewTask): Boolean
    suspend fun task(taskId: String): TaskRecord?
    suspend fun transitionTask(taskId: String, to: TaskStatus, reason: String, at: Long, eventKey: String): TransitionResult
    suspend fun preserveAcceptance(taskId: String, acceptance: List<String>, at: Long)
    suspend fun heartbeat(taskId: String, at: Long)
    suspend fun setObjective(taskId: String, normalizedObjective: String, at: Long)

    /** Atomically supersedes the current revision and creates the next one. Returns null when [eventKey] was already applied. */
    suspend fun appendPlanRevision(taskId: String, steps: List<NewStep>, reason: String, at: Long, eventKey: String, metadataJson: String = "", expectedRevision: Int? = null, runtimeObjective: String? = null): PlanRevisionRecord?
    suspend fun transitionStep(stepId: String, to: StepStatus, reason: String, at: Long, eventKey: String, completion: StepCompletion? = null): TransitionResult

    suspend fun beginCycle(taskId: String, stepId: String?, at: Long, eventKey: String): CycleRecord
    suspend fun endCycle(cycleId: String, outcome: String, summary: String, decisionsJson: String, at: Long): TransitionResult

    suspend fun recordInvocationIntent(invocation: NewInvocation): Boolean
    suspend fun transitionInvocation(invocationId: String, to: InvocationStatus, patch: InvocationPatch, at: Long, eventKey: String, reason: String = ""): TransitionResult

    /** One transaction: the task finish record, the task status and the step outcomes together. */
    suspend fun finalizeTask(taskId: String, status: TaskStatus, finish: FinishRecord, stepOutcomes: List<StepOutcome>, at: Long, eventKey: String): TransitionResult

    suspend fun snapshot(taskId: String): TaskSnapshot?
    suspend fun recoverySnapshot(taskId: String): TaskRecoverySnapshot?
    /** Atomically normalizes interrupted records and claims an eligible task for exactly one resume caller. */
    suspend fun claimForResume(taskId: String, at: Long, eventKey: String): TaskResumeClaimResult
    /** Release a claim that failed before a new execution cycle was persisted. */
    suspend fun releaseResumeClaim(taskId: String, expectedCycle: Int, at: Long, eventKey: String): TransitionResult
    /** Atomically apply the recovery plan to a task after user cancellation or run completion. */
    suspend fun recoverTask(taskId: String, at: Long): RecoveryOutcome?
    /** Re-check status and heartbeat in the same transaction as recovery to avoid interrupting an active run. */
    suspend fun recoverIfStale(taskId: String, cutoff: Long, liveTaskIds: Set<String>, at: Long): RecoveryOutcome?
    /** Append a bounded operational event without changing task state. */
    suspend fun recordEvent(taskId: String, entityType: String, entityId: String, from: String, to: String, reason: String, at: Long, eventKey: String): Boolean
    suspend fun eventRecorded(taskId: String, eventKey: String): Boolean
    suspend fun invocation(invocationId: String): InvocationRecord?
    suspend fun invocations(taskId: String, limit: Int, offset: Int): List<InvocationRecord>
    suspend fun events(taskId: String, limit: Int, offset: Int): List<TransitionEvent>
    suspend fun counts(taskId: String): TaskCounts
    suspend fun tasksByStatus(statuses: Set<TaskStatus>, limit: Int, offset: Int = 0): List<TaskRecord>
    suspend fun tasksForSession(sessionId: Long, limit: Int, offset: Int = 0): List<TaskRecord>
    /** Side-effecting invocations whose outcome is not established (PENDING, RUNNING, INTERRUPTED or UNKNOWN). */
    suspend fun unresolvedSideEffects(taskId: String): List<InvocationRecord>
}

data class StepOutcome(val stepId: String, val status: StepStatus, val reason: String, val completion: StepCompletion? = null)

/** Persistence errors are surfaced, never swallowed. */
class TaskStateException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
