package com.omnidev.workspace.domain.engine.completion

import com.omnidev.workspace.data.brain.EpisodeOutcome
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.domain.engine.IntentClassifier
import com.omnidev.workspace.domain.engine.RunEvidenceLedger
import com.omnidev.workspace.domain.engine.SensitiveObservationRedactor
import com.omnidev.workspace.domain.engine.ToolBatchPolicy
import com.omnidev.workspace.domain.planning.*
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.core.policy.ConfirmationGate
import com.omnidev.workspace.domain.taskstate.Effect
import com.omnidev.workspace.domain.taskstate.TaskRunRecorder
import com.omnidev.workspace.domain.taskstate.TaskResumeMetadata
import com.omnidev.workspace.domain.taskstate.TaskStateStore

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
    private val ids: ToolCallIdentity,
    /** Persistent task state for this run; null for plain chat or when no store is wired. */
    val recorder: TaskRunRecorder? = null
) {
    val enforce: Boolean get() = policy.enforce
    /** Verdict of the current turn; cleared at the start of every turn. */
    private var verdict: GateResult.Publish? = null

    /** Result of a protocol step: the (possibly rewritten) response, or messages to append before another turn. */
    class Step(val response: CompletionResponse, val append: List<ChatMessage> = emptyList(), val rejected: Boolean = false)

    private var planner: TaskPlanningCoordinator? = null
    val sequentialPlanning: Boolean get() = planner?.active == true

    suspend fun configurePlanning(provider: suspend (CompletionRequest) -> CompletionResponse, prototype: CompletionRequest,
        contextWindow: Int, definitions: List<ToolDefinition>, history: List<ChatMessage>, approval: ConfirmationGate?) {
        val r = recorder ?: return
        r.synchronize()
        val capabilities = definitions.groupBy { IntentClassifier.getToolDomain(it.name) }.entries.sortedBy { it.key.name }.map {
            "${it.key.name}: ${it.value.take(4).joinToString { tool -> tool.name }} (${it.value.size} capabilities)"
        }
        planner = TaskPlanningCoordinator(r.store, r.taskId, ModelStructuredPlanGenerator(prototype, provider, contextWindow, definitions.mapTo(hashSetOf()) { it.name }),
            capabilities, history.filter { it.role == MessageRole.USER }.takeLast(2).map { PlanningPrivacy.safe(it.content).take(400) },
            identity.acceptance, approval)
        planner?.refresh()
        gate.planProblems = { planner?.pending().orEmpty() }
    }

    suspend fun beginTurn(budget: Int? = null): Int {
        verdict = null
        val used = planner?.prepare(budget) ?: 0
        recorder?.planningActive = planner?.active == true
        recorder?.beginTurn(planner?.nextStep(), explicit = planner != null)
        recorder?.synchronize()
        planner?.refresh()
        return used
    }
    fun setGoal(goal: String) {
        if (state.goal != goal) {
            if (planner != null) planner?.redirect(goal) else recorder?.replan("steering: the user changed the objective", goal)
        }
        state.goal = goal
    }

    /** Persists the run's end (verdict or interruption) when its job completes, even on cancellation. */
    fun attach(job: kotlinx.coroutines.Job?) {
        val r = recorder ?: return
        job?.invokeOnCompletion { r.closeAsync() }
    }

    private fun decided(p: GateResult.Publish) { verdict = p; recorder?.finalizeAsync(p) }
    fun report(): CompletionReport? = verdict?.report
    fun reflectionEnabled(configured: Boolean): Boolean = profile.selfReflection ?: configured
    /** Honest non-success terminal texts must not be rewritten by the critic. */
    fun keepsText(): Boolean = verdict?.let { it.status != FinishStatus.COMPLETED } == true
    /** True when a verdict exists, so the stall detector must not re-judge the accepted text. */
    fun hasVerdict(): Boolean = verdict != null
    fun inputReserveTokens(): Int = if (policy.enforce) RunState.RESERVE_TOKENS + (if (planner?.active == true) 1_900 else 0) else 0

    /** finish_task is a control signal: exposed to the model, intercepted before execution. */
    fun exposed(defs: List<ToolDefinition>, reportOnly: Boolean): List<ToolDefinition> =
        if (policy.enforce && !reportOnly && defs.isNotEmpty()) defs + FinishTool.DEFINITION +
            (if (planner?.active == true) TaskPlanningCoordinator.TOOLS else emptyList()) else defs

    /** Tool names the model may call this turn, including the control tool. */
    fun callableNames(defs: List<ToolDefinition>, reportOnly: Boolean): Set<String> =
        exposed(defs, reportOnly).mapTo(mutableSetOf()) { it.name }

    /** Runtime-owned Goal/Plan/Ledger/Next Action, appended fresh to every request and never stored in history. */
    fun stateMessages(iteration: Int): List<ChatMessage> =
        if (policy.enforce && (iteration > 1 || ledger.size() > 0 || planner?.active == true))
            listOf(ChatMessage(MessageRole.USER, state.render() + "\n" + planner?.render().orEmpty())) else emptyList()

    /** Stabilizes tool-call ids and decides an explicit finish_task call. */
    suspend fun afterModel(response: CompletionResponse): Step {
        planner?.refresh()
        if (response.toolCalls.isEmpty()) return Step(response)
        val stable = response.copy(toolCalls = ids.stabilize(response.toolCalls))
        val control = stable.toolCalls.firstOrNull { it.name == TaskPlanningCoordinator.COMPLETE || it.name == TaskPlanningCoordinator.REPLAN }
        if (control != null) {
            val feedback = if (stable.toolCalls.size != 1) "Planning control must be called alone. Nothing executed."
                else planner?.takeIf { it.active }?.control(control) ?: "No structured plan is active."
            return Step(stable, listOf(ChatMessage(MessageRole.ASSISTANT, stable.content, toolCalls = stable.toolCalls),
                ChatMessage(MessageRole.TOOL, feedback, toolResults = stable.toolCalls.map {
                    com.omnidev.workspace.data.model.ToolCallResult(it.id, it.name, feedback)
                })), rejected = true)
        }
        val finishCall = (if (policy.enforce) stable.toolCalls.firstOrNull { it.name == FinishTool.NAME } else null)
            ?: return Step(stable)
        val step = FinishDispatch.explicit(gate, finishCall, stable)
        val published = step.publish ?: return Step(stable, step.append, rejected = true)
        decided(published)
        return Step(stable.copy(toolCalls = emptyList(), content = published.text))
    }

    /** Plain-text final in a bound run = implicit `completed` claim. */
    suspend fun beforePublish(response: CompletionResponse, blocked: Boolean): Step {
        planner?.refresh()
        if (verdict != null || !policy.enforce || blocked) return Step(response)
        val step = FinishDispatch.implicit(gate, response.content)
        val published = step.publish ?: return Step(response, step.append, rejected = true)
        decided(published)
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
            toolsAvailable: Boolean, modelId: String, enableModelProfiles: Boolean,
            store: TaskStateStore? = null, originalRequest: String = goal
        ): FinishRuntime {
            val requestedIdentity = runIdentity ?: RunIdentity()
            val identity = requestedIdentity.copy(
                taskKind = FinalizationPolicy.resolveTaskKind(signals, requestedIdentity.taskKind)
            )
            val profile = ModelExecutionProfile.resolve(modelId, enableModelProfiles)
            val policy = FinalizationPolicy.decide(signals, isWorker, toolsAvailable, identity)
            val ledger = RunEvidenceLedger(identity.runId, identity.workerId)
            val state = RunState(goal, ledger, identity.acceptance, policy.requireExecution)
            // Only runs bound by the finish protocol become persistent tasks; plain chat never touches the task store.
            val recorder = if (store != null && policy.enforce) {
                identity.resume?.let { ledger.restore(it) }
                TaskRunRecorder(store, identity.runId, identity.sessionId, originalRequest, identity.parentTaskId, identity.workerId, identity.acceptance,
                    resumed = identity.resume != null,
                    effectOf = { if (ToolBatchPolicy.isReadOnly(it)) Effect.READ_ONLY else Effect.SIDE_EFFECT },
                    redact = SensitiveObservationRedactor::redact,
                    constraintsJson = TaskResumeMetadata.encode(identity, policy),
                    resumeClaimed = identity.resumeClaimed,
                    deferInitialPlan = identity.resume == null && PlanningTrigger.complex(goal, identity.acceptance)).also { it.start(goal) }
            } else null
            val runtime = FinishRuntime(identity, profile, policy, ledger, state,
                FinishGate(ledger, state, policy, identity, profile.maxFinishRejections), ToolCallIdentity(identity.runId, identity.resume.orEmpty().map { it.callId }), recorder)
            ledger.fin = runtime
            return runtime
        }
    }
}
