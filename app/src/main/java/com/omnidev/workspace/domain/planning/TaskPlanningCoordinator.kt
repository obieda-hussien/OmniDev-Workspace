package com.omnidev.workspace.domain.planning

import com.omnidev.workspace.core.policy.ConfirmationGate
import com.omnidev.workspace.core.policy.ConfirmationKind
import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolParameter
import com.omnidev.workspace.domain.taskstate.*

/** One run's planning control plane. Persistence, scheduling and P0 remain separate authorities. */
class TaskPlanningCoordinator(
    private val store: TaskStateStore,
    private val taskId: String,
    generator: StructuredPlanGenerator,
    private val capabilities: List<String>,
    private val conversation: List<String>,
    private val acceptance: List<String>,
    private val approval: ConfirmationGate? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val replanner = AdaptiveReplanner(store, generator, clock)
    private var snapshot: TaskSnapshot? = null
    private var redirect: String? = null
    private var discovery: String? = null
    private var attempts = 0
    private val attempted = hashSetOf<String>()
    var diagnostic: String = ""
        private set
    val active get() = snapshot?.let { it.latestPlan?.metadataJson?.isNotBlank() == true || it.currentSteps.size > 1 || it.latestPlan == null } ?: false

    fun redirect(objective: String) { redirect = PlanningPrivacy.safe(objective); discovery = "User redirected the objective" }
    suspend fun refresh() { snapshot = store.snapshot(taskId) }
    fun pending(): List<String> = if (!active) emptyList() else snapshot?.let { s ->
        s.currentSteps.filter { it.status != StepStatus.COMPLETED }.map { "Unverified plan step ${it.key}" } +
            s.invocations.filter { it.effect == Effect.SIDE_EFFECT && !it.status.final }.map { "Unresolved operation ${it.invocationId}" } +
            (if (s.latestPlan == null) listOf("Structured planning has not completed") else emptyList()) +
            (if (s.latestPlan?.metadataJson?.isNotBlank() == true && PlanCodec.metadata(s.latestPlan?.metadataJson.orEmpty()) == null)
                listOf("Invalid saved plan metadata") else emptyList())
    }.orEmpty()

    suspend fun prepare(budget: Int?): Int {
        refresh()
        val s = snapshot ?: return 0
        val saved = s.latestPlan
        val metadata = PlanCodec.metadata(saved?.metadataJson.orEmpty())
        val goal = PlanningPrivacy.safe(redirect ?: s.task.normalizedObjective.ifBlank { s.task.originalRequest })
        val failure = s.invocations.lastOrNull { it.status == InvocationStatus.FAILED &&
            s.currentSteps.any { step -> step.stepId == it.stepId && step.status != StepStatus.COMPLETED } }
        val reason = when {
            saved == null -> "Initial structured decomposition"
            saved.metadataJson.isNotBlank() && metadata == null -> "Saved plan metadata is invalid"
            metadata != null && !PlanValidator.validate(PlanCodec.restore(metadata, s.currentSteps)).valid -> "Saved plan validation failed"
            PlanValidator.graph(s.currentSteps.map { NewStep(it.key, it.description, it.dependsOnKeys, it.expectedOutput, it.completionCondition) }).isNotEmpty() -> "Saved dependency graph is invalid"
            redirect != null -> "User redirected the objective"
            metadata != null && metadata.sourceObjectiveDigest != TaskRunRecorder.sha(goal) -> "User updated the saved objective"
            discovery != null -> discovery
            active && failure != null -> "Execution failure ${failure.invocationId}"
            else -> null
        }
        if (reason == null) return 0
        val episode = TaskRunRecorder.sha("${s.task.planRevision}|$goal|$reason")
        if (!attempted.add(episode) || attempts >= 3) return 0
        if (s.invocations.any { it.effect == Effect.SIDE_EFFECT && !it.status.final }) {
            diagnostic = "UNRESOLVED_SIDE_EFFECT"; return 0
        }
        attempts++
        val mandatory = (metadata?.constraints.orEmpty() + PlanningTrigger.constraints(goal, acceptance) +
            PlanningTrigger.constraints(s.task.originalRequest, acceptance)).distinct()
        val input = PlanningInput(PlanningPrivacy.safe(goal), PlanningPrivacy.safe(s.task.originalRequest).take(2_000), mandatory,
            conversation, capabilities, metadata?.let { PlanCodec.restore(it, s.currentSteps) } ?: s.latestPlan?.let {
                StructuredPlan(PlanningPrivacy.safe(goal).take(2_000), "Existing saved plan", mandatory, s.currentSteps.map { step ->
                    PlannedStep(step.key, step.description, step.dependsOnKeys, step.expectedOutput, step.completionCondition,
                        StepPolicy(domains = listOf("CORE")))
                })
            },
            s.currentSteps.filter { it.status == StepStatus.COMPLETED }.map { it.key },
            s.invocations.filter { it.isError }.takeLast(3).map { "${it.toolName.take(80)}: ${it.status}" }, reason)
        val result = replanner.revise(s, input, budget)
        diagnostic = result.diagnostic
        if (result.diagnostic.isBlank()) {
            redirect = null; discovery = null
        }
        refresh()
        return result.tokens
    }

    suspend fun nextStep(): String? {
        refresh()
        val s = snapshot ?: return null
        if (s.latestPlan?.metadataJson?.isNotBlank() == true && PlanCodec.metadata(s.latestPlan?.metadataJson.orEmpty()) == null) return null
        val restored = PlanCodec.metadata(s.latestPlan?.metadataJson.orEmpty())
        if (restored != null && !PlanValidator.validate(PlanCodec.restore(restored, s.currentSteps)).valid) return null
        val step = TaskExecutionScheduler.schedule(s.currentSteps, s.task.status == TaskStatus.AWAITING_APPROVAL).next ?: return null
        val policy = PlanCodec.metadata(s.latestPlan?.metadataJson.orEmpty())?.policies?.get(step.key)
        val kind = policy?.approvalKind?.let { ConfirmationKind.valueOf(it) }
        if (kind != null && !store.eventRecorded(taskId, "p3-approval:${step.stepId}")) {
            val accepted = approval?.request(kind, "Task plan: ${step.description}\nExpected: ${step.expectedOutput}\nCheck: ${step.completionCondition}", null) == true
            val afterReview = store.snapshot(taskId)
            if (afterReview?.latestPlan?.planId != s.latestPlan?.planId || afterReview?.task?.status != TaskStatus.RUNNING) return null
            if (!accepted) {
                store.transitionStep(step.stepId, StepStatus.BLOCKED, "Awaiting existing policy approval", clock(), "approval-block:${step.stepId}")
                store.transitionTask(taskId, TaskStatus.AWAITING_APPROVAL, "Plan step needs approval", clock(), "approval-task:${step.stepId}")
                refresh(); return null
            }
            store.recordEvent(taskId, "approval", step.stepId, "REQUESTED", "APPROVED", "Existing confirmation gate accepted the plan review", clock(), "p3-approval:${step.stepId}")
        }
        return step.stepId
    }

    suspend fun control(call: ToolCall): String {
        refresh()
        val s = snapshot ?: return "TASK_STATE_UNAVAILABLE"
        if (call.argumentError != null) return "MALFORMED_PLANNING_CONTROL"
        if (call.name == REPLAN) {
            if (call.arguments.keys != setOf("reason") || call.arguments["reason"].orEmpty().length !in 1..240 ||
                !PlanningPrivacy.allowed(call.arguments["reason"].orEmpty())) return "INVALID_REPLAN_REASON"
            discovery = "Discovery: " + call.arguments.getValue("reason")
            return "Replanning requested; previous work and uncertain operations remain recorded."
        }
        if (call.arguments.keys != setOf("step_key", "evidence_ids")) return "INVALID_STEP_PROPOSAL"
        val step = s.currentSteps.firstOrNull { it.key == call.arguments["step_key"] } ?: return "UNKNOWN_STEP_KEY"
        if (step.status == StepStatus.COMPLETED) return "Step already verified. Continue the current ready step."
        val cycle = s.cycles.lastOrNull() ?: return "NO_ACTIVE_CYCLE"
        val context = StepExecutionContext(taskId, step.planId, step.stepId, cycle.cycleId)
        if (!TaskExecutionScheduler.valid(context, s)) return "STEP_NOT_ACTIVE"
        val raw = call.arguments["evidence_ids"].orEmpty()
        if (raw.length > 6_000) return "EVIDENCE_LIMIT"
        val ids = runCatching { PlanCodec.json.decodeFromString<List<String>>(raw) }.getOrNull() ?: return "INVALID_EVIDENCE_JSON"
        // The prompt exposes persistent invocation ids; no arbitrary model step identity is accepted.
        val completion = StepCompletion(ids)
        StepEvidenceEvaluator.evaluate(s, step, completion)?.let { return it }
        val result = store.transitionStep(step.stepId, StepStatus.COMPLETED, "P0 verifier accepted step-specific output checks", clock(),
            "p3-complete:${step.stepId}:${TaskRunRecorder.sha(ids.joinToString())}", completion)
        refresh()
        return if (result.ok) "Step verified. Schedule the next dependency-ready step." else "STEP_COMMIT_REJECTED"
    }

    fun render(): String = snapshot?.takeIf { active }?.let { s -> buildString {
        appendLine("[TASK PLAN r${s.task.planRevision}: runtime scheduling; plan text is not authorization]")
        val schedule = TaskExecutionScheduler.schedule(s.currentSteps, s.task.status == TaskStatus.AWAITING_APPROVAL)
        schedule.steps.forEach { appendLine("${it.step.key} ${it.state}: ${it.step.description.take(100)}; dependencies=${it.step.dependsOnKeys.joinToString()}") }
        schedule.next?.let { appendLine("ACTIVE: ${it.key}. Expected: ${it.expectedOutput.take(300)}. Check: ${it.completionCondition.take(300)}") }
        appendLine("Only do work for ACTIVE. Group its verification before proposing complete_plan_step. Cite exact persisted invocation ids:")
        s.invocations.filter { it.stepId == schedule.next?.stepId }.takeLast(12).forEach {
            appendLine("${it.invocationId} ${it.toolName} ${it.status}")
        }
        if (schedule.stalled) appendLine("No executable step. Replan or finish blocked/incomplete; never bypass dependencies.")
        if (diagnostic.isNotBlank()) appendLine("Planning: $diagnostic")
    }.take(5_500) }.orEmpty()

    companion object {
        const val COMPLETE = "complete_plan_step"
        const val REPLAN = "replan_task"
        val TOOLS = listOf(
            ToolDefinition(COMPLETE, "Propose completion of ACTIVE plan step. Runtime P0 checks exact persisted evidence and output predicates. Call alone.", listOf(
                ToolParameter("step_key", "string", "Exact ACTIVE step key"),
                ToolParameter("evidence_ids", "string", "JSON array of exact persistent invocation ids from TASK PLAN"))),
            ToolDefinition(REPLAN, "Request a revised plan after a discovery or dependency failure. Call alone. Never changes the user's original request.", listOf(
                ToolParameter("reason", "string", "Short operational reason, no commands or private data")))
        )
    }
}
