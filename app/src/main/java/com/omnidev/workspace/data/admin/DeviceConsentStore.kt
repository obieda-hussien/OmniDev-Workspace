package com.omnidev.workspace.data.admin

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import com.omnidev.workspace.core.policy.TierPolicyHolder
import com.omnidev.workspace.data.accessibility.AccessibilityStateManager
import com.omnidev.workspace.data.assistant.AssistantRuntime

class DeviceConsentStore(context: Context) {
    private val context = context.applicationContext
    private val prefs by lazy { this.context.getSharedPreferences("device-user-consent", Context.MODE_PRIVATE) }
    private val savedPinAuthorization by lazy { SavedPinAuthorization(
        read = { runCatching { SavedPinAuthorization.State.valueOf(prefs.getString(PIN_AUTHORIZATION, null).orEmpty()) }
            .getOrDefault(SavedPinAuthorization.State.NONE) },
        write = { state -> prefs.edit().putString(PIN_AUTHORIZATION, state.name).commit() }
    ) }
    private fun available() = context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
    fun enabled(scope: DeviceConsentPolicy.Scope): Boolean = available() &&
        TierPolicyHolder.current.allowAccessibility &&
        (scope != DeviceConsentPolicy.Scope.SAVED_PIN || TierPolicyHolder.current.tier == "ADMIN") &&
        prefs.getBoolean(scope.name, false)

    /** Called only by the local settings UI after Android credential confirmation. */
    fun setFromUser(scope: DeviceConsentPolicy.Scope, enabled: Boolean): Boolean {
        if (!available()) return false
        val saved = synchronized(pinGate) {
            val edit = prefs.edit().putBoolean(scope.name, enabled)
            if (!enabled && scope in setOf(DeviceConsentPolicy.Scope.SAVED_PIN, DeviceConsentPolicy.Scope.UNLOCK))
                edit.remove(PIN_AUTHORIZATION)
            edit.commit()
        }
        if (saved) {
            OmniDeviceAdminReceiver.recordDeviceAccess("CONSENT_CHANGE", true)
            AccessibilityStateManager.updateRootNode(null)
            com.omnidev.workspace.data.accessibility.SemanticUITool.clearSnapshot()
            AssistantRuntime.get(context).clearScreen()
            if (!enabled) permit.revoke()
            if (scope == DeviceConsentPolicy.Scope.SAVED_PIN && !enabled) DevicePinVault(context).delete()
        }
        return saved
    }
    fun locked(): Boolean = context.getSystemService(KeyguardManager::class.java)?.let {
        it.isDeviceLocked || it.isKeyguardLocked
    } ?: true
    fun isSettings(pkg: String?): Boolean = pkg != null && (pkg == "com.android.settings" || pkg ==
        context.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName)
    fun denial(pkg: String?, screenshot: Boolean = false, mutation: Boolean = false): String? =
        DeviceConsentPolicy.denial(locked(), isSettings(pkg), screenshot, mutation,
            enabled(DeviceConsentPolicy.Scope.LOCK_OBSERVE), enabled(DeviceConsentPolicy.Scope.SETTINGS))
    /** Called only by authenticated local settings, never by a model/tool argument. */
    fun armPin(): Boolean = synchronized(pinGate) {
        if (!canAuthorizePin() || !savedPinAuthorization.revoke()) return@synchronized false
        permit.arm(SystemClock.elapsedRealtime())
        true
    }
    /** An explicit opt-in; existing one-shot permits are never migrated to a remembered grant. */
    fun rememberPinFromUser(): Boolean = synchronized(pinGate) {
        if (!canAuthorizePin()) return@synchronized false
        val saved = savedPinAuthorization.rememberFromUser()
        if (saved) { permit.revoke(); OmniDeviceAdminReceiver.recordDeviceAccess("PIN_AUTHORIZATION_REMEMBERED", true) }
        saved
    }
    private fun canAuthorizePin() = !locked() && enabled(DeviceConsentPolicy.Scope.SAVED_PIN) &&
        enabled(DeviceConsentPolicy.Scope.UNLOCK) && DevicePinVault(context).exists()
    fun pinRemembered() = available() && synchronized(pinGate) { savedPinAuthorization.granted() }
    fun pinPaused() = available() && synchronized(pinGate) { savedPinAuthorization.paused() }
    fun pinArmed() = available() && synchronized(pinGate) {
        savedPinAuthorization.granted() || permit.active(SystemClock.elapsedRealtime())
    }
    fun consumePin(): Boolean = synchronized(pinGate) {
        if (!enabled(DeviceConsentPolicy.Scope.SAVED_PIN) || !enabled(DeviceConsentPolicy.Scope.UNLOCK)) return@synchronized false
        if (savedPinAuthorization.granted()) savedPinAuthorization.beginAttempt()
        else permit.consume(SystemClock.elapsedRealtime())
    }
    fun finishPinAttempt(androidUnlocked: Boolean) = synchronized(pinGate) {
        // A revoked grant cannot be recreated by a late successful callback.
        if (available()) savedPinAuthorization.finishAttempt(androidUnlocked)
    }
    fun revokePinAuthorization(): Boolean = synchronized(pinGate) {
        permit.revoke()
        val saved = available() && savedPinAuthorization.revoke()
        if (saved) OmniDeviceAdminReceiver.recordDeviceAccess("PIN_AUTHORIZATION_REVOKED", true)
        saved
    }
    fun pinAuthorizationStatus(): String = when {
        pinPaused() -> "remembered; attempts paused after a failed or interrupted input"
        pinRemembered() -> "remembered until revoked"
        pinArmed() -> "one attempt authorized for up to 15 minutes"
        else -> "not authorized"
    }
    fun revokeAll() {
        OmniDeviceAdminReceiver.recordDeviceAccess("CONSENT_REVOKE", true)
        synchronized(pinGate) {
            if (available()) prefs.edit().clear().commit()
            permit.revoke()
        }
        DevicePinVault(context).delete()
        AccessibilityStateManager.updateRootNode(null)
        com.omnidev.workspace.data.accessibility.SemanticUITool.clearSnapshot()
        AssistantRuntime.get(context).clearScreen()
    }
    companion object {
        private const val PIN_AUTHORIZATION = "local-pin-authorization"
        private val pinGate = Any()
        private val permit = OneShotUnlockPermit()
    }
}
