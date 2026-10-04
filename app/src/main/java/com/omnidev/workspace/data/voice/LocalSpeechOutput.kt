package com.omnidev.workspace.data.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.media.AudioAttributes
import kotlinx.coroutines.*
import java.util.UUID

/** Only installed offline voices. Never speak a credential or silently choose a network voice. */
class LocalSpeechOutput(private val context: Context) : AutoCloseable {
    private val ready = CompletableDeferred<Int>()
    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var completion: CompletableDeferred<Boolean>? = null
    var language = "en"
        private set
    suspend fun initialize() = withContext(Dispatchers.Main.immediate) {
        engine = TextToSpeech(context) { ready.complete(it) }
        check(withTimeoutOrNull(10_000) { ready.await() } == TextToSpeech.SUCCESS) { "Speech output unavailable." }
        val tts = engine ?: error("Speech output unavailable.")
        val language = OfflineVoiceModels(context).language()
        val voice = tts.voices?.filter { !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }?.let { voices ->
            voices.firstOrNull { it.locale.language == language } ?: voices.firstOrNull { it.locale.language == "en" }
        } ?: error("Install an offline voice in Android text-to-speech settings.")
        check(tts.setVoice(voice) == TextToSpeech.SUCCESS)
        this@LocalSpeechOutput.language = voice.locale.language
        tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
    }
    suspend fun say(text: String): Boolean = withContext(Dispatchers.Main.immediate) {
        val tts = engine ?: return@withContext false
        val id = UUID.randomUUID().toString(); val done = CompletableDeferred<Boolean>(); completion = done
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) done.complete(true) }
            @Deprecated("Android callback") override fun onError(utteranceId: String?) { if (utteranceId == id) done.complete(false) }
        })
        if (tts.speak(text.take(1500), TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) done.complete(false)
        try { withTimeoutOrNull(45_000) { done.await() } == true }
        finally { if (completion === done) completion = null; tts.stop() }
    }
    fun stop() { engine?.stop(); completion?.complete(false) }
    override fun close() { stop(); engine?.shutdown(); engine = null }
}
