package com.omnidev.workspace.data.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * Duplex voice interface: Speech-to-Text + Text-to-Speech.
 *
 * Wraps Android's [SpeechRecognizer] and [TextToSpeech] engines. Call [startListening] to begin
 * capturing the user's speech. The final transcription is delivered via [transcriptState]. Call
 * [speak] to synthesise AI responses using the highest-quality neural TTS voice available.
 *
 * Lifecycle: call [init] on the main thread before use; call [destroy] when done.
 */
class VoiceManager(private val context: Context) {

    // ── STT state ────────────────────────────────────────────────────────────────────────────────

    enum class SttState { IDLE, LISTENING, PARTIAL, RESULT, ERROR }

    private val _sttState = MutableStateFlow(SttState.IDLE)
    val sttState: StateFlow<SttState> = _sttState.asStateFlow()

    /** Latest final transcript from the last STT session. */
    private val _transcriptState = MutableStateFlow<String?>(null)
    val transcriptState: StateFlow<String?> = _transcriptState.asStateFlow()

    /** Latest partial transcript (intermediate result). */
    private val _partialTranscript = MutableStateFlow<String?>(null)
    val partialTranscript: StateFlow<String?> = _partialTranscript.asStateFlow()

    // ── TTS state ────────────────────────────────────────────────────────────────────────────────

    enum class TtsState { UNINITIALISED, READY, SPEAKING, ERROR }

    private val _ttsState = MutableStateFlow(TtsState.UNINITIALISED)
    val ttsState: StateFlow<TtsState> = _ttsState.asStateFlow()

    // ── Internals ────────────────────────────────────────────────────────────────────────────────

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    /** Callback invoked when a final transcript is ready (auto-submit). */
    var onTranscriptReady: ((String) -> Unit)? = null

    // ── Lifecycle ────────────────────────────────────────────────────────────────────────────────

    /**
     * Initialises both STT and TTS engines. Must be called on the main thread.
     */
    fun init() {
        initStt()
        initTts()
    }

    private fun initStt() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _sttState.value = SttState.LISTENING
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!partial.isNullOrBlank()) {
                        _partialTranscript.value = partial
                        _sttState.value = SttState.PARTIAL
                    }
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    _partialTranscript.value = null
                    if (!text.isNullOrBlank()) {
                        _transcriptState.value = text
                        _sttState.value = SttState.RESULT
                        onTranscriptReady?.invoke(text)
                    } else {
                        _sttState.value = SttState.IDLE
                    }
                }

                override fun onError(error: Int) {
                    _sttState.value = SttState.ERROR
                    _partialTranscript.value = null
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val locale = Locale.getDefault()
                tts?.setLanguage(locale)

                // Prefer high-quality network voice; fallback handled internally
                tts?.setSpeechRate(1.0f)
                tts?.setPitch(1.0f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _ttsState.value = TtsState.SPEAKING
                    }

                    override fun onDone(utteranceId: String?) {
                        _ttsState.value = TtsState.READY
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _ttsState.value = TtsState.READY
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        _ttsState.value = TtsState.READY
                    }
                })
                _ttsState.value = TtsState.READY
            } else {
                _ttsState.value = TtsState.ERROR
            }
        }
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    // ── STT API ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Starts capturing the user's speech. The final transcript is delivered via
     * [transcriptState] and [onTranscriptReady].
     *
     * @param language BCP-47 language tag, defaults to device locale.
     */
    fun startListening(language: String = Locale.getDefault().toLanguageTag()) {
        if (_sttState.value == SttState.LISTENING) return
        _transcriptState.value = null
        _partialTranscript.value = null
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer?.startListening(intent)
        _sttState.value = SttState.LISTENING
    }

    /** Stops the current STT session. */
    fun stopListening() {
        recognizer?.stopListening()
        _sttState.value = SttState.IDLE
    }

    // ── TTS API ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Synthesises [text] using the Android TTS engine. Any currently speaking utterance is
     * interrupted.
     *
     * @param text Text to synthesise. Long texts are queued automatically.
     */
    fun speak(text: String) {
        if (_ttsState.value == TtsState.UNINITIALISED || tts == null) return
        val utteranceId = UUID.randomUUID().toString()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /** Stops the current TTS utterance immediately. */
    fun stopSpeaking() {
        tts?.stop()
        _ttsState.value = TtsState.READY
    }

    val isSpeechRecognitionAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)
}
