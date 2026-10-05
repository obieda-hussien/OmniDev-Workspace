package com.omnidev.workspace.data.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Only installed offline voices. Never speak a credential or silently choose a network voice. */
class LocalSpeechOutput(private val context: Context) : AutoCloseable {
    private val ready = CompletableDeferred<Int>()
    private val lifecycle = SpeechOutputLifecycle<TextToSpeech>(
        stopEngine = { it.stop() },
        shutdownEngine = { it.shutdown() },
        onCleanupFailure = { Log.w("LocalSpeechOutput", "Speech connection already disconnected during cleanup", it) }
    )
    private val completion = AtomicReference<CompletableDeferred<Boolean>?>(null)
    var language = "en"
        private set

    suspend fun initialize() = withContext(Dispatchers.Main.immediate) {
        check(lifecycle.beginInitialization()) { "Speech output already initialized or closed." }
        try {
            val candidate = TextToSpeech(context.applicationContext) { ready.complete(it) }
            check(lifecycle.install(candidate)) { "Speech output closed during initialization." }
            check(withTimeoutOrNull(10_000) { ready.await() } == TextToSpeech.SUCCESS) { "Speech output unavailable." }
            val tts = lifecycle.current() ?: error("Speech output unavailable.")
            val requestedLanguage = OfflineVoiceModels(context).language()
            check(lifecycle.withCurrent(tts) {
                val voices = tts.voices?.filter {
                    !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
                }
                val voice = voices?.firstOrNull { it.locale.language == requestedLanguage }
                    ?: voices?.firstOrNull { it.locale.language == "en" }
                    ?: error("Install an offline voice in Android text-to-speech settings.")
                check(tts.setVoice(voice) == TextToSpeech.SUCCESS)
                language = voice.locale.language
                tts.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                true
            } == true) { "Speech output closed during initialization." }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    suspend fun say(text: String): Boolean = withContext(Dispatchers.Main.immediate) {
        val tts = lifecycle.current() ?: return@withContext false
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        completion.getAndSet(done)?.complete(false)
        try {
            val queued = lifecycle.withCurrent(tts) {
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { if (utteranceId == id) done.complete(true) }
                    @Deprecated("Android callback")
                    override fun onError(utteranceId: String?) { if (utteranceId == id) done.complete(false) }
                })
                tts.speak(text.take(1500), TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS
            } == true
            if (!queued) done.complete(false)
            withTimeoutOrNull(45_000) { done.await() } == true
        } finally {
            // A cancelled old utterance must not stop the newer QUEUE_FLUSH utterance.
            if (completion.compareAndSet(done, null)) lifecycle.stopIfCurrent(tts)
        }
    }

    fun stop() {
        completion.getAndSet(null)?.complete(false)
        lifecycle.stop()
    }

    override fun close() {
        completion.getAndSet(null)?.complete(false)
        ready.complete(TextToSpeech.ERROR)
        lifecycle.close()
    }
}
