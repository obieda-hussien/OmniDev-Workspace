package com.omnidev.workspace.domain.engine

import android.content.Context
import com.omnidev.workspace.data.brain.UserFeedbackLearningStore
import java.util.concurrent.ConcurrentHashMap

/**
 * User-controlled authority for agent-initiated execution-mode switches.
 *
 * A router may recommend a switch, but it may not silently expand execution capability unless
 * the user previously granted an explicit matching permission. Learned preference is kept in a
 * separate local store and can only tune recommendation confidence/noise — never authorization.
 */
class ModeSwitchPermissionStore(context: Context) {

    enum class Approval {
        ONCE,
        ALWAYS_THIS_TRANSITION,
        ALL_THIS_SESSION,
        DENY
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val feedbackStore = UserFeedbackLearningStore(appContext)

    /** Session grants intentionally live only in process memory. */
    private val allowAllForSession = ConcurrentHashMap.newKeySet<Long>()

    fun canAutoSwitch(from: OmniMode, to: OmniMode, sessionId: Long?): Boolean {
        if (from == to || to == OmniMode.AUTO) return false
        if (sessionId != null && sessionId in allowAllForSession) return true
        return prefs.getBoolean(allowKey(from, to), false)
    }

    fun recordDecision(
        from: OmniMode,
        to: OmniMode,
        sessionId: Long?,
        approval: Approval
    ) {
        if (from == to || to == OmniMode.AUTO) return

        when (approval) {
            Approval.ONCE -> Unit
            Approval.ALWAYS_THIS_TRANSITION -> prefs.edit()
                .putBoolean(allowKey(from, to), true)
                .apply()
            Approval.ALL_THIS_SESSION -> if (sessionId != null) allowAllForSession += sessionId
            Approval.DENY -> Unit
        }

        feedbackStore.recordModeDecision(
            from = from,
            to = to,
            accepted = approval != Approval.DENY
        )
    }

    /** Advisory preference only. A positive value is never permission. */
    fun recommendationConfidenceAdjustment(from: OmniMode, to: OmniMode): Float =
        feedbackStore.confidenceAdjustment(from, to)

    fun shouldSuggest(from: OmniMode, to: OmniMode, hardBlocked: Boolean): Boolean {
        if (hardBlocked) return true
        return !feedbackStore.stronglyDisliked(from, to)
    }

    /** Lets the router consume preference signals without seeing authorization state. */
    fun preferenceSource(): ModePreferenceSource = feedbackStore

    fun buildLearningHint(): String = feedbackStore.buildPromptInjection()

    fun clearPersistentTransition(from: OmniMode, to: OmniMode) {
        prefs.edit().remove(allowKey(from, to)).apply()
    }

    fun clearSession(sessionId: Long?) {
        if (sessionId != null) allowAllForSession.remove(sessionId)
    }

    private fun allowKey(from: OmniMode, to: OmniMode) =
        "allow_${from.name}_${to.name}"

    companion object {
        private const val PREFS_NAME = "adaptive_mode_permissions_v1"
    }
}
