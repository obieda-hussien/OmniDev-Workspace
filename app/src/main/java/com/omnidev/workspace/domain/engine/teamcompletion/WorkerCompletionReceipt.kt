package com.omnidev.workspace.domain.engine.teamcompletion

import com.omnidev.workspace.domain.engine.*
import com.omnidev.workspace.domain.engine.completion.*

/** One runtime-created attempt. A retry, redirect or different team cannot reuse its proof. */
data class WorkerAssignment(val teamRunId: String, val revision: Long, val task: SwarmTask, val attempt: Int,
    val kind: TaskKind = if (TeamCompletionProtocol.requiresAction(task.description) && !task.toolFree)
        TaskKind.IMPLEMENTATION else TaskKind.ANALYSIS) {
    val identity = RunIdentity(runId = "$teamRunId:r$revision:${task.id}:a$attempt", workerId = task.id,
        acceptance = task.acceptance, requireExecution = !task.toolFree,
        taskKind = kind,
        parentTaskId = teamRunId, forceFinishProtocol = true)
}

class WorkerReceipt internal constructor(val assignment: WorkerAssignment, val status: FinishStatus, val accepted: Boolean,
    val reason: String, val ledgerDigest: String, val outputDigest: String, val evidenceCount: Int)

/** Exact runtime observations, not per-tool counters or model-authored reports. Bounded and run-local. */
class WorkerCompletionObserver(private val assignment: WorkerAssignment) {
    private val started = linkedMapOf<String, String>()
    private val observed = linkedMapOf<String, EvidenceRef>()
    private val pendingEffects = mutableSetOf<String>()
    private var invalid = false
    private var uncertain = false
    private var effectObserved = false
    private var terminal: FinishStatus? = null
    val unsafeToRetry: Boolean get() = terminal != null || effectObserved || uncertain || pendingEffects.isNotEmpty()

    fun started(event: AgentEvent.ToolExecution) {
        if (event.callId.isBlank() || event.callId.length > 160 || started.size >= 200 ||
            started.putIfAbsent(event.callId, event.toolName) != null) { invalid = true; uncertain = true; return }
        if (!ToolBatchPolicy.isReadOnly(com.omnidev.workspace.data.model.ToolCall(event.callId, event.toolName, event.arguments))) {
            if (event.callId.isBlank() || event.callId.length > 160 || pendingEffects.size >= 200) uncertain = true
            else pendingEffects += event.callId
        }
    }

    fun observed(event: AgentEvent.ToolResult) {
        val e = event.evidence
        if (e == null || e.runId != assignment.identity.runId || e.workerId != assignment.task.id ||
            e.tool != event.toolName || started[e.callId] != e.tool || e.seq != observed.size + 1 || e.callId.isBlank() || e.callId.length > 160 || observed.size >= 200 ||
            observed.putIfAbsent(e.callId, e) != null) { invalid = true; uncertain = true; return }
        if (event.classification in setOf("USER_ACTION_REQUIRED", "CONFIRMATION_REQUIRED")) terminal = FinishStatus.AWAITING_APPROVAL
        if (event.classification in setOf("GITHUB_AUTH_REQUIRED", "GITHUB_PERMISSION_DENIED", "PERMISSION_DENIED", "TIER_DENIED", "USER_DENIED", "USER_ACTION_DENIED") ||
            event.classification == "TOOL_POLICY_DENIED" && !event.output.startsWith("READ_ONLY_WORKER:")) terminal = FinishStatus.BLOCKED
        val hadEffectIntent = pendingEffects.remove(e.callId)
        if (hadEffectIntent && e.outcome != RunEvidenceLedger.Outcome.SKIPPED && event.classification !in SAFE_DENIALS) effectObserved = true
        if (e.kind in setOf(EvidenceKinds.Kind.MUTATION, EvidenceKinds.Kind.ACTION) &&
            e.outcome != RunEvidenceLedger.Outcome.OK && e.outcome != RunEvidenceLedger.Outcome.SKIPPED &&
            event.classification !in SAFE_DENIALS) uncertain = true
        if (event.isError && e.outcome == RunEvidenceLedger.Outcome.OK) invalid = true
    }

    fun review(report: CompletionReport?, output: String): WorkerReceipt {
        fun rejected(code: String, status: FinishStatus = FinishStatus.INCOMPLETE) = WorkerReceipt(assignment,
            status.takeUnless { it == FinishStatus.COMPLETED } ?: FinishStatus.INCOMPLETE,
            false, SensitiveObservationRedactor.redact(code).take(300), report?.ledgerDigest.orEmpty(), TeamCompletionProtocol.digest(output), observed.size)
        terminal?.let { return rejected("RUNTIME_INTERVENTION_REQUIRED", it) }
        if (report == null) return rejected("MISSING_P0_REPORT")
        if (invalid || pendingEffects.isNotEmpty() || started.size != observed.size) return rejected("INVALID_OR_UNSETTLED_OBSERVATIONS")
        if (report.runId != assignment.identity.runId || report.workerId != assignment.task.id) return rejected("FOREIGN_ATTEMPT")
        if (report.evidence.size > 200 || report.evidence.map { it.callId }.distinct().size != report.evidence.size ||
            report.evidence != observed.values.toList()) return rejected("EVIDENCE_OBSERVATION_MISMATCH")
        if (output.isBlank()) return rejected("EMPTY_WORKER_OUTPUT")
        val reviewed = WorkerEvidenceReview.review(report, assignment.task.id, assignment.task.acceptance,
            !assignment.task.toolFree, false, assignment.identity.runId,
            assignment.kind == TaskKind.IMPLEMENTATION && !assignment.task.toolFree)
        if (!reviewed.accepted) return rejected("P0_REJECTED: " + reviewed.reason.orEmpty().take(240), report.status)
        if (uncertain) return rejected("UNRESOLVED_SIDE_EFFECT")
        return WorkerReceipt(assignment, FinishStatus.COMPLETED, true, "P0_VERIFIED", report.ledgerDigest,
            TeamCompletionProtocol.digest(output), observed.size)
    }

    companion object {
        private val SAFE_DENIALS = setOf("TOOL_POLICY_DENIED", "TIER_DENIED", "TOOL_NOT_AVAILABLE", "TOOL_NOT_EXPOSED",
            "INVALID_TOOL_ARGUMENTS", "INVALID_TOOL_BATCH", "BATCH_PREFLIGHT_BLOCKED", "RUN_REDIRECTED")
    }
}
