package com.omnidev.workspace.data.voice

import android.content.Context
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

/**
 * Lightweight UI facade over [VoiceAssistantService].
 *
 * All STT and TTS operations are delegated to [VoiceAssistantService], which is the single
 * source of truth for the microphone and speaker. This prevents `ERROR_RECOGNIZER_BUSY`
 * crashes caused by concurrent [SpeechRecognizer] sessions when both this class and the
 * always-on wake-word service attempt to use the mic simultaneously.
 *
 * Lifecycle: [init] and [destroy] are no-ops — the service manages its own lifecycle.
 * Start / stop the service via [android.content.Context.startService] /
 * [android.content.Context.stopService] before calling voice APIs.
 */
class VoiceManager(private val context: Context) {

    // ── State enums (kept for API compatibility with ChatViewModel) ──────────────────────────────

    enum class SttState { IDLE, LISTENING, PARTIAL, RESULT, ERROR }
    enum class TtsState { UNINITIALISED, READY, SPEAKING, ERROR }

    // ── Reactive state — derived from VoiceAssistantService ──────────────────────────────────────

    /**
     * Current STT state, mapped from [VoiceAssistantService.voiceState].
     * Emits [SttState.IDLE] when the service is not running.
     */
    val sttState: Flow<SttState>
        get() = VoiceAssistantService.voiceState.map { vs ->
            when (vs) {
                is VoiceAssistantService.VoiceState.Listening     -> SttState.LISTENING
                is VoiceAssistantService.VoiceState.PartialResult -> SttState.PARTIAL
                is VoiceAssistantService.VoiceState.Processing    -> SttState.RESULT
                is VoiceAssistantService.VoiceState.Error         -> SttState.ERROR
                else                                              -> SttState.IDLE
            }
        }

    /**
     * Current TTS state, mapped from [VoiceAssistantService.voiceState].
     * Emits [TtsState.READY] when the service is not running.
     */
    val ttsState: Flow<TtsState>
        get() = VoiceAssistantService.voiceState.map { vs ->
            if (vs is VoiceAssistantService.VoiceState.Speaking) TtsState.SPEAKING else TtsState.READY
        }

    /**
     * Live partial transcript while the user is speaking, or `null` when silent.
     * Mapped from [VoiceAssistantService.voiceState].
     */
    val partialTranscript: Flow<String?>
        get() = VoiceAssistantService.voiceState.map { vs ->
            if (vs is VoiceAssistantService.VoiceState.PartialResult) vs.text else null
        }

    // ── Lifecycle (no-ops — service manages its own lifecycle) ───────────────────────────────────

    /** No-op. The service is the engine; call [android.content.Context.startService] instead. */
    fun init() {}

    /** No-op. The service is the engine; call [android.content.Context.stopService] instead. */
    fun destroy() {}

    // ── STT API (delegates to VoiceAssistantService) ─────────────────────────────────────────────

    /**
     * Starts capturing the user's speech via [VoiceAssistantService].
     * The wake-word loop is automatically paused while the query session is active.
     *
     * @param language BCP-47 language tag (unused; the service uses the device locale).
     */
    fun startListening(language: String = Locale.getDefault().toLanguageTag()) {
        VoiceAssistantService.startListening()
    }

    /** Stops the current STT session via [VoiceAssistantService]. */
    fun stopListening() {
        VoiceAssistantService.stopListening()
    }

    // ── TTS API (delegates to VoiceAssistantService) ─────────────────────────────────────────────

    /**
     * Synthesises [text] using [VoiceAssistantService]'s on-device TTS engine.
     * Any currently playing utterance is interrupted.
     */
    fun speak(text: String) {
        VoiceAssistantService.speak(text)
    }

    /** Stops the current TTS utterance immediately via [VoiceAssistantService]. */
    fun stopSpeaking() {
        VoiceAssistantService.stopSpeaking()
    }

    val isSpeechRecognitionAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)
}
