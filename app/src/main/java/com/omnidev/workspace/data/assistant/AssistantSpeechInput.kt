package com.omnidev.workspace.data.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/** A microphone tap starts one recognition request; never starts on assistant invocation. */
class AssistantSpeechInput(private val context: Context, private val controller: AssistantController) {
    private var recognizer: SpeechRecognizer? = null

    fun toggle(onPermissionRequired: () -> Unit) {
        if (controller.state.value.listening) { stop(); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onPermissionRequired(); return
        }
        val provider = SpeechRecognizerProvider.find(context)
        if (provider == null) {
            controller.message("Voice input is unavailable on this device. You can type your question."); return
        }
        runCatching {
            stop()
            recognizer = SpeechRecognizer.createSpeechRecognizer(context, provider).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { controller.listening(true) }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { controller.listening(false) }
                    override fun onError(error: Int) {
                        controller.listening(false)
                        controller.message("Could not hear your question. Tap the microphone to try again, or type it.")
                    }
                    override fun onResults(results: Bundle?) {
                        controller.listening(false)
                        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(controller::input)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                controller.listening(true)
                startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                })
            }
        }.onFailure { stop(); controller.message("Voice input could not start. Please try typing.") }
    }

    fun stop() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        controller.listening(false)
    }
}
