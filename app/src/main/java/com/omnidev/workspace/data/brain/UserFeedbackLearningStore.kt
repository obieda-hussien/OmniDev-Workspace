package com.omnidev.workspace.data.brain

import android.content.Context
import kotlin.math.max

/**
 * Tiny local-only preference learner for explicit user feedback.
 *
 * This store learns *preferences*, never permissions. A high historical acceptance rate may
 * make a recommendation less noisy, but it can never authorize an action. Authorization remains
 * the responsibility of the mode/confirmation policy layer.
 */
class UserFeedbackLearningStore(context: Context) {

    data class TransitionPreference(
        val accepted: Int,
        val denied: Int
    ) {
        val observations: Int get() = accepted + denied
        val acceptanceRate: Float
            get() = if (observations == 0) 0.5f else accepted.toFloat() / observations.toFloat()

        /**
         * Bayesian-smoothed preference in [0,1]. The 1/1 prior prevents one click from becoming
         * an overconfident long-term preference.
         */
        val smoothedAcceptance: Float
            get() = (accepted + 1f) / (observations + 2f)

        val stronglyDisliked: Boolean
            get() = observations >= 3 && denied >= max(3, accepted * 2)
    }

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun recordModeDecision(from: String, to: String, accepted: Boolean) {
        val normalizedFrom = normalize(from)
        val normalizedTo = normalize(to)
        if (normalizedFrom == normalizedTo) return
        val key = key(normalizedFrom, normalizedTo, accepted)
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    fun modePreference(from: String, to: String): TransitionPreference {
        val normalizedFrom = normalize(from)
        val normalizedTo = normalize(to)
        return TransitionPreference(
            accepted = prefs.getInt(key(normalizedFrom, normalizedTo, true), 0),
            denied = prefs.getInt(key(normalizedFrom, normalizedTo, false), 0)
        )
    }

    /**
     * Returns a small confidence adjustment for recommendation ranking only.
     * Never use this value as an authorization signal.
     */
    fun recommendationConfidenceAdjustment(from: String, to: String): Float {
        val preference = modePreference(from, to)
        if (preference.observations < 2) return 0f
        return ((preference.smoothedAcceptance - 0.5f) * 0.20f).coerceIn(-0.10f, 0.10f)
    }

    /**
     * User-facing agents can use this compact hint to avoid repeatedly suggesting transitions
     * the user usually rejects. It intentionally contains no raw prompts or personal content.
     */
    fun buildPromptInjection(maxChars: Int = 360): String {
        val modes = listOf("CHAT", "AGENT", "SWARM")
        val observations = buildList {
            for (from in modes) {
                for (to in modes) {
                    if (from == to) continue
                    val p = modePreference(from, to)
                    if (p.observations >= 2) {
                        add("$from->$to accepted=${p.accepted} denied=${p.denied}")
                    }
                }
            }
        }
        if (observations.isEmpty()) return ""
        return buildString {
            append("\n👤 Learned user execution preferences (advisory only; NEVER permission): ")
            append(observations.joinToString("; "))
            append(". Avoid nagging for repeatedly rejected switches unless the current run is genuinely blocked.")
        }.take(maxChars)
    }

    private fun normalize(mode: String): String = when (mode.trim().uppercase()) {
        "TEAM" -> "SWARM"
        else -> mode.trim().uppercase()
    }

    private fun key(from: String, to: String, accepted: Boolean): String =
        "mode_${from}_${to}_${if (accepted) "accepted" else "denied"}"

    companion object {
        private const val PREFS_NAME = "agent_user_feedback_learning_v1"
    }
}
