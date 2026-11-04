package com.omnidev.workspace.domain.taskstate

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.domain.engine.RunEvidenceLedger
import com.omnidev.workspace.domain.engine.completion.AcceptanceCriterion
import com.omnidev.workspace.domain.engine.completion.FinishStatus
import com.omnidev.workspace.domain.engine.completion.GateResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.UUID

sealed class RunGate {
    data object Proceed : RunGate()
    /** The call must not execute. [output] is returned to the model as the tool result. */
    data class Blocked(val output: String, val classification: String) : RunGate()
}

/**
 * Pipeline-facing writer of persistent task state. It records, in this order and as separate layers:
 * the plan, the requested invocation, the write-ahead RUNNING mark, the returned result and, only through the
 * P0 finish gate, the verified outcome. It never decides completion: [finalize] stores what the gate decided.
 *
 * Writes that do not need to precede execution are queued and flushed at the next suspension point; the
 * write-ahead mark of a side-effecting call is awaited and, if it cannot be stored, the call does not run.
 */
class TaskRunRecorder(
    private val store: TaskStateStore,
    val taskId: String,
    private val sessionId: Long?,
    private val originalRequest: String,
    private val parentTaskId: String?,
    private val workerId: String?,
    private val acceptance: List<String>,
    private val resumed: Boolean,
    private val effectOf: (ToolCall) -> Effect,
    private val redact: (String) -> String = { it },
    private val clock: () -> Long = System::currentTimeMillis,
    private val schemaIdOf: (String) -> String = { it }
) {
    private val lock = Mutex()
    /** Own scope: persistence of the verdict must survive the run's coroutine being cancelled. */
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val inFlight = ArrayList<kotlinx.coroutines.Job>()
    private val pending = ArrayList<suspend () -> Unit>()
    private val runToken = UUID.randomUUID().toString().take(8)
    private var turn = 0
    private var cycleId: String? = null
    private var cycleNotes = StringBuilder()
    private var steps: List<StepRecord> = emptyList()
    private val invocationByCall = HashMap<String, String>()
    private val running = HashSet<String>()
    private val done = HashSet<String>()
    private val stepStarted = HashSet<String>()

    @Volatile var finalized = false
        private set
    /** True once any persistence write failed. The run continues, but its durability is no longer guaranteed. */
    @Volatile var degraded = false
        private set
    @Volatile var lastError: String? = null
        private set

    private suspend fun <T> safely(block: suspend () -> T): T? = try { block() } catch (c: CancellationException) { throw c } catch (e: Exception) {
        degraded = true
        lastError = redact(e.message ?: e.javaClass.simpleName).take(300)
        null
    }

    private fun queue(op: suspend () -> Unit) { synchronized(pending) { pending += op } }
    private suspend fun flush() {
        val ops = synchronized(pending) { pending.toList().also { pending.clear() } }
        ops.forEach { safely { it() } }
    }

    // ---- non-suspending entry points (queued) ----

    fun start(objective: String) {
        queue {
            val at = clock()
            if (!resumed) {
                store.createTask(NewTask(taskId, sessionId, originalRequest, "", parentTaskId, workerId, "{}", at))
                if (objective.isNotBlank() && objective != originalRequest) store.setObjective(taskId, objective.take(2000), at)
                store.transitionTask(taskId, TaskStatus.RUNNING, "run started", at, "start:$runToken")
                store.appendPlanRevision(taskId, initialSteps(objective), "initial plan", at, "plan:initial")
            } else {
                store.transitionTask(taskId, TaskStatus.RUNNING, "run resumed", at, "start:$runToken")
            }
            steps = store.snapshot(taskId)?.currentSteps.orEmpty()
        }
    }

    fun beginTurn() {
        val n = ++turn
        queue {
            endOpenCycle("continued")
            val c = store.beginCycle(taskId, nextStepId(), clock(), "cycle:$runToken:$n")
            cycleId = c.cycleId
            cycleNotes = StringBuilder()
        }
    }

    /** Records a new plan revision (for example after the user redirected the run). Earlier revisions stay intact. */
    fun replan(reason: String, objective: String) {
        queue {
            store.appendPlanRevision(taskId, initialSteps(objective), reason, clock(), "plan:$runToken:${reason.hashCode()}:$turn")
            steps = store.snapshot(taskId)?.currentSteps.orEmpty()
        }
    }

    private fun initialSteps(objective: String): List<NewStep> =
        if (acceptance.isNotEmpty()) acceptance.mapIndexed { i, text -> NewStep("A${i + 1}", text.take(300), expectedOutput = "Evidence of: ${text.take(120)}", completionCondition = "P0 finish gate accepts this item with evidence") }
        else listOf(NewStep("S1", "Complete the objective: ${objective.replace(Regex("\\s+"), " ").take(300)}", completionCondition = "P0 finish gate accepts completion"))

    private fun nextStepId(): String? {
        val done = steps.filter { it.status == StepStatus.COMPLETED }.map { it.key }.toSet()
        return steps.firstOrNull { !it.status.terminal && it.dependsOnKeys.all { d -> d in done } }?.stepId
    }

    private suspend fun endOpenCycle(outcome: String) {
        val open = cycleId ?: return
        store.endCycle(open, outcome, cycleNotes.toString().trim().ifBlank { "model turn without tool calls" }, "[]", clock())
        cycleId = null
    }

    // ---- suspending entry points ----

    suspend fun requested(calls: List<ToolCall>) = lock.withLock {
        flush()
        val cycle = cycleId ?: return@withLock
        val stepId = nextStepId()
        calls.forEach { call ->
            val effect = effectOf(call)
            val args = canonical(call.arguments)
            val invocationId = "$taskId/$cycle/${call.id}"
            invocationByCall[call.id] = invocationId
            safely {
                store.recordInvocationIntent(NewInvocation(invocationId, call.id, taskId, cycle, stepId, call.name, schemaIdOf(call.name),
                    redact(args).take(MAX_INPUT_CHARS), sha(args), effect, sha("$taskId|${call.name}|$args"), requestedAt = clock()))
            }
        }
        cycleNotes.append("requested: ").append(calls.joinToString(", ") { it.name }).append(". ")
        safely { store.heartbeat(taskId, clock()) }
    }

    /**
     * Write-ahead: the call is marked RUNNING in storage before it executes. A side-effecting call whose mark
     * cannot be stored is refused, and so is a repeat of a side effect whose earlier outcome is still unknown.
     */
    suspend fun gateRunning(call: ToolCall): RunGate = lock.withLock {
        val invocationId = invocationByCall[call.id] ?: return@withLock RunGate.Proceed
        val effect = effectOf(call)
        if (effect == Effect.SIDE_EFFECT) {
            val key = sha("$taskId|${call.name}|${canonical(call.arguments)}")
            val blockers = safely { store.unresolvedSideEffects(taskId) }.orEmpty()
                .filter { it.invocationId != invocationId && it.idempotencyKey == key && it.status in UNRESOLVED }
            if (blockers.isNotEmpty() && !verifiedByLaterRead(blockers.first())) return@withLock RunGate.Blocked(
                "An earlier identical ${call.name} call was interrupted and its outcome is unknown. Do not repeat it blindly: " +
                    "check the real state with a read-only tool first, then decide. Nothing was executed.",
                "PRIOR_MUTATION_OUTCOME_UNKNOWN")
        }
        val marked = safely {
            store.transitionInvocation(invocationId, InvocationStatus.RUNNING, InvocationPatch(startedAt = clock()), clock(), "run:$invocationId", call.name)
        }
        if (effect == Effect.SIDE_EFFECT && (marked == null || marked is TransitionResult.Rejected)) return@withLock RunGate.Blocked(
            "The action was not executed because its start could not be recorded durably. No side effect happened; try again.",
            "PERSISTENCE_UNAVAILABLE")
        running += call.id
        nextStepId()?.let { sid ->
            if (stepStarted.add(sid)) safely { store.transitionStep(sid, StepStatus.RUNNING, "first action started", clock(), "stepstart:$runToken:$sid") }
        }
        RunGate.Proceed
    }

    private suspend fun verifiedByLaterRead(blocker: InvocationRecord): Boolean {
        val all = safely { store.snapshot(taskId) }?.invocations.orEmpty()
        val path = pathOf(blocker.inputSanitized)
        return all.any { it.effect == Effect.READ_ONLY && it.status == InvocationStatus.SUCCEEDED && it.seq > blocker.seq &&
            (path == null || pathOf(it.inputSanitized).let { p -> p == null || p == path }) }
    }

    suspend fun completed(call: ToolCall, output: String, isError: Boolean, classification: String?) = lock.withLock {
        val invocationId = invocationByCall[call.id] ?: return@withLock
        if (!done.add(call.id)) return@withLock
        val safe = redact(output)
        val head = if (safe.length <= MAX_RESULT_CHARS) safe else safe.take(MAX_RESULT_CHARS * 3 / 4) + "\n…[truncated]…\n" + safe.takeLast(MAX_RESULT_CHARS / 4)
        val status = when {
            classification == "MUTATION_OUTCOME_UNKNOWN" -> InvocationStatus.UNKNOWN
            classification in NEVER_RAN || call.id !in running -> InvocationStatus.CANCELLED
            isError -> InvocationStatus.FAILED
            else -> InvocationStatus.SUCCEEDED
        }
        safely {
            store.transitionInvocation(invocationId, status, InvocationPatch(completedAt = clock(), resultSummary = head, resultHash = sha(safe),
                resultTruncated = safe.length > MAX_RESULT_CHARS, resultChars = safe.length, isError = isError || status != InvocationStatus.SUCCEEDED,
                errorDetail = if (isError) safe.take(500) else "", classification = classification.orEmpty()), clock(), "done:$invocationId", status.name)
        }
        cycleNotes.append(call.name).append(if (status == InvocationStatus.SUCCEEDED) " ok. " else " ${status.name.lowercase()}. ")
        safely { store.heartbeat(taskId, clock()) }
    }

    /** Persists the gate's decision right away without suspending the run. */
    fun finalizeAsync(publish: GateResult.Publish) {
        val job = scope.launch { finalize(publish) }
        synchronized(inFlight) { inFlight += job }
    }

    /** Ends persistence for this run once the flow completed or was cancelled: finish writes, then record an unfinished run as interrupted. */
    fun closeAsync() {
        scope.launch {
            val jobs = synchronized(inFlight) { inFlight.toList() }
            jobs.forEach { it.join() }
            onFlowEnd()
        }
    }

    /** Stores the P0 finish gate's decision. Completed steps are proven by SUCCEEDED invocations or an explicit non-tool justification. */
    suspend fun finalize(publish: GateResult.Publish) = lock.withLock {
        if (finalized) return@withLock
        flush()
        val report = publish.report
        val status = when (report.status) {
            FinishStatus.COMPLETED -> if (report.verified) TaskStatus.COMPLETED else TaskStatus.INCOMPLETE
            FinishStatus.BLOCKED -> TaskStatus.BLOCKED
            FinishStatus.FAILED -> TaskStatus.FAILED
            FinishStatus.AWAITING_APPROVAL -> TaskStatus.AWAITING_APPROVAL
            FinishStatus.INCOMPLETE -> TaskStatus.INCOMPLETE
        }
        safely { endOpenCycle(report.status.wire) }
        val current = safely { store.snapshot(taskId) }
        val outcomes = if (current == null) emptyList() else stepOutcomes(current, report, status)
        val result = safely {
            store.finalizeTask(taskId, status, FinishRecord(report.status.wire, report.summary, report.ledgerDigest), outcomes, clock(), "finish:$taskId:${report.ledgerDigest}")
        }
        if (result != null && result !is TransitionResult.Rejected) finalized = true
        else if (result is TransitionResult.Rejected) { degraded = true; lastError = result.reason.take(300) }
    }

    private fun stepOutcomes(snapshot: TaskSnapshot, report: com.omnidev.workspace.domain.engine.completion.CompletionReport, status: TaskStatus): List<StepOutcome> {
        val open = snapshot.currentSteps.filter { !it.status.terminal }
        if (status == TaskStatus.COMPLETED) {
            val byCall = snapshot.invocations.associateBy { it.callId }
            val runtimeIds = report.criteria.filter { it.criterion.source == AcceptanceCriterion.Source.RUNTIME && it.met }.flatMap { it.evidenceIds }
            return open.map { step ->
                val ids = if (step.key.startsWith("A")) report.criteria.firstOrNull { it.criterion.id == step.key && it.met }?.evidenceIds.orEmpty() else runtimeIds
                val proof = ids.mapNotNull { byCall[it]?.takeIf { inv -> inv.status == InvocationStatus.SUCCEEDED }?.invocationId }
                StepOutcome(step.stepId, StepStatus.COMPLETED, "accepted by the finish gate",
                    if (proof.isNotEmpty()) StepCompletion(proof) else StepCompletion(nonToolJustification = "finish gate accepted without tool evidence: the task did not require execution"))
            }
        }
        val target = when (status) { TaskStatus.FAILED -> StepStatus.FAILED; TaskStatus.BLOCKED, TaskStatus.AWAITING_APPROVAL -> StepStatus.BLOCKED; else -> StepStatus.INTERRUPTED }
        return open.filter { StepStatus.allowed(it.status, target) }.map { StepOutcome(it.stepId, target, "run ended ${report.status.wire}") }
    }

    /** Called when the run's flow ends for any reason. If no verdict was stored, the run did not finish: record it as interrupted. */
    suspend fun onFlowEnd() = lock.withLock {
        if (finalized) return@withLock
        flush()
        safely { endOpenCycle("interrupted") }
        safely { TaskRecovery(store, clock).recover(taskId) }
    }

    companion object {
        const val MAX_INPUT_CHARS = 4_000
        const val MAX_RESULT_CHARS = 8_000
        private val UNRESOLVED = setOf(InvocationStatus.INTERRUPTED, InvocationStatus.UNKNOWN)
        /** Classifications of results that were produced without the tool ever executing. */
        val NEVER_RAN = setOf("TOOL_NOT_AVAILABLE", "TOOL_POLICY_DENIED", "TIER_DENIED", "RUN_REDIRECTED", "SKILL_NOT_SELECTED",
            "LAUNCHER_TYPED_ACTION_REQUIRED", "TOOL_NOT_EXPOSED", "INVALID_TOOL_ARGUMENTS", "BATCH_PREFLIGHT_BLOCKED", "INVALID_TOOL_BATCH",
            "PRIOR_MUTATION_OUTCOME_UNKNOWN", "PERSISTENCE_UNAVAILABLE")

        private val SENSITIVE_KEY = Regex("(?i)(pass(word|wd)?|secret|token|api[_-]?key|authorization|credential|otp|\\bpin\\b|private[_-]?key)")
        /** Stable, key-sorted JSON of the arguments with credential-like values masked; used for storage and hashing. */
        fun canonical(args: Map<String, String>): String = args.toSortedMap().entries.joinToString(",", "{", "}") { (k, v) ->
            "\"${esc(k)}\":\"${if (SENSITIVE_KEY.containsMatchIn(k)) "[REDACTED]" else esc(v)}\""
        }
        private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        fun sha(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
        fun pathOf(inputJson: String): String? = Regex("\"(?:path|filePath|directory)\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(inputJson)?.groupValues?.get(1)

        /** Evidence entries that a resumed run restores into the P0 ledger. Only SUCCEEDED counts as success. */
        fun seedFrom(snapshot: TaskSnapshot): List<ResumeEntry> = snapshot.invocations.map { inv ->
            val outcome = when {
                inv.status == InvocationStatus.SUCCEEDED && !inv.isError -> RunEvidenceLedger.Outcome.OK
                inv.status == InvocationStatus.FAILED || inv.status == InvocationStatus.UNKNOWN ||
                    (inv.status == InvocationStatus.INTERRUPTED && inv.effect == Effect.SIDE_EFFECT) -> RunEvidenceLedger.Outcome.ERROR
                else -> RunEvidenceLedger.Outcome.SKIPPED
            }
            ResumeEntry(inv.callId, inv.toolName, pathOf(inv.inputSanitized)?.let { "path=$it" }.orEmpty(), outcome,
                inv.resultSummary.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(140).ifBlank { "restored: ${inv.status}" })
        }
    }
}

/** One restored ledger entry. UNKNOWN and interrupted side effects return as ERROR so they block completion, as they do in-process. */
data class ResumeEntry(val callId: String, val tool: String, val target: String, val outcome: RunEvidenceLedger.Outcome, val evidence: String)
