package com.omnidev.workspace.domain.engine

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * User-controlled authority for agent-initiated execution-mode switches.
 *
 * Security/agency rule: a router may *recommend* a switch, but it may not silently expand
 * execution capability unless the user previously granted an explicit matching permission.
 * Manual tab changes are always user-authorized and do not consult this store.
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

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

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

        // Keep tiny aggregate feedback. This is not an authorization mechanism; it is only
        // learning/telemetry for the local Agent Brain so it can avoid nagging the user.
        val key = statsKey(from, to, approval != Approval.DENY)
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    fun clearPersistentTransition(from: OmniMode, to: OmniMode) {
        prefs.edit().remove(allowKey(from, to)).apply()
    }

    fun clearSession(sessionId: Long?) {
        if (sessionId != null) allowAllForSession.remove(sessionId)
    }

    /**
     * Compact local-only feedback for the brain prompt. It describes observed preference but
     * never turns statistical history into permission; [canAutoSwitch] remains explicit-only.
     */
    fun buildLearningHint(): String {
        val transitions = buildList {
            for (from in MANUAL_MODES) {
                for (to in MANUAL_MODES) {
                    if (from == to) continue
                    val accepted = prefs.getInt(statsKey(from, to, true), 0)
                    val denied = prefs.getInt(statsKey(from, to, false), 0)
                    if (accepted + denied >= 2) {
                        add("${from.name}->${to.name}: accepted=$accepted denied=$denied")
                    }
                }
            }
        }
        if (transitions.isEmpty()) return ""
        return "User mode-switch feedback (preference only; never permission): ${transitions.joinToString("; ")}"
            .take(480)
    }

    private fun allowKey(from: OmniMode, to: OmniMode) =
        "allow_${from.name}_${to.name}"

    private fun statsKey(from: OmniMode, to: OmniMode, accepted: Boolean) =
        "stats_${from.name}_${to.name}_${if (accepted) "accepted" else "denied"}"

    companion object {
        private const val PREFS_NAME = "adaptive_mode_permissions_v1"
        private val MANUAL_MODES = listOf(OmniMode.CHAT, OmniMode.AGENT, OmniMode.SWARM)
    }
}
