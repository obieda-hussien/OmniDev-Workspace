package com.omnidev.workspace.domain.engine

/**
 * Local-only execution-mode router.
 *
 * This deliberately avoids a network/model call.  It consumes deterministic runtime
 * evidence (failures, team plan shape and task wording) and emits a *suggestion* only;
 * [ModeSwitchPermissionStore] remains the authority for whether that suggestion may
 * execute automatically.
 */
object AdaptiveModeRouter {

    data class Suggestion(
        val from: OmniMode,
        val to: OmniMode,
        val reason: String,
        val confidence: Float,
        val trigger: Trigger
    )

    enum class Trigger {
        CHAT_CAPABILITY_GAP,
        AGENT_STUCK,
        AGENT_COMPLEXITY,
        TEAM_OVERHEAD
    }

    /**
     * Returns an Agent -> Team suggestion only for failures where another independent
     * worker / decomposition can plausibly help. Provider outages, auth failures and rate
     * limits are intentionally excluded because spawning more workers would only amplify
     * the same failure and waste tokens.
     */
    fun fromAgentFailure(errorMessage: String, userRequest: String = ""): Suggestion? {
        val error = errorMessage.lowercase()

        val infrastructureFailure = listOf(
            "rate limit", "429", "api key", "unauthorized", "forbidden",
            "model not found", "network timeout", "internet connection",
            "provider cooldown", "insufficient quota"
        ).any(error::contains)
        if (infrastructureFailure) return null

        val hardStall = listOf(
            "maximum iterations", "max iterations", "repeated with identical",
            "agent stuck", "no progress", "token budget", "without completing"
        ).any(error::contains)

        if (!hardStall) return null

        val parallelism = estimateParallelism(userRequest)
        val confidence = (0.78f + parallelism * 0.18f).coerceAtMost(0.96f)
        val reason = if (parallelism >= 0.45f) {
            "The single agent reached a no-progress/complexity limit. The remaining work appears splittable, so Team Agents can continue from the saved checkpoint with focused workers instead of restarting."
        } else {
            "The single agent reached a no-progress limit. Team Agents can try a fresh decomposition and recovery path while reusing the saved checkpoint."
        }

        return Suggestion(
            from = OmniMode.AGENT,
            to = OmniMode.SWARM,
            reason = reason,
            confidence = confidence,
            trigger = Trigger.AGENT_STUCK
        )
    }

    /**
     * Team mode is wasteful when the orchestrator itself discovers that there is only one
     * atomic task and no parallel work.  In that case a single Agent keeps all context in
     * one loop and avoids planner/worker/synthesis overhead.
     */
    fun fromTeamPlan(taskCount: Int, parallelSafeTaskCount: Int): Suggestion? {
        if (taskCount != 1 || parallelSafeTaskCount > 1) return null
        return Suggestion(
            from = OmniMode.SWARM,
            to = OmniMode.AGENT,
            reason = "The Team plan contains only one atomic task, so a single Agent can continue with less coordination and token overhead.",
            confidence = 0.91f,
            trigger = Trigger.TEAM_OVERHEAD
        )
    }

    /** Cheap, language-tolerant signal used only to tune confidence, never as authority. */
    internal fun estimateParallelism(text: String): Float {
        if (text.isBlank()) return 0f
        val lower = text.lowercase()
        val domains = listOf(
            "ui", "واجهة", "database", "قاعدة", "backend", "api", "tests", "اختبارات",
            "browser", "متصفح", "gradle", "build", "memory", "ذاكرة", "shizuku",
            "security", "أمان", "performance", "أداء"
        ).count(lower::contains)
        val splitWords = listOf(
            " and ", " + ", "كمان", "وكمان", "كل المشاكل", "عدة", "multiple",
            "several", "independent", "parallel", "بالتوازي"
        ).count(lower::contains)
        return ((domains * 0.12f) + (splitWords * 0.16f)).coerceIn(0f, 1f)
    }
}
