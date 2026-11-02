package com.omnidev.workspace.domain.engine.completion

import com.omnidev.workspace.data.brain.EpisodeOutcome
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.domain.engine.IntentClassifier
import com.omnidev.workspace.domain.engine.RunEvidenceLedger

/**
 * Everything the finish protocol needs for one run, behind a handful of calls. AgentPipeline's flow
 * lambda is at the JVM method-size limit ("Method too large" fails the build), so no protocol logic
 * may live inline there: the lambda only calls these methods.
 */
class FinishRuntime private constructor(
    val identity: RunIdentity,
    private val profile: ModelExecutionProfile,
    private val policy: FinalizationPolicy,
    val ledger: RunEvidenceLedger,
    private val state: RunState,
    private val gate: FinishGate,
    private val ids: ToolCallIdentity
) {
    val enforce: Boolean get() = policy.enforce
    /** Verdict of the current turn; cleared at the start of every turn. */
    private var verdict: GateResult.Publish? = null

    /** Result of a protocol step: the (possibly rewritten) response, or messages to append before another turn. */
    class Step(val response: CompletionResponse, val append: List<ChatMessage> = emptyList(), val rejected: Boolean = false)

    fun beginTurn() { verdict = null }
    fun setGoal(goal: String) { state.goal = goal }
    fun report(): CompletionReport? = verdict?.report
    fun reflectionEnabled(configured: Boolean): Boolean = profile.selfReflection ?: configured
    /** Honest non-success terminal texts must not be rewritten by the critic. */
    fun keepsText(): Boolean = verdict?.let { it.status != FinishStatus.COMPLETED } == true
    /** True when a verdict exists, so the stall detector must not re-judge the accepted text. */
    fun hasVerdict(): Boolean = verdict != null
    fun inputReserveTokens(): Int = if (policy.enforce) RunState.RESERVE_TOKENS else 0

    /** finish_task is a control signal: exposed to the model, intercepted before execution. */
    fun exposed(defs: List<ToolDefinition>, reportOnly: Boolean): List<ToolDefinition> =
        if (policy.enforce && !reportOnly && defs.isNotEmpty()) defs + FinishTool.DEFINITION else defs

    /** Runtime-owned Goal/Plan/Ledger/Next Action, appended fresh to every request and never stored in history. */
    fun stateMessages(iteration: Int): List<ChatMessage> =
        if (policy.enforce && (iteration > 1 || ledger.size() > 0)) listOf(ChatMessage(MessageRole.USER, state.render())) else emptyList()

    /** Stabilizes tool-call ids and decides an explicit finish_task call. */
    fun afterModel(response: CompletionResponse): Step {
        if (response.toolCalls.isEmpty()) return Step(response)
        val stable = response.copy(toolCalls = ids.stabilize(response.toolCalls))
        val finishCall = (if (policy.enforce) stable.toolCalls.firstOrNull { it.name == FinishTool.NAME } else null)
            ?: return Step(stable)
        val step = FinishDispatch.explicit(gate, finishCall, stable)
        val published = step.publish ?: return Step(stable, step.append, rejected = true)
        verdict = published
        return Step(stable.copy(toolCalls = emptyList(), content = published.text))
    }

    /** Plain-text final in a bound run = implicit `completed` claim. */
    fun beforePublish(response: CompletionResponse, blocked: Boolean): Step {
        if (verdict != null || !policy.enforce || blocked) return Step(response)
        val step = FinishDispatch.implicit(gate, response.content)
        val published = step.publish ?: return Step(response, step.append, rejected = true)
        verdict = published
        return Step(response.copy(content = published.text))
    }

    fun episodeOutcome(blocked: Boolean): EpisodeOutcome {
        val status = verdict?.status
        return when {
            blocked -> EpisodeOutcome.BLOCKED
            status == null || status == FinishStatus.COMPLETED -> EpisodeOutcome.SUCCESS
            status == FinishStatus.BLOCKED || status == FinishStatus.AWAITING_APPROVAL -> EpisodeOutcome.BLOCKED
            status == FinishStatus.FAILED -> EpisodeOutcome.FAILURE
            else -> EpisodeOutcome.ABANDONED
        }
    }

    companion object {
        fun create(
            runIdentity: RunIdentity?, goal: String, signals: IntentClassifier.TaskSignals, isWorker: Boolean,
            toolsAvailable: Boolean, modelId: String, enableModelProfiles: Boolean
        ): FinishRuntime {
            val identity = runIdentity ?: RunIdentity()
            val profile = ModelExecutionProfile.resolve(modelId, enableModelProfiles)
            val policy = FinalizationPolicy.decide(signals, isWorker, toolsAvailable, identity)
            val ledger = RunEvidenceLedger(identity.runId, identity.workerId)
            val state = RunState(goal, ledger, identity.acceptance, policy.requireExecution)
            return FinishRuntime(identity, profile, policy, ledger, state,
                FinishGate(ledger, state, policy, identity, profile.maxFinishRejections), ToolCallIdentity(identity.runId))
        }
    }
}
