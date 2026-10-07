package com.omnidev.workspace.data.admin

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.omnidev.workspace.data.accessibility.OmniAccessibilityService
import com.omnidev.workspace.data.assistant.AssistantRuntime
import com.omnidev.workspace.data.assistant.OmniVoiceInteractionService
import com.omnidev.workspace.data.voice.LocalVoiceSessionService
import com.omnidev.workspace.ui.assistant.DeviceUnlockActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/** Only the standard SystemUI PIN keypad; never coordinates, shell input, patterns or guessed PINs. */
object LocalPinUnlock {
    @Volatile var entering = false
        private set
    private val requestGate = Mutex()
    fun authorized(context: Context): Boolean {
        val consent = DeviceConsentStore(context)
        return consent.enabled(DeviceConsentPolicy.Scope.SAVED_PIN) &&
            consent.enabled(DeviceConsentPolicy.Scope.UNLOCK) &&
            consent.pinArmed() && DevicePinVault(context).exists()
    }

    /** Open native authentication and await its empty keypad before authorizing one input. */
    suspend fun request(context: Context): String = withContext(Dispatchers.Main.immediate) {
        if (!requestGate.tryLock()) return@withContext "USER_ACTION_REQUIRED: another saved PIN request is active."
        try {
            val consent = DeviceConsentStore(context)
            if (!consent.locked()) return@withContext "UNLOCKED: verified with Android keyguard state."
            if (!authorized(context)) return@withContext "USER_ACTION_REQUIRED: enable local PIN and Android unlock, save a PIN and authorize its use in Device access."
            if (consent.pinPaused()) return@withContext "USER_ACTION_REQUIRED: saved PIN attempts are paused after a failed or interrupted input. Resume them in Device access; no credential retry or voice fallback."
            if (OmniAccessibilityService.instance == null) return@withContext "USER_ACTION_REQUIRED: Accessibility disconnected. No PIN entered; unlock manually."
            coroutineScope {
                val generation = AssistantRuntime.get(context).sessionGeneration
                val prompt = async(start = CoroutineStart.UNDISPATCHED) { DeviceUnlockActivity.request(context, true) }
                try {
                    when (SavedPinKeypadWait.await(
                        locked = { consent.locked() }, authorized = { authorized(context) },
                        promptFinished = { prompt.isCompleted }, ready = { ready(context) }
                    )) {
                        SavedPinKeypadWait.Outcome.UNLOCKED -> "UNLOCKED: verified with Android keyguard state."
                        SavedPinKeypadWait.Outcome.PROMPT_FINISHED -> prompt.await()
                        SavedPinKeypadWait.Outcome.REVOKED -> "USER_ACTION_REQUIRED: saved PIN authorization expired or was revoked. No PIN entered."
                        SavedPinKeypadWait.Outcome.UNSUPPORTED -> "USER_ACTION_REQUIRED: the standard empty SystemUI PIN keypad did not become available. No PIN entered; unlock manually."
                        SavedPinKeypadWait.Outcome.READY -> attempt(context)
                    }
                } finally {
                    val cancelledHost = !prompt.isCompleted
                    withContext(NonCancellable) { prompt.cancelAndJoin() }
                    // Cancelling the sibling prompt suppresses its own restoration; restore only the same assistant session.
                    if (cancelledHost && currentCoroutineContext().isActive && !LocalVoiceSessionService.handoff &&
                        AssistantRuntime.targetingScreen && AssistantRuntime.get(context).sessionGeneration == generation) {
                        runCatching { OmniVoiceInteractionService.resume(context) }
                    }
                }
            }
        } finally { requestGate.unlock() }
    }
    private fun root(service: OmniAccessibilityService) = SystemUiCredentialControls.root(service)
    private fun node(root: AccessibilityNodeInfo, id: String) = SystemUiCredentialControls.find(root, id)
    fun ready(context: Context): Boolean {
        if (!DeviceConsentStore(context).locked()) return false
        val service = OmniAccessibilityService.instance ?: return false
        val root = root(service) ?: return false
        return try {
            val input = node(root, "pinEntry") ?: return false
            val empty = SystemUiCredentialControls.emptyPin(input)
            input.recycle()
            empty && (0..9).all { digit -> node(root, "key$digit")?.let { val clickable = it.isClickable; it.recycle(); clickable } == true }
        } finally { root.recycle() }
    }
    suspend fun attempt(context: Context): String {
        val consent = DeviceConsentStore(context)
        if (!consent.locked()) return "UNLOCKED: verified with Android keyguard state."
        if (!consent.enabled(DeviceConsentPolicy.Scope.SAVED_PIN) || !consent.enabled(DeviceConsentPolicy.Scope.UNLOCK))
            return "DENIED: enable local PIN and Android unlock in Device access."
        if (!ready(context)) return "USER_ACTION_REQUIRED: the standard empty SystemUI PIN keypad is unavailable. Open it manually or unlock yourself."
        if (!CredentialInputGate.acquire()) return "USER_ACTION_REQUIRED: another private credential attempt is active."
        entering = true
        var consumed = false
        try {
            if (!consent.consumePin()) return "USER_ACTION_REQUIRED: authorize saved PIN use or resume paused attempts in Device access first. No PIN entered."
            consumed = true
            val service = OmniAccessibilityService.instance ?: return "USER_ACTION_REQUIRED: Accessibility disconnected."
            val accepted = DevicePinVault(context).withPin { pin ->
                for (digit in pin) {
                    if (!consent.locked()) return@withPin true
                    if (!consent.enabled(DeviceConsentPolicy.Scope.SAVED_PIN) || !consent.enabled(DeviceConsentPolicy.Scope.UNLOCK)) return@withPin false
                    val current = root(service) ?: return@withPin false
                    val key = node(current, "key$digit")
                    val clicked = try { key?.isClickable == true && key.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                        finally { key?.recycle(); current.recycle() }
                    if (!clicked) return@withPin false
                    delay(120) // Let OEM SystemUI consume each click before reading its next tree.
                }
                // Some devices auto-submit. Never click again after they have unlocked.
                if (!consent.locked()) return@withPin true
                if (!consent.enabled(DeviceConsentPolicy.Scope.SAVED_PIN) || !consent.enabled(DeviceConsentPolicy.Scope.UNLOCK)) return@withPin false
                // Auto-submit may remove the keypad before Android's lock state settles.
                val current = root(service) ?: return@withPin true
                val enter = node(current, "key_enter")
                try { enter == null || (enter.isClickable && enter.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
                    finally { enter?.recycle(); current.recycle() }
            }
            if (!accepted) return "USER_ACTION_REQUIRED: PIN attempt stopped; no automatic retry. Complete unlock manually."
            repeat(25) {
                if (!consent.locked()) return "UNLOCKED: verified with Android keyguard state."
                delay(200)
            }
            return "USER_ACTION_REQUIRED: Android remains locked. No automatic retry; complete unlock manually."
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            return "USER_ACTION_REQUIRED: local PIN attempt unavailable. No automatic retry."
        } finally {
            try { if (consumed) consent.finishPinAttempt(!consent.locked()) }
            finally { entering = false; CredentialInputGate.release() }
        }
    }
}
