package com.omnidev.workspace.domain.taskstate

import kotlinx.coroutines.launch

enum class RecoveryDecision {
    ALREADY_COMPLETED, SAFE_TO_RESUME, SAFE_TO_RETRY, REQUIRES_RECONCILIATION, REQUIRES_USER_INTERVENTION, PERMANENTLY_FAILED
}

data class InvocationRecovery(val invocationId: String, val to: InvocationStatus, val reason: String)
data class StepRecovery(val stepId: String, val to: StepStatus, val reason: String)

data class RecoveryPlan(
    val decision: RecoveryDecision,
    val invocationTransitions: List<InvocationRecovery>,
    val stepTransitions: List<StepRecovery>,
    /** null = leave the task status alone. */
    val taskStatus: TaskStatus?,
    val resumeStepId: String?,
    /** Side-effecting invocations whose outcome must be established before anything is retried. */
    val needsReconciliation: List<String>,
    val reasons: List<String>
)

/**
 * Pure recovery policy. It never executes anything and never invents an outcome: an interrupted
 * side-effecting call becomes UNKNOWN (not failed, not succeeded) and blocks retry until reconciled.
 */
object TaskRecoveryPlanner {
    fun plan(snapshot: TaskSnapshot): RecoveryPlan {
        val task = snapshot.task
        when (task.status) {
            TaskStatus.COMPLETED -> return RecoveryPlan(RecoveryDecision.ALREADY_COMPLETED, emptyList(), emptyList(), null, null, emptyList(), listOf("task already completed and verified"))
            TaskStatus.FAILED, TaskStatus.CANCELLED ->
                return RecoveryPlan(RecoveryDecision.PERMANENTLY_FAILED, emptyList(), emptyList(), null, null, emptyList(), listOf("task ended ${task.status}"))
            TaskStatus.AWAITING_APPROVAL, TaskStatus.BLOCKED ->
                return RecoveryPlan(RecoveryDecision.REQUIRES_USER_INTERVENTION, emptyList(), emptyList(), null, firstOpenStep(snapshot), emptyList(),
                    listOf(if (task.status == TaskStatus.AWAITING_APPROVAL) "waiting for user approval" else "blocked: needs user action"))
            else -> Unit
        }

        val invocationMoves = ArrayList<InvocationRecovery>()
        val reconcile = ArrayList<String>()
        val reasons = ArrayList<String>()
        var retryable = false
        for (inv in snapshot.invocations) {
            when (inv.status) {
                InvocationStatus.PENDING ->
                    invocationMoves += InvocationRecovery(inv.invocationId, InvocationStatus.CANCELLED, "requested but never started; nothing ran")
                InvocationStatus.RUNNING, InvocationStatus.INTERRUPTED -> when (inv.effect) {
                    Effect.READ_ONLY -> {
                        retryable = true
                        if (inv.status == InvocationStatus.RUNNING)
                            invocationMoves += InvocationRecovery(inv.invocationId, InvocationStatus.INTERRUPTED, "read-only call interrupted; safe to repeat")
                    }
                    Effect.SIDE_EFFECT -> {
                        reconcile += inv.invocationId
                        invocationMoves += InvocationRecovery(inv.invocationId, InvocationStatus.UNKNOWN,
                            "interrupted before its result was stored; the side effect may or may not have happened")
                    }
                }
                InvocationStatus.UNKNOWN -> if (inv.effect == Effect.SIDE_EFFECT) reconcile += inv.invocationId
                else -> Unit
            }
        }
        val stepMoves = snapshot.currentSteps.filter { it.status == StepStatus.RUNNING }
            .map { StepRecovery(it.stepId, StepStatus.INTERRUPTED, "run interrupted while this step was running") }
        val decision = when {
            reconcile.isNotEmpty() -> RecoveryDecision.REQUIRES_RECONCILIATION
            retryable -> RecoveryDecision.SAFE_TO_RETRY
            else -> RecoveryDecision.SAFE_TO_RESUME
        }
        if (reconcile.isNotEmpty()) reasons += "unresolved side effects: ${reconcile.size}"
        if (retryable) reasons += "read-only calls can be repeated"
        val taskStatus = if (task.status == TaskStatus.INTERRUPTED) null else TaskStatus.INTERRUPTED
        return RecoveryPlan(decision, invocationMoves, stepMoves, taskStatus, firstOpenStep(snapshot), reconcile, reasons)
    }

    /** Earliest unfinished step of the latest plan revision whose dependencies are completed. */
    fun firstOpenStep(snapshot: TaskSnapshot): String? {
        val steps = snapshot.currentSteps
        val done = steps.filter { it.status == StepStatus.COMPLETED }.map { it.key }.toSet()
        return steps.firstOrNull { !it.status.terminal && it.dependsOnKeys.all { d -> d in done } }?.stepId
    }
}

data class RecoveryOutcome(val taskId: String, val plan: RecoveryPlan, val applied: Boolean)

/** Applies [TaskRecoveryPlanner] decisions. Every move is a guarded, idempotent transition, so recovery can itself be interrupted and re-run. */
class TaskRecovery(private val store: TaskStateStore, private val now: () -> Long = System::currentTimeMillis) {

    suspend fun recover(taskId: String): RecoveryOutcome? {
        val snapshot = store.snapshot(taskId) ?: return null
        val plan = TaskRecoveryPlanner.plan(snapshot)
        val at = now()
        plan.invocationTransitions.forEach { m ->
            store.transitionInvocation(m.invocationId, m.to, InvocationPatch(completedAt = null), at, "recover:${m.invocationId}:${m.to}", m.reason)
        }
        plan.stepTransitions.forEach { m -> store.transitionStep(m.stepId, m.to, m.reason, at, "recover:${m.stepId}:${m.to}") }
        plan.taskStatus?.let { store.transitionTask(taskId, it, "recovered: ${plan.decision}", at, "recover:$taskId:${snapshot.task.currentCycle}") }
        return RecoveryOutcome(taskId, plan, applied = true)
    }

    /** Startup/idle sweep: tasks that claim to be running but whose heartbeat is stale and that are not live in this process. */
    suspend fun recoverStale(staleAfterMs: Long, liveTaskIds: Set<String>, limit: Int = 50): List<RecoveryOutcome> {
        val cutoff = now() - staleAfterMs
        return store.tasksByStatus(setOf(TaskStatus.CREATED, TaskStatus.RUNNING), limit)
            .filter { it.taskId !in liveTaskIds && it.heartbeatAt < cutoff }
            .mapNotNull { recover(it.taskId) }
    }

    /**
     * Establishes the real outcome of an UNKNOWN/INTERRUPTED invocation. [verification] states how that was
     * established and is stored apart from the tool result, which never counts as verification by itself.
     */
    suspend fun reconcile(invocationId: String, outcome: InvocationStatus, verification: String): TransitionResult {
        require(outcome == InvocationStatus.SUCCEEDED || outcome == InvocationStatus.FAILED || outcome == InvocationStatus.CANCELLED) { "reconcile to a final outcome" }
        if (verification.isBlank()) return TransitionResult.Rejected("reconciliation needs a verification statement")
        val at = now()
        return store.transitionInvocation(invocationId, outcome, InvocationPatch(completedAt = at, verification = verification, isError = outcome != InvocationStatus.SUCCEEDED),
            at, "reconcile:$invocationId:$outcome", "reconciled")
    }
}

/**
 * Single-flight readiness barrier for the process-start recovery sweep.
 *
 * Runs may enqueue their initialization before recovery completes, but their first persistence flush waits
 * here. This guarantees the sweep sees only records left by an earlier process, never a task created by
 * the current process. A failed/cancelled sweep remains a failed barrier: callers must not assume readiness.
 */
internal class TaskRecoveryBarrier {
    private val lock = Any()
    private var readiness: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    fun startOnce(
        scope: kotlinx.coroutines.CoroutineScope,
        recovery: suspend () -> Unit,
        onFailure: (Throwable) -> Unit
    ): Boolean {
        val gate = synchronized(lock) {
            if (readiness != null) return false
            kotlinx.coroutines.CompletableDeferred<Unit>().also { readiness = it }
        }
        scope.launch {
            try {
                recovery()
                gate.complete(Unit)
            } catch (c: kotlinx.coroutines.CancellationException) {
                gate.cancel(c)
                throw c
            } catch (e: Exception) {
                gate.completeExceptionally(e)
                onFailure(e)
            }
        }
        return true
    }

    suspend fun awaitReady() {
        val gate = synchronized(lock) { readiness } ?: return
        gate.await()
    }
}

/**
 * Once per process, marks tasks left RUNNING by a previous process as INTERRUPTED and settles their open
 * invocations (read-only: interrupted; side effects: UNKNOWN). Nothing is executed or retried here.
 */
object TaskRecoveryBootstrap {
    private val barrier = TaskRecoveryBarrier()

    fun runOnce(store: TaskStateStore, scope: kotlinx.coroutines.CoroutineScope, log: (String) -> Unit) {
        barrier.startOnce(scope, recovery = {
            // Agent task creation is held behind this sweep, so no current-process task can enter this query.
            val outcomes = TaskRecovery(store).recoverStale(staleAfterMs = 0L, liveTaskIds = emptySet())
            if (outcomes.isNotEmpty()) log("recovered ${outcomes.size} interrupted task(s): ${outcomes.joinToString { it.plan.decision.name }}")
        }, onFailure = { e ->
            log("task recovery failed; agent task creation remains blocked: ${e.message}")
        })
    }

    /** Await before creating persistent task state or executing a side effect. */
    suspend fun awaitReady() = barrier.awaitReady()
}
