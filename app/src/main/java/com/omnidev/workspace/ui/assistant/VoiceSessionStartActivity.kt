package com.omnidev.workspace.ui.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.omnidev.workspace.data.admin.DeviceConsentPolicy
import com.omnidev.workspace.data.admin.DeviceConsentStore
import com.omnidev.workspace.data.voice.LocalVoiceSessionService

/** A resumed host establishes microphone eligibility before handing focus to SystemUI. */
class VoiceSessionStartActivity : ComponentActivity() {
    private var started = false
    private var id: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getStringExtra("request")
        val consent = DeviceConsentStore(this)
        if (id?.let(LocalVoiceSessionService::pendingRequest) != true ||
            !consent.enabled(DeviceConsentPolicy.Scope.UNLOCK) || !consent.enabled(DeviceConsentPolicy.Scope.VOICE_CREDENTIAL) ||
            !consent.enabled(DeviceConsentPolicy.Scope.LOCK_OVERLAY)) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        id?.let { hosts[it] = this }
        setContentView(TextView(this).apply { text = "Preparing private local unlock…"; setPadding(32, 48, 32, 48) })
    }
    override fun onResume() {
        super.onResume()
        if (isFinishing || started) return
        started = true
        if (id?.let { LocalVoiceSessionService.startPendingRequest(this, it) } != true) finish()
    }
    override fun onDestroy() {
        id?.let { if (hosts[it] === this) hosts.remove(it) }
        super.onDestroy()
    }
    companion object {
        private val hosts = mutableMapOf<String, VoiceSessionStartActivity>()
        fun launch(context: Context, id: String) {
            context.startActivity(Intent(context, VoiceSessionStartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("request", id))
        }
        fun serviceStarted(id: String?) { id?.let { hosts.remove(it)?.finish() } }
    }
}
