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
    internal suspend fun assertMayPlan(id: String, owner: String) {
        owned(id, owner) { r ->
            check(r.plans.size < MAX_REVISIONS) { "Team revision limit reached" }
            check(r.attempts.none { unsafe(it) }) { "Review unresolved effects or approvals before asking for a new plan" }
            r
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
            val fields = listOf("action", "operation", "repo", "repository", "path", "filePath", "file_path", "directory", "endpoint")
                .mapNotNull { key -> call.arguments[key]?.let { "$key=${SensitiveObservationRedactor.redact(it).take(160)}" } }.take(4)
            val target = com.omnidev.workspace.domain.engine.completion.EvidenceKinds.commandTarget(call.name, call.arguments)
                ?.let { "target=${SensitiveObservationRedactor.redact(it).take(160)}" }
            val intent = (fields + listOfNotNull(target)).joinToString("; ").take(700)
            w.copy(stage = "TOOL_RUNNING", operations = w.operations + TeamOperation(call.id, call.name, ToolBatchPolicy.isReadOnly(call), intentSummary = intent))
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
                    succeeded = !event.isError, classification = event.classification.orEmpty().take(120),
                    evidence = ref, observation = SensitiveObservationRedactor.redact(event.output).take(600)) })
        }
    }
    internal suspend fun finishWorker(id: String, owner: String, a: WorkerAssignment, observer: WorkerCompletionObserver,
        report: CompletionReport?, output: String, receipt: WorkerReceipt, error: String?) {
        require(output.length <= 48_000) { "Worker output exceeds durable checkpoint bound" }
        require(receipt.assignment === a)
        require(SensitiveObservationRedactor.redact(output) == output) { "Worker output must be redacted before checkpointing" }
        require(report == null || (report.criteria.size <= 64 && report.evidence.size <= 200 && report.problems.size <= 64))
        val safeReport = report?.copy(summary = SensitiveObservationRedactor.redact(report.summary).take(2_000),
            criteria = report.criteria.map { it.copy(criterion = it.criterion.copy(text = SensitiveObservationRedactor.redact(it.criterion.text).take(1_000)),
                reason = it.reason?.let { reason -> SensitiveObservationRedactor.redact(reason).take(300) }) },
            problems = report.problems.map { SensitiveObservationRedactor.redact(it).take(300) })
        val reviewed = observer.review(safeReport, output)
        require(receipt.accepted == reviewed.accepted && receipt.ledgerDigest == reviewed.ledgerDigest && receipt.outputDigest == reviewed.outputDigest)
        attempt(id, owner, a) { w ->
            val checkpoint = if (error == null && receipt.accepted) WorkerCheckpoint(a, observer.snapshot(), safeReport, output,
                receipt.ledgerDigest, receipt.outputDigest) else null
            w.copy(status = if (checkpoint != null) TaskStatus.COMPLETED
                else if (w.intervention || receipt.status == com.omnidev.workspace.domain.engine.completion.FinishStatus.AWAITING_APPROVAL) TaskStatus.AWAITING_APPROVAL
                else TaskStatus.INTERRUPTED,
                stage = if (checkpoint != null) "P0_P5_VERIFIED" else "INTERRUPTED", checkpoint = checkpoint,
                failure = error?.let { "${TeamFailurePolicy.classify(it)}: ${SensitiveObservationRedactor.redact(it).take(260)}" }.orEmpty(),
                intervention = w.intervention || (checkpoint == null && observer.unsafeToRetry && w.operations.none { !it.readOnly }) || receipt.status in setOf(com.omnidev.workspace.domain.engine.completion.FinishStatus.AWAITING_APPROVAL,
                    com.omnidev.workspace.domain.engine.completion.FinishStatus.BLOCKED))
        }
    }
    internal suspend fun finish(id: String, owner: String, report: TeamCompletionReport, summary: String = "") {
        owned(id, owner) { r ->
            check(report.runId == id && report.revision == r.revision)
            if (report.verified) {
                val protocol = restoredProtocol(r)
                check(protocol.finish(emptySet(), emptySet()).digest == report.digest) { "Team report disagrees with durable P0/P5 receipts" }
            }
            r.copy(status = when (report.status) {
                com.omnidev.workspace.domain.engine.completion.FinishStatus.COMPLETED -> TaskStatus.COMPLETED
                com.omnidev.workspace.domain.engine.completion.FinishStatus.AWAITING_APPROVAL -> TaskStatus.AWAITING_APPROVAL
                com.omnidev.workspace.domain.engine.completion.FinishStatus.BLOCKED -> TaskStatus.BLOCKED
                else -> TaskStatus.INCOMPLETE
            }, finalDigest = report.digest,
                verifiedSummary = if (report.verified) SensitiveObservationRedactor.redact(summary).take(24_000) else "")
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
        val verifiedCompletion = r.status == TaskStatus.COMPLETED && r.plan?.tasks?.all { accepted(r, it.id) } == true
        TeamRecoverySummary(r.id, r.sessionId, r.originalObjective, r.objective, r.revision,
            if (r.status == TaskStatus.COMPLETED && !verifiedCompletion) "BLOCKED"
                else if (!active && r.status == TaskStatus.RUNNING) "INTERRUPTED" else r.status.name,
            r.plan?.tasks.orEmpty().map { task ->
                val a = r.currentAttempts.lastOrNull { it.assignment.task.id == task.id } ?:
                    r.attempts.lastOrNull { it.assignment.task.id == task.id && it.assignment.revision == r.plan?.carriedReceipts?.get(task.id) }
                val proof = accepted(r, task.id)
                val blocked = task.dependencies.filterNot { accepted(r, it) }
                TeamWorkerRecovery(task.id, if (proof) "COMPLETED" else if (active && a?.status == TaskStatus.RUNNING) "RUNNING"
                    else if (blocked.isNotEmpty()) "BLOCKED" else if (a?.status == TaskStatus.RUNNING) "INTERRUPTED" else a?.status?.name ?: "READY",
                    a?.assignment?.attempt ?: 0, task.dependencies, a?.operations?.lastOrNull()?.tool,
                    if (blocked.isNotEmpty()) "Waiting for verified dependencies: ${blocked.joinToString()}" else a?.failure?.takeIf { it.isNotBlank() },
                    a?.operations.orEmpty().filter { !it.readOnly && !it.notExecuted && a?.checkpoint == null }.map { it.callId }, a?.assignment?.identity?.runId,
                    a?.operations.orEmpty().filter { !it.readOnly && !it.notExecuted && !it.succeeded && a?.intervention != true && a?.checkpoint == null }.map { it.callId }, a?.intervention == true,
                    a?.operations?.lastOrNull { it.succeeded && it.evidence?.outcome == RunEvidenceLedger.Outcome.OK }?.tool, task.description, task.acceptance,
                    a?.operations.orEmpty().filter { !it.readOnly && !it.notExecuted && a?.checkpoint == null }.associate { it.callId to it.intentSummary })
            }, !active && blockers.isEmpty() && r.status !in setOf(TaskStatus.COMPLETED, TaskStatus.CANCELLED), blockers, r.scopePath, if (verifiedCompletion) r.verifiedSummary else "")
    }

    /** Explicit exact-operation reconciliation. A read/result alone cannot call this API. Successful writes stay non-replayable. */
    suspend fun reconcileNotExecuted(id: String, sessionId: Long, attemptRunId: String, callId: String, verification: String) {
        require(verification.length <= 1_000 && verification.trim().length >= 20)
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
    suspend fun reviewIntervention(id: String, sessionId: Long, attemptRunId: String) {
        backend.change(id) { old ->
            val r = requireNotNull(old)
            require(r.sessionId == sessionId && (r.owner == null || r.leaseUntil <= now()))
            val a = r.attempts.single { it.assignment.identity.runId == attemptRunId }
            require(a.intervention && a.checkpoint == null && a.operations.none { !it.readOnly && !it.notExecuted }) {
                "Side effects must be reconciled before retrying"
            }
            r.copy(attempts = r.attempts.map { if (it != a) it else a.copy(intervention = false,
                status = TaskStatus.INTERRUPTED, failure = "User requested fresh authorization/backend validation") }, updatedAt = now())
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
    internal fun restoredProtocol(r: TeamRecord): TeamCompletionProtocol {
        val p = requireNotNull(r.plan)
        val protocol = TeamCompletionProtocol(r.id, r.revision, p.tasks,
            r.revision == 0L && TeamCompletionProtocol.requiresAction(r.originalObjective))
        val remaining = checkpoints(r).toMutableList()
        repeat(p.tasks.size) {
            remaining.toList().forEach { c -> if (protocol.restore(c, p.carriedReceipts[c.assignment.task.id])) remaining.remove(c) }
        }
        check(remaining.isEmpty()) { "Saved Team receipt failed verification" }
        return protocol
    }
    internal fun accepted(r: TeamRecord, id: String): Boolean {
        if (r.plan == null) return false
        return try { restoredProtocol(r).finish(emptySet(), emptySet()).workers.any {
            it.taskId == id && it.status == com.omnidev.workspace.domain.engine.completion.FinishStatus.COMPLETED
        } } catch (_: IllegalStateException) { false } catch (_: IllegalArgumentException) { false }
    }

}

private class MemoryTeamBackend : TeamStateBackend {
    private val mutex = Mutex()
    private val records = mutableMapOf<String, String>()
    override suspend fun read(id: String) = mutex.withLock { records[id]?.let(TeamPayloadCodec::decode) }
    override suspend fun change(id: String, transform: (TeamRecord?) -> TeamRecord) = mutex.withLock {
        transform(records[id]?.let(TeamPayloadCodec::decode)).also { check(it.id == id); records[id] = TeamPayloadCodec.encode(it) }
    }
    override suspend fun forSession(sessionId: Long, limit: Int) = mutex.withLock {
        records.values.map(TeamPayloadCodec::decode).filter { it.sessionId == sessionId }.sortedByDescending { it.updatedAt }.take(limit)
    }
}
