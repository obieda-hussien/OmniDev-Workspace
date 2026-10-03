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
import java.util.Locale

/** One tap, one utterance. Stale callbacks cannot overwrite a restarted request. */
class AssistantSpeechInput(private val context: Context, private val controller: AssistantController) {
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0

    fun toggle(onPermissionRequired: () -> Unit, onSystemInput: () -> Unit = {}) {
        if (controller.state.value.listening) { stop(); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onPermissionRequired(); return
        }
        stop()
        val providers = SpeechRecognizerProvider.candidates(context)
        if (providers.isEmpty()) { controller.message("No speech service is installed. Trying system voice input."); onSystemInput(); return }
        val epoch = generation
        var activeIndex = -1
        fun start(index: Int) {
            if (epoch != generation) return
            activeIndex = index
            recognizer?.destroy()
            controller.message(null)
            controller.listening(true)
            runCatching {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context, providers[index]).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        // Android requires waiting for a final result/error before starting again.
                        override fun onEndOfSpeech() {}
                        override fun onError(error: Int) {
                            if (epoch != generation || activeIndex != index) return
                            controller.listening(false)
                            if (error in setOf(SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_RECOGNIZER_BUSY, 12, 13) && index + 1 < providers.size) {
                                activeIndex = -1
                                android.os.Handler(android.os.Looper.getMainLooper()).post { start(index + 1) }
                                return
                            }
                            activeIndex = -1
                            val reason = when (error) {
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "The speech service cannot access the microphone. Check its microphone permission too."
                                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a working connection."
                                SpeechRecognizer.ERROR_AUDIO -> "The speech service could not record audio. Another app may be using the microphone."
                                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was recognized. Tap the mic and speak again."
                                12, 13 -> "The speech service does not support ${Locale.getDefault().displayLanguage}."
                                else -> "Speech service failed (code $error). Trying system voice input."
                            }
                            controller.message(reason)
                            if (error !in setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) onSystemInput()
                        }
                        override fun onResults(results: Bundle?) {
                            if (epoch != generation || activeIndex != index) return
                            controller.listening(false)
                            activeIndex = -1
                            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(controller::input)
                        }
                        override fun onPartialResults(partialResults: Bundle?) {
                            if (epoch == generation && activeIndex == index) partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(controller::input)
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    startListening(intent())
                }
            }.onFailure {
                controller.listening(false)
                if (index + 1 < providers.size) start(index + 1)
                else { controller.message("Speech could not start. Trying system voice input."); onSystemInput() }
            }
        }
        start(0)
    }

    fun stop() {
        generation++
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null
        controller.listening(false)
    }

    companion object {
        fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Do not force offline: many installed recognizers have no offline Arabic pack.
        }
    }
}
