package com.omnidev.workspace.domain.engine

import android.content.Context
import com.omnidev.workspace.data.brain.UserFeedbackLearningStore
import java.util.concurrent.ConcurrentHashMap

/**
 * User-controlled authority for agent-initiated execution-mode switches.
 * Recommendations and learned preferences never grant authorization.
 */
class ModeSwitchPermissionStore(context: Context) {
    enum class Approval { ONCE, ALWAYS_THIS_TRANSITION, ALL_THIS_SESSION, DENY }

    enum class SwitchPolicy(val title: String, val description: String) {
        AUTO_APPROVED("Previously approved", "Switch automatically only for transitions you approved earlier."),
        ASK_EVERY_TIME("Ask every time", "Keep the current mode until you approve each proposed switch."),
        STAY_IN_SELECTED_MODE("Stay in selected mode", "Never switch automatically; continue in the selected mode when possible.")
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val feedbackStore = UserFeedbackLearningStore(appContext)
    private val allowAllForSession = ConcurrentHashMap.newKeySet<Long>()
    private val deniedForSession = ConcurrentHashMap.newKeySet<String>()

    fun preferredMode(): OmniMode {
        val raw = prefs.getString(KEY_PREFERRED_MODE, OmniMode.CHAT.name)
        val mode = runCatching { OmniMode.valueOf(raw.orEmpty()) }.getOrDefault(OmniMode.CHAT)
        return OmniMode.constrain(mode).takeUnless { it == OmniMode.AUTO } ?: OmniMode.CHAT
    }

    fun setPreferredMode(mode: OmniMode) {
        if (mode != OmniMode.AUTO && OmniMode.permitted(mode)) {
            prefs.edit().putString(KEY_PREFERRED_MODE, mode.name).apply()
        }
    }

    fun switchPolicy(): SwitchPolicy =
        runCatching { SwitchPolicy.valueOf(prefs.getString(KEY_SWITCH_POLICY, null).orEmpty()) }
            .getOrDefault(SwitchPolicy.AUTO_APPROVED)

    fun setSwitchPolicy(policy: SwitchPolicy) {
        prefs.edit().putString(KEY_SWITCH_POLICY, policy.name).apply()
    }

    private fun transitionKey(from: OmniMode, to: OmniMode) = "allow_${from.name}_${to.name}"
    private fun denialKey(sessionId: Long, from: OmniMode, to: OmniMode) =
        "$sessionId:${transitionKey(from, to)}"

    fun isDeniedForSession(from: OmniMode, to: OmniMode, sessionId: Long?): Boolean =
        sessionId != null && denialKey(sessionId, from, to) in deniedForSession

    fun shouldOfferSwitch(from: OmniMode, to: OmniMode, sessionId: Long?): Boolean =
        ModeSwitchPolicyRules.shouldOffer(switchPolicy(), from, to, isDeniedForSession(from, to, sessionId))

    fun canAutoSwitch(from: OmniMode, to: OmniMode, sessionId: Long?): Boolean {
        if (from == to || to == OmniMode.AUTO || switchPolicy() != SwitchPolicy.AUTO_APPROVED) return false
        if (isDeniedForSession(from, to, sessionId)) return false
        val sessionGrant = sessionId != null && sessionId in allowAllForSession
        val previouslyApproved = sessionGrant || prefs.getBoolean(transitionKey(from, to), false)
        return ModeSwitchPolicyRules.canAutoSwitch(switchPolicy(), previouslyApproved)
    }

    fun recordDecision(from: OmniMode, to: OmniMode, sessionId: Long?, approval: Approval) {
        if (from == to || to == OmniMode.AUTO) return
        when (approval) {
            Approval.ONCE -> if (sessionId != null) deniedForSession.remove(denialKey(sessionId, from, to))
            Approval.ALWAYS_THIS_TRANSITION -> {
                if (sessionId != null) deniedForSession.remove(denialKey(sessionId, from, to))
                prefs.edit().putBoolean(transitionKey(from, to), true).apply()
            }
            Approval.ALL_THIS_SESSION -> if (sessionId != null) {
                deniedForSession.remove(denialKey(sessionId, from, to))
                allowAllForSession += sessionId
            }
            Approval.DENY -> if (sessionId != null) deniedForSession += denialKey(sessionId, from, to)
        }
        feedbackStore.recordModeDecision(from, to, approval != Approval.DENY)
    }

    fun recommendationConfidenceAdjustment(from: OmniMode, to: OmniMode): Float =
        feedbackStore.confidenceAdjustment(from, to)

    fun shouldSuggest(from: OmniMode, to: OmniMode, hardBlocked: Boolean): Boolean =
        hardBlocked || !feedbackStore.stronglyDisliked(from, to)

    fun preferenceSource(): ModePreferenceSource = feedbackStore
    fun buildLearningHint(): String = feedbackStore.buildPromptInjection()

    fun clearPersistentTransition(from: OmniMode, to: OmniMode) {
        prefs.edit().remove(transitionKey(from, to)).apply()
    }

    fun clearSession(sessionId: Long?) {
        if (sessionId == null) return
        allowAllForSession.remove(sessionId)
        val prefix = "$sessionId:"
        deniedForSession.removeAll { it.startsWith(prefix) }
    }

    companion object {
        private const val PREFS_NAME = "adaptive_mode_permissions_v1"
        private const val KEY_PREFERRED_MODE = "preferred_execution_mode"
        private const val KEY_SWITCH_POLICY = "execution_mode_switch_policy"
    }
}
