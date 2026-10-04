package com.omnidev.workspace.ui.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.Window
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.omnidev.workspace.data.admin.DeviceConsentStore
import com.omnidev.workspace.data.voice.LocalVoiceSessionService

/** Ordinary assistant conversations can be screenshotted; private lock/credential hosts cannot. */
class AssistantWindowPrivacy(context: Context, private val window: Window) : AutoCloseable {
    private val app = context.applicationContext
    private var closed = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) protect(true) else refresh()
        }
    }
    init {
        ContextCompat.registerReceiver(app, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }
    fun refresh() = protect(DeviceConsentStore(app).locked() || LocalVoiceSessionService.privateInput)
    private fun protect(value: Boolean) {
        if (value) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    override fun close() { if (!closed) { closed = true; app.unregisterReceiver(receiver) } }
}
