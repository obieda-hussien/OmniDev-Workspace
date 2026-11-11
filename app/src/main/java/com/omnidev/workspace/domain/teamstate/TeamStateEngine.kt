package com.omnidev.workspace.domain.teamstate

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.domain.engine.*
import com.omnidev.workspace.domain.engine.completion.CompletionReport
import com.omnidev.workspace.domain.engine.teamcompletion.*
import com.omnidev.workspace.domain.taskstate.TaskStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Durable control plane for the existing SwarmOrchestrator. It never executes or approves tools. */
class TeamStateEngine internal constructor(private val backend: TeamStateBackend,
    private val now: () -> Long = System::currentTimeMillis) {
    companion object {
        const val MAX_ATTEMPTS = 6 // includes bounded read-to-serial promotion
        const val MAX_REVISIONS = 8
        const val TOKEN_LIMIT = 2_000_000
        const val LEASE_MS = 45_000L
        fun inMemory(now: () -> Long = System::currentTimeMillis) = TeamStateEngine(MemoryTeamBackend(), now)
    }

    internal suspend fun load(id: String) = backend.read(id)
    internal suspend fun create(id: String, sessionId: Long?, objective: String, scopePath: String, owner: String): TeamRecord =
        backend.change(id) { old ->
            require(old == null) { "Team already exists" }
            require(objective.isNotBlank() && objective.length <= 24_000 && scopePath.length <= 2_000)
            val safe = SensitiveObservationRedactor.redact(objective)
            TeamRecord(id, sessionId, safe, scopePath, owner = owner, leaseUntil = now() + LEASE_MS,
                status = TaskStatus.RUNNING, createdAt = now())
        }

    internal suspend fun claim(id: String, sessionId: Long?, owner: String): TeamRecord = backend.change(id) { old ->
        val record = requireNotNull(old) { "Saved Team unavailable" }
        require(record.sessionId == sessionId) { "Team belongs to another conversation" }
        require(record.owner == null || record.leaseUntil <= now()) { "Team coordinator is still active; wait for its lease" }
        require(record.status !in setOf(TaskStatus.COMPLETED, TaskStatus.CANCELLED)) { "Team already ended" }
        val blockers = recoveryBlockers(record)
        require(blockers.isEmpty()) { blockers.joinToString("; ") }
        record.copy(owner = owner, leaseUntil = now() + LEASE_MS, status = TaskStatus.RUNNING,
            generation = record.generation + 1, updatedAt = now())
    }

    private suspend fun owned(id: String, owner: String, transform: (TeamRecord) -> TeamRecord) = backend.change(id) { old ->
        val r = requireNotNull(old)
        check(r.owner == owner && r.leaseUntil > now()) { "Team coordinator lease lost" }
        transform(r).copy(updatedAt = now())
    }
    internal suspend fun heartbeat(id: String, owner: String) { owned(id, owner) { it.copy(leaseUntil = now() + LEASE_MS) } }
    internal suspend fun reserve(id: String, owner: String, tokens: Int) {
        require(tokens > 0)
        owned(id, owner) { r ->
            check(r.reservedTokens <= TOKEN_LIMIT - tokens) { "Durable Team token ceiling reached" }
            r.copy(reservedTokens = r.reservedTokens + tokens)
        }
    }
    internal suspend fun savePlan(id: String, owner: String, revision: Long, objective: String, tasks: List<SwarmTask>) {
        // Validate with exactly the production P5 graph validator before committing.
        TeamCompletionProtocol(id, revision, tasks)
        require(tasks.all { it.description.length <= 6_000 && it.acceptance.size <= 24 && it.acceptance.all { a -> a.length <= 1_000 } })
        require(objective.length <= 24_000)
        owned(id, owner) { r ->
            val existing = r.plans.firstOrNull { it.revision == revision }
            if (existing != null) { check(existing.tasks == tasks && existing.objective == objective); r }
            else {
                check(r.plans.size < MAX_REVISIONS && (r.plan == null || revision > r.revision)) { "Invalid or excessive Team revision" }
                check(r.attempts.none { a -> a.operations.any { !it.readOnly && !it.notExecuted } && a.checkpoint == null }) {
                    "Resolve prior side effects before changing the Team plan"
                }
                // Only an unchanged objective and identical dependency-closed worker contracts permit carry-forward.
                // A real user redirect retains history but invalidates acceptance until the new contracts are verified.
                val compatible = if (objective == r.objective) tasks.filter { task ->
                    r.plan?.tasks?.contains(task) == true && accepted(r, task.id)
                }.mapTo(mutableSetOf()) { it.id } else mutableSetOf()
                repeat(tasks.size) { compatible.removeAll { key -> tasks.single { it.id == key }.dependencies.any { it !in compatible } } }
                val carried = checkpoints(r).filter { it.assignment.task.id in compatible }
                    .associate { it.assignment.task.id to it.assignment.revision }
                r.copy(revision = revision, objective = SensitiveObservationRedactor.redact(objective), plans = r.plans +
                    TeamPlan(revision, SensitiveObservationRedactor.redact(objective), tasks, if (r.plan == null) "initial" else "plan revision", carried))
            }
        }
    }
    internal suspend fun begin(id: String, owner: String, assignment: WorkerAssignment, tokens: Int) {
        owned(id, owner) { r ->
            check(assignment.teamRunId == id && assignment.revision == r.revision)
            val task = r.plan?.tasks?.singleOrNull { it.id == assignment.task.id } ?: error("Unknown worker")
            check(task.copy(parallelSafe = assignment.task.parallelSafe) == assignment.task && (!assignment.task.parallelSafe || task.parallelSafe))
            check(task.dependencies.all { accepted(r, it) }) { "Unverified dependency" }
            val old = r.currentAttempts.filter { it.assignment.task.id == task.id }
            check(!accepted(r, task.id) && assignment.attempt == (old.maxOfOrNull { it.assignment.attempt } ?: 0) + 1 && assignment.attempt <= MAX_ATTEMPTS)
            check(old.none { unsafe(it) }) { "Prior worker requires intervention or exact reconciliation" }
            check(tokens > 0 && r.reservedTokens <= TOKEN_LIMIT - tokens) { "Durable Team token ceiling reached" }
            r.copy(attempts = r.attempts + TeamWorkerAttempt(assignment), reservedTokens = r.reservedTokens + tokens)
        }
    }
    private suspend fun attempt(id: String, owner: String, a: WorkerAssignment, change: (TeamWorkerAttempt) -> TeamWorkerAttempt) {
        owned(id, owner) { r ->
            check(a.revision == r.revision)
            val latest = r.currentAttempts.lastOrNull { it.assignment.task.id == a.task.id } ?: error("Missing worker")
            check(latest.assignment == a && latest.checkpoint == null) { "Stale or completed worker attempt" }
            r.copy(attempts = r.attempts.map { if (it.assignment == a) change(it) else it })
        }
    }
    internal suspend fun beforeTool(id: String, owner: String, a: WorkerAssignment, call: ToolCall) {
        require(call.id.isNotBlank() && call.id.length <= 160 && call.name.length <= 160)
        attempt(id, owner, a) { w ->
            check(w.operations.size < 200 && w.operations.none { it.callId == call.id }) { "Duplicate or excessive operation" }
            check(w.status == TaskStatus.RUNNING)
            w.copy(stage = "TOOL_RUNNING", operations = w.operations + TeamOperation(call.id, call.name, ToolBatchPolicy.isReadOnly(call)))
        }
    }
    internal suspend fun observed(id: String, owner: String, a: WorkerAssignment, event: AgentEvent.ToolResult) {
        val ref = event.evidence ?: return
        attempt(id, owner, a) { w ->
            check(ref.runId == a.identity.runId && ref.workerId == a.task.id)
            val intervention = event.classification in setOf("USER_ACTION_REQUIRED", "CONFIRMATION_REQUIRED", "PERMISSION_DENIED",
                "GITHUB_AUTH_REQUIRED", "GITHUB_PERMISSION_DENIED", "TIER_DENIED", "USER_DENIED", "USER_ACTION_DENIED")
            w.copy(stage = "TOOL_RETURNED", intervention = w.intervention || intervention,
                operations = w.operations.map { op -> if (op.callId != ref.callId) op else op.copy(returned = true,
                    succeeded = !event.isError, classification = event.classification.orEmpty().take(120)) })
        }
    }
    internal suspend fun finishWorker(id: String, owner: String, a: WorkerAssignment, observer: WorkerCompletionObserver,
        report: CompletionReport?, output: String, receipt: WorkerReceipt, error: String?) {
        require(output.length <= 48_000) { "Worker output exceeds durable checkpoint bound" }
        require(receipt.assignment === a)
        val reviewed = observer.review(report, output)
        require(receipt.accepted == reviewed.accepted && receipt.ledgerDigest == reviewed.ledgerDigest && receipt.outputDigest == reviewed.outputDigest)
        attempt(id, owner, a) { w ->
            val checkpoint = if (error == null && receipt.accepted) WorkerCheckpoint(a, observer.snapshot(), report, output,
                receipt.ledgerDigest, receipt.outputDigest) else null
            w.copy(status = if (checkpoint != null) TaskStatus.COMPLETED else TaskStatus.INTERRUPTED,
                stage = if (checkpoint != null) "P0_P5_VERIFIED" else "INTERRUPTED", checkpoint = checkpoint,
                failure = error?.let { "${TeamFailurePolicy.classify(it)}: ${SensitiveObservationRedactor.redact(it).take(260)}" }.orEmpty(),
                intervention = w.intervention || receipt.status in setOf(com.omnidev.workspace.domain.engine.completion.FinishStatus.AWAITING_APPROVAL,
                    com.omnidev.workspace.domain.engine.completion.FinishStatus.BLOCKED))
        }
    }
    internal suspend fun finish(id: String, owner: String, report: TeamCompletionReport) {
        owned(id, owner) { r ->
            check(report.runId == id && report.revision == r.revision)
            if (report.verified) check(r.plan!!.tasks.all { accepted(r, it.id) }) { "Missing durable worker proof" }
            r.copy(status = if (report.verified) TaskStatus.COMPLETED else TaskStatus.INTERRUPTED, finalDigest = report.digest)
        }
    }
    internal suspend fun release(id: String, owner: String) {
        backend.change(id) { old ->
            val r = requireNotNull(old)
            if (r.owner != owner) r else r.copy(owner = null, leaseUntil = 0,
                status = if (r.status == TaskStatus.RUNNING) TaskStatus.INTERRUPTED else r.status, updatedAt = now())
        }
    }

    suspend fun summaries(sessionId: Long): List<TeamRecoverySummary> = backend.forSession(sessionId, 20).map { r ->
        val active = r.owner != null && r.leaseUntil > now()
        val blockers = recoveryBlockers(r) + if (active) listOf("Coordinator active or awaiting lease expiry") else emptyList()
        TeamRecoverySummary(r.id, r.sessionId, r.originalObjective, r.objective, r.revision,
            if (!active && r.status == TaskStatus.RUNNING) "INTERRUPTED" else r.status.name,
            r.plan?.tasks.orEmpty().map { task ->
                val a = r.currentAttempts.lastOrNull { it.assignment.task.id == task.id }
                val proof = accepted(r, task.id)
                val blocked = task.dependencies.filterNot { accepted(r, it) }
                TeamWorkerRecovery(task.id, if (proof) "COMPLETED" else if (active && a?.status == TaskStatus.RUNNING) "RUNNING"
                    else if (blocked.isNotEmpty()) "BLOCKED" else a?.status?.name?.takeUnless { it == "RUNNING" } ?: "READY",
                    a?.assignment?.attempt ?: 0, task.dependencies, a?.operations?.lastOrNull()?.tool,
                    if (blocked.isNotEmpty()) "Waiting for verified dependencies: ${blocked.joinToString()}" else a?.failure?.takeIf { it.isNotBlank() },
                    a?.operations.orEmpty().filter { !it.readOnly && !it.notExecuted && a?.checkpoint == null }.map { it.callId }, a?.assignment?.identity?.runId,
                    a?.operations.orEmpty().filter { !it.readOnly && !it.notExecuted && !it.succeeded && a?.intervention != true && a?.checkpoint == null }.map { it.callId })
            }, !active && blockers.isEmpty() && r.status !in setOf(TaskStatus.COMPLETED, TaskStatus.CANCELLED), blockers, r.scopePath)
    }

    /** Explicit exact-operation reconciliation. A read/result alone cannot call this API. Successful writes stay non-replayable. */
    suspend fun reconcileNotExecuted(id: String, sessionId: Long, attemptRunId: String, callId: String, verification: String) {
        require(verification.trim().length in 20..1_000)
        backend.change(id) { old ->
            val r = requireNotNull(old)
            require(r.sessionId == sessionId && (r.owner == null || r.leaseUntil <= now()))
            val a = r.attempts.single { it.assignment.identity.runId == attemptRunId }
            require(a.checkpoint == null && !a.intervention)
            val op = a.operations.single { it.callId == callId }
            require(!op.readOnly && !op.succeeded) { "A recorded successful action cannot be retried" }
            r.copy(attempts = r.attempts.map { if (it != a) it else a.copy(operations = a.operations.map {
                if (it.callId != callId) it else it.copy(notExecuted = true, reconciliation = SensitiveObservationRedactor.redact(verification))
            }) }, updatedAt = now())
        }
    }
    suspend fun cancel(id: String, sessionId: Long) { backend.change(id) { old ->
        val r = requireNotNull(old)
        require(r.sessionId == sessionId && (r.owner == null || r.leaseUntil <= now()) && r.status != TaskStatus.COMPLETED)
        r.copy(status = TaskStatus.CANCELLED, owner = null, leaseUntil = 0, updatedAt = now())
    } }

    internal fun recoveryBlockers(r: TeamRecord): List<String> = buildList {
        r.currentAttempts.groupBy { it.assignment.task.id }.forEach { (id, attempts) ->
            if (accepted(r, id)) return@forEach
            if (attempts.any { it.checkpoint != null }) add("$id: saved receipt failed verification; inspect checkpoint")
            if (attempts.any { unsafe(it) }) add("$id: review side effects or required approval; no automatic replay")
            if (attempts.size >= MAX_ATTEMPTS) add("$id: attempt limit reached")
        }
        // Unknown operations from old revisions are still barriers.
        if (r.attempts.any { it.assignment.revision != r.revision && unsafe(it) }) add("A prior revision has unresolved effects")
        if (r.reservedTokens >= TOKEN_LIMIT - 40_000) add("Durable token ceiling reached")
    }
    private fun unsafe(a: TeamWorkerAttempt) = a.checkpoint == null && (a.intervention || a.operations.any { !it.readOnly && !it.notExecuted })
    internal fun checkpoints(r: TeamRecord): List<WorkerCheckpoint> {
        val current = r.currentAttempts.mapNotNull { it.checkpoint }
        val carried = r.plan?.carriedReceipts.orEmpty().map { (key, revision) ->
            r.attempts.single { it.assignment.task.id == key && it.assignment.revision == revision && it.checkpoint != null }.checkpoint!!
        }
        return current + carried
    }
    internal fun accepted(r: TeamRecord, id: String): Boolean {
        val p = r.plan ?: return false
        val protocol = TeamCompletionProtocol(r.id, r.revision, p.tasks, TeamCompletionProtocol.requiresAction(r.originalObjective))
        val remaining = checkpoints(r).toMutableList()
        repeat(p.tasks.size) {
            remaining.toList().forEach { c -> if (protocol.restore(c, p.carriedReceipts[c.assignment.task.id])) remaining.remove(c) }
        }
        return !protocol.ready(id) && protocol.finish(emptySet(), emptySet()).workers.any { it.taskId == id && it.status == com.omnidev.workspace.domain.engine.completion.FinishStatus.COMPLETED }
    }
}

private class MemoryTeamBackend : TeamStateBackend {
    private val mutex = Mutex()
    private val records = mutableMapOf<String, TeamRecord>()
    override suspend fun read(id: String) = mutex.withLock { records[id] }
    override suspend fun change(id: String, transform: (TeamRecord?) -> TeamRecord) = mutex.withLock {
        transform(records[id]).also { check(it.id == id); records[id] = it }
    }
    override suspend fun forSession(sessionId: Long, limit: Int) = mutex.withLock {
        records.values.filter { it.sessionId == sessionId }.sortedByDescending { it.updatedAt }.take(limit)
    }
}
