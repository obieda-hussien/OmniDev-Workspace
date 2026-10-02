package com.omnidev.workspace.data.assistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/** Android requires a recognizer in assistant metadata. Delegate to an installed provider. */
class OmniRecognitionService : RecognitionService() {
    private var recognizer: SpeechRecognizer? = null
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        recognizer?.destroy()
        val provider = SpeechRecognizerProvider.find(this)
        if (provider == null) { listener.error(SpeechRecognizer.ERROR_CLIENT); return }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this, provider).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { runCatching { listener.readyForSpeech(params ?: Bundle()) } }
                override fun onBeginningOfSpeech() { runCatching { listener.beginningOfSpeech() } }
                override fun onRmsChanged(rmsdB: Float) { runCatching { listener.rmsChanged(rmsdB) } }
                override fun onBufferReceived(buffer: ByteArray?) { buffer?.let { runCatching { listener.bufferReceived(it) } } }
                override fun onEndOfSpeech() { runCatching { listener.endOfSpeech() } }
                override fun onError(error: Int) { runCatching { listener.error(error) } }
                override fun onResults(results: Bundle?) { runCatching { listener.results(results ?: Bundle()) } }
                override fun onPartialResults(partialResults: Bundle?) { runCatching { listener.partialResults(partialResults ?: Bundle()) } }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(recognizerIntent)
        }
    }
    override fun onStopListening(listener: Callback) { recognizer?.stopListening() }
    override fun onCancel(listener: Callback) { recognizer?.cancel(); recognizer?.destroy(); recognizer = null }
    override fun onDestroy() { recognizer?.destroy(); recognizer = null; super.onDestroy() }
}
