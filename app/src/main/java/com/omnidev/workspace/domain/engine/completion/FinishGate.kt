package com.omnidev.workspace.domain.engine.completion

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.domain.engine.RunEvidenceLedger

sealed class GateResult {
    /** Publish [text] with [report]. Also used for honest non-success terminals and for exhausted retries. */
    data class Publish(val status: FinishStatus, val text: String, val report: CompletionReport) : GateResult()
    /** Do not publish; give the model this feedback and run another turn. */
    data class Reject(val feedback: String) : GateResult()
}

/**
 * Runtime-controlled finalization. The model proposes (finish_task or plain text); this class decides.
 * 'completed' is accepted only after [FinishVerifier] confirms it against the ledger.
 */
class FinishGate(
    private val ledger: RunEvidenceLedger,
    private val state: RunState,
    private val policy: FinalizationPolicy,
    private val identity: RunIdentity,
    private val maxRejections: Int = policy.maxRejections
) {
    var rejections = 0
        private set

    private fun context() = VerificationContext(
        runId = identity.runId, workerId = identity.workerId, evidence = ledger.snapshot(),
        requireExecution = policy.requireExecution, taskCriteria = state.taskCriteria
    )

    fun explicit(call: ToolCall, streamedText: String): GateResult {
        if (call.argumentError != null) return reject("finish_task arguments were malformed (${call.argumentError.take(120)}). Send status and summary.", emptyList())
        val parsed = FinishTool.parse(call.arguments)
            ?: return reject("finish_task needs status = completed | blocked | failed | awaiting_approval | incomplete.", emptyList())
        val request = if (parsed.summary.isBlank() && streamedText.isNotBlank()) parsed.copy(summary = streamedText.trim()) else parsed
        return decide(request)
    }

    /** A plain-text final in an enforced run is treated as an implicit `completed` claim. */
    fun implicit(text: String): GateResult =
        decide(FinishRequest(FinishStatus.COMPLETED, text.trim()))

    /** Honest terminal used when another runtime mechanism already gave up (e.g. stalled promises). */
    fun incomplete(text: String): GateResult.Publish = publish(FinishStatus.INCOMPLETE, text, evaluate(FinishRequest(FinishStatus.INCOMPLETE, text)), verified = false)

    private fun evaluate(request: FinishRequest) = FinishVerifier.evaluate(context(), request)

    private fun decide(request: FinishRequest): GateResult {
        val eval = evaluate(request)
        if (eval.accepted) {
            state.noteFinishAttempt(request.claims, null)
            return publish(request.status, request.summary, eval, verified = request.status == FinishStatus.COMPLETED)
        }
        return reject(eval.problems.joinToString("; "), request.claims, request, eval)
    }

    private fun reject(reason: String, claims: List<CriterionClaim>, request: FinishRequest? = null, eval: Evaluation? = null): GateResult {
        rejections += 1
        state.noteFinishAttempt(claims, reason)
        if (rejections > maxRejections) {
            // Never silent success: downgrade and say why, using the model's own text as the body.
            val body = request?.summary.orEmpty().ifBlank { "The run stopped without a verifiable result." }
            val text = "$body\n\nRuntime: completion could not be verified (${reason.take(300)})."
            val e = eval ?: Evaluation(false, emptyList(), listOf(reason))
            return publish(FinishStatus.INCOMPLETE, text, e, verified = false)
        }
        return GateResult.Reject(
            "FINISH_REJECTED ($rejections/$maxRejections): $reason. " +
                "Do the missing work with tools, then call finish_task again" +
                (if (state.taskCriteria.isNotEmpty()) " with 'A1 => id' lines" else "") +
                ", or finish with status=blocked/failed/incomplete and the exact reason. Do not claim anything the RUN STATE ledger does not show."
        )
    }

    private fun publish(status: FinishStatus, summary: String, eval: Evaluation, verified: Boolean): GateResult.Publish {
        val evidence = ledger.snapshot().takeLast(200)
        val text = if (status == FinishStatus.COMPLETED || summary.startsWith(status.label)) summary
        else "${status.label} $summary"
        return GateResult.Publish(
            status, text,
            CompletionReport(identity.runId, identity.workerId, status, summary, verified, eval.results, evidence,
                eval.problems, CompletionReport.digest(evidence))
        )
    }
}

/** Coordinator-side re-check of a worker's report. Never trusts the worker's prose. */
object WorkerEvidenceReview {
    data class Review(val accepted: Boolean, val reason: String?)

    fun review(report: CompletionReport?, expectedWorkerId: String, taskCriteria: List<String>, requireExecution: Boolean, legacyToolEvidencePresent: Boolean): Review {
        if (report == null) {
            // Old/unprotected path: accept only when the worker really ran tools, as before it was checked by prose alone.
            return if (!requireExecution || legacyToolEvidencePresent) Review(true, null)
            else Review(false, "worker returned no completion report and no tool evidence")
        }
        if (report.status != FinishStatus.COMPLETED) return Review(false, "worker ended ${report.status.wire}: ${report.summary.take(200)}")
        if (!report.verified) return Review(false, "worker's completion was not verified by its runtime")
        if (report.workerId != expectedWorkerId) return Review(false, "report belongs to another worker (${report.workerId})")
        if (report.ledgerDigest != CompletionReport.digest(report.evidence)) return Review(false, "evidence digest mismatch")
        if (report.evidence.any { it.runId != report.runId || it.workerId != report.workerId }) return Review(false, "evidence from another run")
        val claims = report.criteria.filter { it.criterion.source != AcceptanceCriterion.Source.RUNTIME }
            .map { CriterionClaim(if (it.criterion.source == AcceptanceCriterion.Source.TASK) it.criterion.id else "", it.criterion.text, it.evidenceIds) }
        val eval = FinishVerifier.evaluate(
            VerificationContext(report.runId, report.workerId, report.evidence, requireExecution, taskCriteria),
            FinishRequest(FinishStatus.COMPLETED, report.summary, claims = claims)
        )
        return if (eval.accepted) Review(true, null) else Review(false, eval.problems.joinToString("; ").take(300))
    }
}

/**
 * Keeps the finish-protocol branches out of AgentPipeline's giant flow lambda (it is already close
 * to the JVM method-size limit; see PipelineRecovery). Returns either a result to publish or the
 * messages to append before the next turn.
 */
object FinishDispatch {
    class Step(val publish: GateResult.Publish?, val append: List<com.omnidev.workspace.data.model.ChatMessage>)

    private const val MIXED = "FINISH_REJECTED: finish_task must be called alone. No tool in this batch ran; run the other tools first, then call finish_task by itself."

    fun explicit(gate: FinishGate, call: ToolCall, response: com.omnidev.workspace.data.model.CompletionResponse): Step {
        val mixed = response.toolCalls.size > 1
        val verdict = if (mixed) null else gate.explicit(call, response.content)
        if (verdict is GateResult.Publish) return Step(verdict, emptyList())
        val feedback = (verdict as? GateResult.Reject)?.feedback ?: MIXED
        val results = response.toolCalls.map { c ->
            com.omnidev.workspace.data.model.ToolCallResult(
                c.id, c.name, if (c === call) feedback else "Skipped: batch rejected with finish_task; nothing ran.", true
            )
        }
        return Step(null, listOf(
            com.omnidev.workspace.data.model.ChatMessage(
                role = com.omnidev.workspace.data.model.MessageRole.ASSISTANT,
                content = response.content, toolCalls = response.toolCalls
            ),
            com.omnidev.workspace.data.model.ChatMessage(
                com.omnidev.workspace.data.model.MessageRole.TOOL, feedback, toolResults = results
            )
        ))
    }

    fun implicit(gate: FinishGate, text: String): Step {
        if (text.startsWith(INCOMPLETE_PREFIX)) return Step(gate.incomplete(text), emptyList())
        return when (val verdict = gate.implicit(text)) {
            is GateResult.Publish -> Step(verdict, emptyList())
            is GateResult.Reject -> Step(null, listOf(
                com.omnidev.workspace.data.model.ChatMessage(com.omnidev.workspace.data.model.MessageRole.ASSISTANT, text),
                com.omnidev.workspace.data.model.ChatMessage(com.omnidev.workspace.data.model.MessageRole.USER, verdict.feedback)
            ))
        }
    }

    // Same literal as PipelineRecovery.INCOMPLETE_PREFIX (pinned by a unit test).
    const val INCOMPLETE_PREFIX = "[Incomplete]"
}
