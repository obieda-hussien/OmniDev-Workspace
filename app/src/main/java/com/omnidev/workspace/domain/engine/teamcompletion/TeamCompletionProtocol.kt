package com.omnidev.workspace.domain.engine.teamcompletion

import com.omnidev.workspace.domain.engine.SwarmTask
import com.omnidev.workspace.domain.engine.completion.FinishStatus
import java.security.MessageDigest

/** Public operational result: no raw tool arguments, targets, results or private model reasoning. */
data class TeamMemberResult(val taskId: String, val status: FinishStatus, val attempt: Int,
    val evidenceCount: Int, val ledgerDigest: String, val outputDigest: String)
data class TeamCompletionReport(val runId: String, val revision: Long, val status: FinishStatus,
    val workers: List<TeamMemberResult>, val skipped: List<String>, val digest: String, val problems: List<String> = emptyList()) {
    val verified: Boolean get() = status == FinishStatus.COMPLETED
    val completed: Int get() = workers.count { it.status == FinishStatus.COMPLETED }
    val sessionStatus: String get() = if (verified) "Completed" else "Interrupted"
    fun render(): String = "Team verification: ${status.wire}; $completed/${workers.size} accepted; ${skipped.size} skipped. " +
        workers.joinToString("; ") { "${it.taskId}: ${it.status.wire} (attempt ${it.attempt}, ${it.evidenceCount} observations)" } +
        (if (problems.isEmpty()) "" else "; " + problems.joinToString()) + "; report=$digest"
}

/** Composes P0 worker verdicts. It neither replaces P0 nor interprets synthesis prose as proof. */
class TeamCompletionProtocol(val runId: String, val revision: Long, private val tasks: List<SwarmTask>, private val requireAction: Boolean = false) {
    private val receipts = linkedMapOf<String, WorkerReceipt>()
    private val active = mutableMapOf<String, WorkerAssignment>()
    private val restoredOrigins = mutableMapOf<String, Long>()
    init {
        require(tasks.size in 1..6 && tasks.map { it.id }.distinct().size == tasks.size)
        val ids = tasks.map { it.id }.toSet()
        require(tasks.all { t -> t.id.matches(Regex("[A-Za-z0-9_-]{1,64}")) &&
            com.omnidev.workspace.domain.planning.PlanningPrivacy.allowed(t.id) &&
            t.dependencies.distinct().size == t.dependencies.size && t.dependencies.all { it in ids && it != t.id } })
        val resolved = mutableSetOf<String>()
        repeat(tasks.size) { tasks.filter { t -> t.dependencies.all(resolved::contains) }.forEach { resolved += it.id } }
        require(resolved.size == tasks.size)
    }

    @Synchronized fun begin(task: SwarmTask, previousAttempt: Int = 0): WorkerAssignment {
        require(matches(task))
        require(task.dependencies.all { receipts[it]?.accepted == true })
        require(receipts[task.id]?.accepted != true)
        val promoted = tasks.single { it.id == task.id }.parallelSafe && !task.parallelSafe
        val kind = if (!task.toolFree && (promoted || requiresAction(task.description)))
            com.omnidev.workspace.domain.engine.completion.TaskKind.IMPLEMENTATION else com.omnidev.workspace.domain.engine.completion.TaskKind.ANALYSIS
        val assignment = WorkerAssignment(runId, revision, task, maxOf(active[task.id]?.attempt ?: 0, previousAttempt) + 1, kind)
        active[task.id] = assignment
        return assignment
    }

    /** Only the durable runtime calls this seam; model-authored JSON never enters it. Re-runs P5/P0 review. */
    @Synchronized internal fun restore(checkpoint: com.omnidev.workspace.domain.teamstate.WorkerCheckpoint, originRevision: Long? = null): Boolean {
        val a = checkpoint.assignment
        if (a.teamRunId != runId || (a.revision != revision && (originRevision != a.revision || a.revision >= revision)) || !matches(a.task) ||
            a.attempt !in 1..com.omnidev.workspace.domain.teamstate.TeamStateEngine.MAX_ATTEMPTS ||
            a.task.dependencies.any { receipts[it]?.accepted != true } || receipts[a.task.id]?.accepted == true) return false
        val observer = WorkerCompletionObserver(a)
        observer.restore(checkpoint.observations)
        val receipt = observer.review(checkpoint.report, checkpoint.output)
        if (!receipt.accepted || receipt.ledgerDigest != checkpoint.ledgerDigest || receipt.outputDigest != checkpoint.outputDigest) return false
        active[a.task.id] = a
        if (a.revision != revision) restoredOrigins[a.task.id] = a.revision
        return accept(receipt, checkpoint.output)
    }

    private fun matches(task: SwarmTask) = tasks.any {
        it.copy(parallelSafe = task.parallelSafe) == task && (!task.parallelSafe || it.parallelSafe)
    }

    /** Rejected or stale attempts cannot unlock dependencies. Duplicate delivery is idempotent. */
    @Synchronized fun accept(receipt: WorkerReceipt, output: String): Boolean {
        val a = receipt.assignment
        if (a.teamRunId != runId || (a.revision != revision && restoredOrigins[a.task.id] != a.revision) || active[a.task.id] !== a || !matches(a.task)) return false
        if (receipt.accepted && receipt.outputDigest != digest(output)) return false
        val previous = receipts[a.task.id]
        if (previous == receipt) return true
        if (previous?.accepted == true || a.task.dependencies.any { receipts[it]?.accepted != true }) return false
        receipts[a.task.id] = receipt
        return true
    }

    @Synchronized fun ready(taskId: String): Boolean = tasks.firstOrNull { it.id == taskId }?.let { t ->
        receipts[t.id]?.accepted != true && t.dependencies.all { receipts[it]?.accepted == true }
    } ?: false

    @Synchronized fun finish(failed: Set<String>, skipped: Set<String>): TeamCompletionReport {
        val members = tasks.map { task ->
            val r = receipts[task.id]
            val status = if (r?.accepted == true && task.id !in failed && task.id !in skipped) FinishStatus.COMPLETED
                else r?.status?.takeIf { it != FinishStatus.COMPLETED } ?: FinishStatus.INCOMPLETE
            TeamMemberResult(task.id, status, r?.assignment?.attempt ?: active[task.id]?.attempt ?: 0,
                r?.evidenceCount ?: 0, r?.ledgerDigest.orEmpty(), r?.outputDigest.orEmpty())
        }
        val problems = if (requireAction && receipts.values.none { it.accepted &&
            it.assignment.kind == com.omnidev.workspace.domain.engine.completion.TaskKind.IMPLEMENTATION })
            listOf("ORIGINAL_OBJECTIVE_REQUIRES_VERIFIED_ACTION") else emptyList()
        val status = when {
            members.all { it.status == FinishStatus.COMPLETED } && failed.isEmpty() && skipped.isEmpty() && problems.isEmpty() -> FinishStatus.COMPLETED
            members.any { it.status == FinishStatus.AWAITING_APPROVAL } -> FinishStatus.AWAITING_APPROVAL
            members.any { it.status == FinishStatus.BLOCKED } -> FinishStatus.BLOCKED
            members.any { it.status == FinishStatus.FAILED } -> FinishStatus.FAILED
            else -> FinishStatus.INCOMPLETE
        }
        val skippedKeys = skipped.sorted()
        val canonical = listOf(runId, revision.toString(), status.wire) + members.flatMap {
            listOf(it.taskId, it.status.wire, it.attempt.toString(), it.evidenceCount.toString(), it.ledgerDigest, it.outputDigest)
        } + skippedKeys + problems
        return TeamCompletionReport(runId, revision, status, members, skippedKeys,
            digest(canonical.joinToString("") { "${it.length}:$it" }), problems)
    }

    companion object {
        // Imperative execution is distinct from reading test/build reports or researching how to run them.
        private val executionDirective = Regex("(?i)(?:^|[|:;\\n]|\\b(?:and|then)\\s+)(?:\\s*(?:goal|steps|please|first|next|now)[:\\s]+)*\\s*(?:run|execute|compile|build|lint|benchmark|launch|invoke|test|start|stop|send|شغل|شغّل|نفذ|نفّذ|اختبر)(?![\\p{L}\\p{N}_])")

        fun requiresAction(objective: String): Boolean =
            com.omnidev.workspace.domain.engine.IntentClassifier.analyze(objective).mutationIntent >= .16f ||
                executionDirective.containsMatchIn(objective)

        private val selfContained = Regex("(?i)(?<![\\p{L}\\p{N}_])(?:" +
            (com.omnidev.workspace.domain.engine.IntentClassifier.CHAT_PHRASES.map { Regex.escape(it.text) } +
                listOf("summarize", "summarise", "synthesize", "synthesise")).joinToString("|") + ")(?![\\p{L}\\p{N}_])")

        fun toolFreeAllowed(objective: String): Boolean {
            val s = com.omnidev.workspace.domain.engine.IntentClassifier.analyze(objective)
            return !requiresAction(objective) && selfContained.containsMatchIn(objective) && s.mutationIntent < .16f &&
                s.executionIntent < .34f && s.deviceIntent < .15f && s.researchIntent == 0f && s.verificationIntent < .4f
        }

        fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
