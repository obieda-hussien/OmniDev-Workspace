package com.omnidev.workspace.data.brain

import android.content.Context
import com.omnidev.workspace.domain.engine.ModePreferenceSource
import com.omnidev.workspace.domain.engine.OmniMode
import kotlin.math.max

/**
 * Local-only learner for explicit user decisions around execution-mode handoffs.
 *
 * This learns preferences, never permissions. Even a 100% historical acceptance rate cannot
 * authorize a future switch; it may only calibrate recommendation confidence/noise.
 */
class UserFeedbackLearningStore(context: Context) : ModePreferenceSource {

    data class TransitionPreference(
        val accepted: Int,
        val denied: Int
    ) {
        val observations: Int get() = accepted + denied
        val acceptanceRate: Float
            get() = if (observations == 0) 0.5f else accepted.toFloat() / observations.toFloat()

        /** Beta(1,1) posterior mean. */
        val smoothedAcceptance: Float
            get() = (accepted + 1f) / (observations + 2f)

        /**
         * Wilson-like confidence strength approximation: grows slowly with observations so a
         * handful of clicks cannot dominate routing forever.
         */
        val evidenceStrength: Float
            get() = (observations.toFloat() / (observations + 6f)).coerceIn(0f, 1f)

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

    fun recordModeDecision(from: OmniMode, to: OmniMode, accepted: Boolean) =
        recordModeDecision(from.name, to.name, accepted)

    fun modePreference(from: String, to: String): TransitionPreference {
        val normalizedFrom = normalize(from)
        val normalizedTo = normalize(to)
        return TransitionPreference(
            accepted = prefs.getInt(key(normalizedFrom, normalizedTo, true), 0),
            denied = prefs.getInt(key(normalizedFrom, normalizedTo, false), 0)
        )
    }

    fun modePreference(from: OmniMode, to: OmniMode): TransitionPreference =
        modePreference(from.name, to.name)

    override fun confidenceAdjustment(from: OmniMode, to: OmniMode): Float {
        val preference = modePreference(from, to)
        if (preference.observations < 2) return 0f

        // Center posterior around neutral 0.5, then scale by evidence strength. Maximum impact is
        // intentionally small because preference is not task-success evidence.
        val centered = (preference.smoothedAcceptance - 0.5f) * 2f
        return (centered * preference.evidenceStrength * 0.10f).coerceIn(-0.10f, 0.10f)
    }

    override fun stronglyDisliked(from: OmniMode, to: OmniMode): Boolean =
        modePreference(from, to).stronglyDisliked

    /** Backward-compatible string API. */
    fun recommendationConfidenceAdjustment(from: String, to: String): Float {
        val fromMode = normalizeModeOrNull(from) ?: return 0f
        val toMode = normalizeModeOrNull(to) ?: return 0f
        return confidenceAdjustment(fromMode, toMode)
    }

    /** Compact advisory prompt context; contains no raw user text. */
    fun buildPromptInjection(maxChars: Int = 360): String {
        val modes = listOf(OmniMode.CHAT, OmniMode.AGENT, OmniMode.SWARM)
        val observations = buildList {
            for (from in modes) {
                for (to in modes) {
                    if (from == to) continue
                    val p = modePreference(from, to)
                    if (p.observations >= 2) {
                        add(
                            "${from.name}->${to.name} accepted=${p.accepted} denied=${p.denied} " +
                                "posterior=${((p.smoothedAcceptance * 100).toInt())}%"
                        )
                    }
                }
            }
        }
        if (observations.isEmpty()) return ""
        return buildString {
            append("\nLearned execution-mode preferences (advisory only; NEVER permission): ")
            append(observations.joinToString("; "))
            append(". Avoid repeated low-value suggestions the user usually rejects.")
        }.take(maxChars)
    }

    fun resetPreference(from: OmniMode, to: OmniMode) {
        val f = normalize(from.name)
        val t = normalize(to.name)
        prefs.edit()
            .remove(key(f, t, true))
            .remove(key(f, t, false))
            .apply()
    }

    private fun normalize(mode: String): String = when (mode.trim().uppercase()) {
        "TEAM" -> "SWARM"
        else -> mode.trim().uppercase()
    }

    private fun normalizeModeOrNull(mode: String): OmniMode? = when (normalize(mode)) {
        "CHAT" -> OmniMode.CHAT
        "AGENT" -> OmniMode.AGENT
        "SWARM" -> OmniMode.SWARM
        else -> null
    }

    private fun key(from: String, to: String, accepted: Boolean): String =
        "mode_${from}_${to}_${if (accepted) "accepted" else "denied"}"

    companion object {
        private const val PREFS_NAME = "agent_user_feedback_learning_v1"
    }
}
