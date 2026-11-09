package com.omnidev.workspace.domain.planning

import com.omnidev.workspace.domain.engine.RunEvidenceLedger
import com.omnidev.workspace.domain.engine.completion.*
import com.omnidev.workspace.domain.taskstate.*
import kotlinx.serialization.json.Json

/** Uses P0's verifier with exact, persisted invocation identities scoped to one step. */
object StepEvidenceEvaluator {
    fun evaluate(snapshot: TaskSnapshot, step: StepRecord, completion: StepCompletion?): String? {
        val ids = completion?.evidenceInvocationIds.orEmpty()
        if (ids.isEmpty() || ids.size > 24 || ids.distinct().size != ids.size) return "STEP_EVIDENCE_REQUIRED"
        if (snapshot.invocations.any { it.effect == Effect.SIDE_EFFECT && !it.status.final }) return "UNRESOLVED_SIDE_EFFECT"
        val owned = snapshot.invocations.filter { it.stepId == step.stepId }
        val cited = ids.map { id -> owned.firstOrNull { it.invocationId == id } ?: return "UNATTRIBUTED_STEP_EVIDENCE" }
        if (cited.any { it.status != InvocationStatus.SUCCEEDED || it.isError || com.omnidev.workspace.data.tools.ToolExecutionSemantics.isEmptyObservation(it.resultSummary) }) return "UNSUCCESSFUL_STEP_EVIDENCE"
        val policy = PlanCodec.metadata(snapshot.latestPlan?.metadataJson.orEmpty())?.policies?.get(step.key) ?: StepPolicy()
        val refs = owned.map { inv ->
            val args = arguments(inv)
            val target = args["path"] ?: args["filePath"] ?: EvidenceKinds.explicitMutationTarget(inv.toolName, args)
                ?: EvidenceKinds.commandTarget(inv.toolName, args)
            EvidenceRef(inv.invocationId, inv.seq.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), inv.toolName,
                target?.let { "path=$it" }.orEmpty(),
                when {
                    inv.status == InvocationStatus.SUCCEEDED && !inv.isError &&
                        com.omnidev.workspace.data.tools.ToolExecutionSemantics.isEmptyObservation(inv.resultSummary) -> RunEvidenceLedger.Outcome.EMPTY
                    inv.status == InvocationStatus.SUCCEEDED && !inv.isError && inv.resultSummary.isNotBlank() -> RunEvidenceLedger.Outcome.OK
                    inv.status == InvocationStatus.FAILED || inv.status == InvocationStatus.UNKNOWN -> RunEvidenceLedger.Outcome.ERROR
                    else -> RunEvidenceLedger.Outcome.SKIPPED
                }, EvidenceKinds.classify(inv.toolName, args), snapshot.task.taskId, snapshot.task.workerId)
        }
        val checked = FinishVerifier.evaluate(VerificationContext(snapshot.task.taskId, snapshot.task.workerId, refs,
            requireExecution = true, taskCriteria = listOf(step.completionCondition)),
            FinishRequest(FinishStatus.COMPLETED, "Step verification proposal", evidenceIds = ids,
                claims = listOf(CriterionClaim("A1", step.completionCondition, ids))))
        if (!checked.accepted) return "P0_STEP_PROOF_REJECTED"
        val effects = owned.filter { it.effect == Effect.SIDE_EFFECT && it.status == InvocationStatus.SUCCEEDED }
        if (effects.isNotEmpty() && policy.proof == ProofKind.READ_RESULT) return "INDEPENDENT_CHECK_REQUIRED"
        val checks = cited.filter { it.effect == Effect.READ_ONLY && it.resultSummary.isNotBlank() &&
            (policy.checkTool.isBlank() || it.toolName == policy.checkTool) &&
            (policy.contains.isBlank() || it.resultSummary.contains(policy.contains)) }
        if (checks.isEmpty()) return "OUTPUT_CHECK_NOT_MET"
        if (policy.proof == ProofKind.READ_BACK) {
            val mutations = refs.filter { it.kind == EvidenceKinds.Kind.MUTATION && it.outcome == RunEvidenceLedger.Outcome.OK }
            if (mutations.isEmpty() || effects.any { effect -> mutations.none { it.callId == effect.invocationId } }) return "FILE_READ_BACK_REQUIRED"
            if (mutations.any { mutation ->
                    val path = EvidenceKinds.pathOf(mutation.target)
                    path == null || checks.none { check -> refs.any { ref -> ref.callId == check.invocationId &&
                        ref.kind == EvidenceKinds.Kind.READ && ref.seq > mutation.seq && EvidenceKinds.pathOf(ref.target) == path } }
                }) return "CONTENT_READ_BACK_REQUIRED"
        }
        if (policy.proof != ProofKind.READ_RESULT) {
            if (policy.contains.isBlank()) return "CONTENT_PREDICATE_REQUIRED"
            val lastEffect = effects.maxOfOrNull { it.seq } ?: return "ACTION_EVIDENCE_REQUIRED"
            if (checks.none { it.seq > lastEffect }) return "INDEPENDENT_CHECK_REQUIRED"
        }
        return null
    }

    private fun arguments(inv: InvocationRecord): Map<String, String> = runCatching {
        Json.decodeFromString<Map<String, String>>(inv.inputSanitized)
    }.getOrDefault(emptyMap())
}
