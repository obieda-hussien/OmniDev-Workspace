package com.omnidev.workspace.data.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import com.omnidev.workspace.ui.assistant.AssistantActivity

class OmniVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() { super.onReady(); active = this }
    override fun onShutdown() {
        if (active === this) { active = null; com.omnidev.workspace.data.voice.LocalWakeService.stop(this); com.omnidev.workspace.data.voice.LocalVoiceSessionService.stop(this) }
        super.onShutdown()
    }
    override fun onDestroy() {
        if (active === this) { active = null; com.omnidev.workspace.data.voice.LocalWakeService.stop(this); com.omnidev.workspace.data.voice.LocalVoiceSessionService.stop(this) }
        super.onDestroy()
    }
    companion object {
        const val RESUME = "resume_assistant_session"
        const val WAKE_INVOCATION = "local_wake_invocation"
        const val UNLOCK_REQUEST = "local_unlock_request"
        @Volatile private var active: OmniVoiceInteractionService? = null
        val ready get() = active != null
        fun showForUnlock(context: Context): Boolean {
            val consent = com.omnidev.workspace.data.admin.DeviceConsentStore(context)
            if (!consent.enabled(com.omnidev.workspace.data.admin.DeviceConsentPolicy.Scope.LOCK_OVERLAY)) return false
            if (context.getSystemService(android.os.PowerManager::class.java)?.isInteractive != true &&
                !consent.enabled(com.omnidev.workspace.data.admin.DeviceConsentPolicy.Scope.WAKE)) return false
            val service = active ?: return false
            return runCatching { service.showSession(Bundle().apply { putBoolean(RESUME, true); putBoolean(UNLOCK_REQUEST, true) }, 0) }.isSuccess
        }
        /** Use the selected system voice service; no background Activity-launch workaround. */
        fun wake(context: Context): Boolean {
            if (!com.omnidev.workspace.data.voice.WakePreferences(context).allowedNow()) return false
            val service = active ?: return false
            return runCatching { service.showSession(Bundle().apply { putBoolean(RESUME, false); putBoolean(WAKE_INVOCATION, true) }, 0) }.isSuccess
        }
        fun resume(context: Context) {
            val service = active
            if (service != null && runCatching { service.showSession(Bundle().apply { putBoolean(RESUME, true) }, 0) }.isSuccess) return
            context.startActivity(Intent(context, AssistantActivity::class.java)
                .putExtra(RESUME, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
