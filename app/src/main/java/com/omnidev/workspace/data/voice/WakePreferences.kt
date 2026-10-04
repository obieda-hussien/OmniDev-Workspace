package com.omnidev.workspace.data.voice

import android.content.Context
import android.app.KeyguardManager
import com.omnidev.workspace.core.policy.TierPolicyHolder
import com.omnidev.workspace.data.admin.DeviceConsentPolicy.Scope
import com.omnidev.workspace.data.admin.DeviceConsentStore

class WakePreferences(context: Context) {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    val enabled get() = prefs.getBoolean("enabled", false)
    val personalVoice get() = prefs.getBoolean("personal_voice", true)
    val lockScreen get() = prefs.getBoolean("lock_screen", false)
    val autoDictation get() = prefs.getBoolean("auto_dictation", false)
    val phrase get() = runCatching { WakePhrasePolicy.normalize(prefs.getString("phrase", WakePhrasePolicy.DEFAULT).orEmpty()) }.getOrDefault(WakePhrasePolicy.DEFAULT)
    /** Call off the main thread: changing the phrase invalidates its acoustic profile first. */
    fun setPhrase(value: String): Boolean {
        check(userCanConfigure()) { "Unlock the device to change the wake phrase." }
        val normalized = WakePhrasePolicy.normalize(value)
        if (normalized == phrase) return true
        LocalWakeService.stop(context); LocalVoiceSessionService.stop(context)
        WakeProfileStore(context).delete()
        return prefs.edit().putString("phrase", normalized).commit()
    }
    fun setEnabled(value: Boolean) = prefs.edit().putBoolean("enabled", value).commit()
    fun setPersonalVoice(value: Boolean) = prefs.edit().putBoolean("personal_voice", value).commit()
    fun setLockScreen(value: Boolean) = prefs.edit().putBoolean("lock_screen", value).commit()
    fun setAutoDictation(value: Boolean) = prefs.edit().putBoolean("auto_dictation", value).commit()
    fun allowedNow(): Boolean {
        val consent = DeviceConsentStore(context)
        return WakeActivationPolicy.canListen(enabled, TierPolicyHolder.current.allowAccessibility, consent.locked(),
            context.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true,
            lockScreen, consent.enabled(Scope.WAKE), consent.enabled(Scope.LOCK_OVERLAY))
    }
    fun userCanConfigure() = context.getSystemService(KeyguardManager::class.java)?.let { !it.isDeviceLocked && !it.isKeyguardLocked } == true
    companion object { const val NAME = "local-wake-consent" }
}
