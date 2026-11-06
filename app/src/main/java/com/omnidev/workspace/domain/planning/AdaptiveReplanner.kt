package com.omnidev.workspace.domain.planning

import com.omnidev.workspace.domain.taskstate.*

data class ReplanResult(val tokens: Int, val persisted: Boolean, val diagnostic: String = "")

/** No lock or Room transaction spans model generation. The store commits with a revision compare-and-set. */
class AdaptiveReplanner(private val store: TaskStateStore, private val generator: StructuredPlanGenerator,
    private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun revise(snapshot: TaskSnapshot, input: PlanningInput, budget: Int?): ReplanResult {
        if (snapshot.invocations.any { it.effect == Effect.SIDE_EFFECT && !it.status.final })
            return ReplanResult(0, false, "UNRESOLVED_SIDE_EFFECT")
        val generated = generator.generate(input, budget)
        val proposed = generated.plan ?: return ReplanResult(generated.tokens, false, generated.diagnostic)
        val validation = PlanValidator.validate(proposed, input.mandatoryConstraints)
        val plan = validation.normalized ?: return ReplanResult(generated.tokens, false, validation.diagnostic())
        val metadata = PlanCodec.metadata(plan, input.objective)
        return try {
            val saved = store.appendPlanRevision(snapshot.task.taskId, plan.steps.map { it.newStep() }, input.reason.take(240), clock(),
                "p3-plan:${snapshot.task.planRevision}:${TaskRunRecorder.sha(metadata)}", metadata, snapshot.task.planRevision)
            ReplanResult(generated.tokens, saved != null)
        } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: TaskStateException) {
            // Error details may originate in providers/storage; do not publish their raw text.
            ReplanResult(generated.tokens, false, "PLAN_COMMIT_REJECTED")
        }
    }
}
