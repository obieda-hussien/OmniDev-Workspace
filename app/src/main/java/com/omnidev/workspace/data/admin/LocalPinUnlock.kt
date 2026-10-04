package com.omnidev.workspace.data.admin

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.omnidev.workspace.data.accessibility.OmniAccessibilityService
import kotlinx.coroutines.delay

/** Only the standard SystemUI PIN keypad; never coordinates, shell input, patterns or guessed PINs. */
object LocalPinUnlock {
    @Volatile var entering = false
        private set
    private const val SYSTEM_UI = "com.android.systemui"
    private fun root(service: OmniAccessibilityService): AccessibilityNodeInfo? {
        val active = service.rootInActiveWindow
        if (active?.packageName?.toString() == SYSTEM_UI) return active
        active?.recycle()
        for (window in service.windows.sortedByDescending { it.layer }) {
            val node = window.root ?: continue
            if (node.packageName?.toString() == SYSTEM_UI) return node
            node.recycle()
        }
        return null
    }
    private fun node(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        val matches = root.findAccessibilityNodeInfosByViewId("$SYSTEM_UI:id/$id")
        val match = matches.singleOrNull()?.takeIf { it.isVisibleToUser && it.isEnabled }
        matches.filter { it !== match }.forEach { it.recycle() }
        return match
    }
    fun ready(context: Context): Boolean {
        if (!DeviceConsentStore(context).locked()) return false
        val service = OmniAccessibilityService.instance ?: return false
        val root = root(service) ?: return false
        return try {
            val input = node(root, "pinEntry") ?: return false
            val empty = input.text.isNullOrEmpty() && (input.isPassword || input.className?.toString()?.endsWith("PasswordTextView") == true)
            input.recycle()
            empty && (0..9).all { digit -> node(root, "key$digit")?.let { it.recycle(); true } == true } &&
                node(root, "key_enter")?.let { it.recycle(); true } == true
        } finally { root.recycle() }
    }
    suspend fun attempt(context: Context): String {
        val consent = DeviceConsentStore(context)
        if (!consent.enabled(DeviceConsentPolicy.Scope.SAVED_PIN) || !consent.enabled(DeviceConsentPolicy.Scope.UNLOCK))
            return "DENIED: enable local PIN and Android unlock in Device access."
        if (!ready(context)) return "USER_ACTION_REQUIRED: the standard empty SystemUI PIN keypad is unavailable. Open it manually or unlock yourself."
        if (!CredentialInputGate.acquire()) return "USER_ACTION_REQUIRED: another private credential attempt is active."
        entering = true
        try {
            if (!consent.consumePin()) return "USER_ACTION_REQUIRED: authorize one PIN attempt in Device access first."
            val service = OmniAccessibilityService.instance ?: return "USER_ACTION_REQUIRED: Accessibility disconnected."
            val accepted = DevicePinVault(context).withPin { pin ->
                for (digit in pin) {
                    if (!consent.enabled(DeviceConsentPolicy.Scope.SAVED_PIN) || !consent.locked()) return@withPin false
                    val current = root(service) ?: return@withPin false
                    val key = node(current, "key$digit")
                    val clicked = try { key?.isClickable == true && key.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                        finally { key?.recycle(); current.recycle() }
                    if (!clicked) return@withPin false
                }
                // Some devices auto-submit. Never click again after they have unlocked.
                if (!consent.locked()) return@withPin true
                if (!consent.enabled(DeviceConsentPolicy.Scope.SAVED_PIN)) return@withPin false
                val current = root(service) ?: return@withPin false
                val enter = node(current, "key_enter")
                try { enter?.isClickable == true && enter.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    finally { enter?.recycle(); current.recycle() }
            }
            if (!accepted) return "USER_ACTION_REQUIRED: PIN attempt stopped; no automatic retry. Complete unlock manually."
            repeat(15) {
                if (!consent.locked()) return "UNLOCKED: verified with Android keyguard state."
                delay(200)
            }
            return "USER_ACTION_REQUIRED: Android remains locked. No automatic retry; complete unlock manually."
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            return "USER_ACTION_REQUIRED: local PIN attempt unavailable. No automatic retry."
        } finally { entering = false; CredentialInputGate.release() }
    }
}
