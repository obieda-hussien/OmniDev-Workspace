package com.omnidev.workspace.data.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.core.content.ContextCompat
import com.omnidev.workspace.data.voice.LocalVoiceSessionService
import com.omnidev.workspace.data.voice.OfflineVoiceModels
import com.omnidev.workspace.data.voice.WakePreferences
import java.util.Locale

/** The assistant mic uses direct local PCM, with no repeated Google recognizer tones. */
class AssistantSpeechInput(private val context: Context, private val controller: AssistantController) {
    fun toggle(onPermissionRequired: () -> Unit, onSystemInput: () -> Unit = {}) {
        if (LocalVoiceSessionService.running) { LocalVoiceSessionService.stop(context); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onPermissionRequired(); return
        }
        if (!WakePreferences(context).autoDictation || !OfflineVoiceModels(context).ready()) {
            controller.message("Enable local voice and install an offline model in Hi Omni settings. System voice input is available from its separate button.")
            return
        }
        if (!LocalVoiceSessionService.start(context)) controller.message("Local voice could not start. Check microphone and assistant setup.")
    }
    // The FGS owns its lifetime; closing a host is handled by AssistantRuntime.close.
    fun stop() { controller.listening(false) }
    companion object {
        /** Only for an explicitly selected system-input picker, never used for private credentials. */
        fun intent() = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
    }
}
