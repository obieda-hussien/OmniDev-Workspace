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
        /** Approve this request only. */
        ONCE,
        /** Remember this exact FROM -> TO transition across future sessions. */
        ALWAYS_THIS_TRANSITION,
        /** Allow any agent-initiated mode transition for the current chat session only. */
        ALL_THIS_SESSION,
        /** Reject this request. No future permission is granted. */
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
        when (approval) {
            Approval.ONCE -> Unit
            Approval.ALWAYS_THIS_TRANSITION -> prefs.edit()
                .putBoolean(allowKey(from, to), true)
                .apply()
            Approval.ALL_THIS_SESSION -> if (sessionId != null) allowAllForSession += sessionId
            Approval.DENY -> Unit
        }

        feedbackStore.recordModeDecision(
            from = from.name,
            to = to.name,
            accepted = approval != Approval.DENY
        )
    }

    /** Advisory preference only. A positive value is never permission. */
    fun recommendationConfidenceAdjustment(from: OmniMode, to: OmniMode): Float =
        feedbackStore.recommendationConfidenceAdjustment(from.name, to.name)

    /**
     * Suppress low-value nagging after repeated rejection. Hard blockers can explicitly bypass
     * this in the caller, because a newly blocked run may still need to explain the only viable
     * recovery path to the user.
     */
    fun shouldSuggest(from: OmniMode, to: OmniMode, hardBlocked: Boolean): Boolean {
        if (hardBlocked) return true
        return !feedbackStore.modePreference(from.name, to.name).stronglyDisliked
    }

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
