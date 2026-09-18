package com.omnidev.workspace.domain.engine

/**
 * Read-only preference contract consumed by the local mode router.
 * Implementations may learn from user decisions, but MUST NOT expose authorization state here.
 */
interface ModePreferenceSource {
    fun confidenceAdjustment(from: OmniMode, to: OmniMode): Float
    fun stronglyDisliked(from: OmniMode, to: OmniMode): Boolean
}

/**
 * Local-only execution-mode router.
 *
 * Routing is evidence-based and deterministic. Static task signals are treated as priors and are
 * calibrated by two independent local feedback channels:
 * 1) explicit user preference about a transition, and
 * 2) observed runtime outcome utility for the same coarse task shape.
 *
 * Neither channel grants authority. [ModeSwitchPermissionStore] remains the sole authority for
 * automatic switches.
 */
object AdaptiveModeRouter {

    data class Suggestion(
        val from: OmniMode,
        val to: OmniMode,
        val reason: String,
        val confidence: Float,
        val trigger: Trigger,
        val evidence: List<String> = emptyList()
    )

    enum class Trigger {
        CHAT_CAPABILITY_GAP,
        AGENT_STUCK,
        AGENT_COMPLEXITY,
        TEAM_OVERHEAD
    }

    private enum class FailureClass {
        INFRASTRUCTURE,
        USER_ACTION,
        STAGNATION,
        CONTEXT_PRESSURE,
        DETERMINISTIC_TASK_FAILURE,
        UNKNOWN
    }

    @Volatile
    private var preferenceSource: ModePreferenceSource? = null

    fun installPreferenceSource(source: ModePreferenceSource?) {
        preferenceSource = source
    }

    /**
     * Agent -> Team escalation. Team is selected only when failure evidence and decomposition
     * evidence agree. Local historical outcomes can gently raise/lower confidence but cannot
     * override infrastructure blockers or the user's authority boundary.
     */
    fun fromAgentFailure(errorMessage: String, userRequest: String = ""): Suggestion? {
        val failureClass = classifyFailure(errorMessage)
        if (
            failureClass == FailureClass.INFRASTRUCTURE ||
            failureClass == FailureClass.USER_ACTION
        ) return null

        val signals = IntentClassifier.analyze(userRequest)
        val scores = IntentClassifier.scoreModes(signals)
        val evidence = mutableListOf<String>()

        val failureEvidence = when (failureClass) {
            FailureClass.STAGNATION -> {
                evidence += "single-agent execution stagnated"
                0.34f
            }
            FailureClass.CONTEXT_PRESSURE -> {
                evidence += "single-agent context/budget pressure"
                0.24f
            }
            FailureClass.DETERMINISTIC_TASK_FAILURE -> {
                evidence += "task failed but failure is not infrastructure-wide"
                0.12f
            }
            FailureClass.UNKNOWN -> 0.04f
            FailureClass.INFRASTRUCTURE,
            FailureClass.USER_ACTION -> 0f
        }

        val decompositionEvidence =
            signals.parallelism * 0.28f +
                signals.breadth * 0.18f +
                signals.complexity * 0.10f +
                scores.swarm * 0.10f

        if (signals.parallelism >= 0.45f) evidence += "request contains independent work streams"
        if (signals.breadth >= 0.55f) evidence += "request spans multiple domains/components"
        if (signals.verificationIntent >= 0.45f) evidence += "verification can be separated from implementation"

        val outcomeSignal = ModeOutcomeLearner.signal(userRequest, OmniMode.SWARM)
        addOutcomeEvidence(evidence, outcomeSignal, OmniMode.SWARM)

        var confidence = 0.30f + failureEvidence + decompositionEvidence
        confidence += preferenceAdjustment(OmniMode.AGENT, OmniMode.SWARM)
        confidence += outcomeSignal.adjustment
        confidence = confidence.coerceIn(0f, 0.97f)

        val enoughDecomposition = signals.parallelism >= 0.30f || signals.breadth >= 0.58f
        val criticalStall = failureClass == FailureClass.STAGNATION && confidence >= 0.76f
        if (!enoughDecomposition && !criticalStall) return null
        if (confidence < 0.68f) return null
        if (isStronglyDisliked(OmniMode.AGENT, OmniMode.SWARM) && !criticalStall) return null

        val reason = when {
            signals.parallelism >= 0.55f ->
                "The single Agent is no longer making reliable progress, and the remaining work has independent parts. Team Agents can split the work and continue from the saved checkpoint without redoing completed steps."
            signals.breadth >= 0.58f ->
                "The task now spans enough distinct components that a single loop is becoming inefficient. Team Agents can decompose the remaining work while reusing the current checkpoint."
            else ->
                "The Agent hit a persistent no-progress condition. Team Agents can try an independent decomposition/recovery path while preserving the current checkpoint."
        }

        return Suggestion(
            from = OmniMode.AGENT,
            to = OmniMode.SWARM,
            reason = reason,
            confidence = confidence,
            trigger = if (failureClass == FailureClass.STAGNATION) Trigger.AGENT_STUCK else Trigger.AGENT_COMPLEXITY,
            evidence = evidence.distinct().take(6)
        )
    }

    /**
     * Team -> Agent de-escalation. The effective runtime plan matters more than planner intent:
     * if two tasks are both serialized after safety classification, Team cannot gain useful
     * concurrency even when the planner forgot to express an explicit dependency edge.
     */
    fun fromTeamPlan(
        taskCount: Int,
        parallelSafeTaskCount: Int,
        dependencyEdgeCount: Int = 0,
        userRequest: String = ""
    ): Suggestion? {
        if (taskCount <= 0) return null

        val atomic = taskCount == 1
        val tinySerial = taskCount == 2 && parallelSafeTaskCount == 0
        if (!atomic && !tinySerial) return null

        val evidence = mutableListOf(
            "tasks=$taskCount",
            "parallelSafe=$parallelSafeTaskCount",
            "dependencyEdges=$dependencyEdgeCount"
        )
        val outcomeSignal = ModeOutcomeLearner.signal(userRequest, OmniMode.AGENT)
        addOutcomeEvidence(evidence, outcomeSignal, OmniMode.AGENT)

        var confidence = if (atomic) 0.92f else if (dependencyEdgeCount > 0) 0.80f else 0.74f
        confidence += preferenceAdjustment(OmniMode.SWARM, OmniMode.AGENT)
        confidence += outcomeSignal.adjustment
        confidence = confidence.coerceIn(0f, 0.97f)

        if (confidence < 0.64f) return null
        if (isStronglyDisliked(OmniMode.SWARM, OmniMode.AGENT) && !atomic) return null

        return Suggestion(
            from = OmniMode.SWARM,
            to = OmniMode.AGENT,
            reason = if (atomic) {
                "The Team planner found only one atomic task. A single Agent can continue with the same context and avoid planner/worker/synthesis overhead."
            } else {
                "The effective Team plan has only two serialized tasks and no useful parallel work. A single Agent can execute them more efficiently from the same checkpoint."
            },
            confidence = confidence,
            trigger = Trigger.TEAM_OVERHEAD,
            evidence = evidence.distinct().take(6)
        )
    }

    /**
     * Chat -> execution recommendation for callers that need a deterministic capability-gap check.
     * The selected target is still a proposal only.
     */
    fun fromChatRequest(userRequest: String): Suggestion? {
        val signals = IntentClassifier.analyze(userRequest)
        val scores = IntentClassifier.scoreModes(signals)
        // Explicit mutation/verification language is execution intent even when the bounded
        // evidence normalizer keeps the aggregate score below the old 0.42 threshold. This is
        // especially common for broad parallel requests such as "implement UI, DB, security and
        // tests in parallel". Do not let Chat swallow a concrete executable request.
        val hasExplicitExecution =
            signals.mutationIntent >= 0.16f || signals.verificationIntent >= 0.18f
        if (signals.executionIntent < 0.34f && !hasExplicitExecution) return null

        val target = if (
            scores.swarm >= 0.66f &&
            signals.parallelism >= 0.38f &&
            scores.swarm >= scores.agent + 0.06f
        ) OmniMode.SWARM else OmniMode.AGENT

        val outcomeSignal = ModeOutcomeLearner.signal(userRequest, target)
        val evidence = mutableListOf(
            "execution=${signals.executionIntent.format2()}",
            "parallelism=${signals.parallelism.format2()}",
            "breadth=${signals.breadth.format2()}"
        )
        addOutcomeEvidence(evidence, outcomeSignal, target)

        var confidence = scores.score(target)
        confidence += preferenceAdjustment(OmniMode.CHAT, target)
        confidence += outcomeSignal.adjustment
        confidence = confidence.coerceIn(0f, 0.96f)
        if (confidence < 0.58f) return null
        if (isStronglyDisliked(OmniMode.CHAT, target) && confidence < 0.82f) return null

        return Suggestion(
            from = OmniMode.CHAT,
            to = target,
            reason = if (target == OmniMode.SWARM) {
                "This request requires execution across multiple independent components. Team Agents can perform the work instead of only describing it."
            } else {
                "This request requires execution tools and project/device access. Agent mode can perform the work instead of only describing it."
            },
            confidence = confidence,
            trigger = Trigger.CHAT_CAPABILITY_GAP,
            evidence = evidence.distinct().take(6)
        )
    }

    internal fun estimateParallelism(text: String): Float = IntentClassifier.analyze(text).parallelism

    private fun classifyFailure(message: String): FailureClass {
        val error = message.lowercase()

        if (containsAny(
                error,
                "rate limit", "429", "api key", "unauthorized", "forbidden", "401", "403",
                "model not found", "provider cooldown", "insufficient quota", "quota exceeded",
                "network timeout", "internet connection", "dns", "connection refused",
                "service unavailable", "http 502", "http 503", "http 504",
                "persistent infrastructure", "backend failure", "transport circuit breaker",
                "termux_run_command_unavailable", "shizuku_unavailable",
                "shizuku_permission_required", "rish_unavailable",
                "android_backend_unavailable", "root_unavailable"
            )
        ) return FailureClass.INFRASTRUCTURE

        if (containsAny(
                error,
                "user_action_required", "waiting for user approval", "user approval is required",
                "android is waiting for user approval", "open omnidev and retry",
                "captcha", "manual confirmation required"
            )
        ) return FailureClass.USER_ACTION

        if (containsAny(
                error,
                "maximum iterations", "max iterations", "repeated with identical",
                "agent stuck", "no progress", "without completing", "stagnat",
                "read-only operations", "loop detected", "no-progress loop",
                "observations are repeating", "strategy is oscillating"
            )
        ) return FailureClass.STAGNATION

        if (containsAny(
                error,
                "token budget", "context window", "context limit", "too large", "compaction",
                "maximum context", "input budget"
            )
        ) return FailureClass.CONTEXT_PRESSURE

        if (containsAny(
                error,
                "build failed", "compile failed", "tests failed", "verification failed",
                "tool execution failed", "cannot complete", "unable to complete"
            )
        ) return FailureClass.DETERMINISTIC_TASK_FAILURE

        return FailureClass.UNKNOWN
    }

    private fun addOutcomeEvidence(
        evidence: MutableList<String>,
        signal: ModeOutcomeLearner.Signal,
        target: OmniMode
    ) {
        if (signal.observations < 3) return
        if (kotlin.math.abs(signal.adjustment) < 0.015f) return
        val direction = if (signal.adjustment > 0f) "supports" else "penalizes"
        evidence += "local outcome history $direction ${target.label} for ${signal.bucket} (n=${signal.observations})"
    }

    private fun preferenceAdjustment(from: OmniMode, to: OmniMode): Float =
        preferenceSource?.confidenceAdjustment(from, to)?.coerceIn(-0.12f, 0.12f) ?: 0f

    private fun isStronglyDisliked(from: OmniMode, to: OmniMode): Boolean =
        preferenceSource?.stronglyDisliked(from, to) == true

    private fun containsAny(haystack: String, vararg needles: String): Boolean =
        needles.any { it in haystack }

    private fun Float.format2(): String = ((this * 100f).toInt() / 100f).toString()
}
