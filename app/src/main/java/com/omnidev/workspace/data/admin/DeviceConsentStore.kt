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
    private fun available() = context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
    fun enabled(scope: DeviceConsentPolicy.Scope): Boolean = available() &&
        TierPolicyHolder.current.allowAccessibility &&
        (scope != DeviceConsentPolicy.Scope.SAVED_PIN || TierPolicyHolder.current.tier == "ADMIN") &&
        prefs.getBoolean(scope.name, false)

    /** Called only by the local settings UI after Android credential confirmation. */
    fun setFromUser(scope: DeviceConsentPolicy.Scope, enabled: Boolean): Boolean {
        if (!available()) return false
        val saved = prefs.edit().putBoolean(scope.name, enabled).commit()
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
    fun armPin() = permit.arm(SystemClock.elapsedRealtime())
    fun pinArmed() = permit.active(SystemClock.elapsedRealtime())
    fun consumePin() = permit.consume(SystemClock.elapsedRealtime())
    fun revokeAll() {
        OmniDeviceAdminReceiver.recordDeviceAccess("CONSENT_REVOKE", true)
        if (available()) prefs.edit().clear().commit()
        permit.revoke()
        DevicePinVault(context).delete()
        AccessibilityStateManager.updateRootNode(null)
        com.omnidev.workspace.data.accessibility.SemanticUITool.clearSnapshot()
        AssistantRuntime.get(context).clearScreen()
    }
    companion object { private val permit = OneShotUnlockPermit() }
}
