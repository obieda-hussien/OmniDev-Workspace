package com.omnidev.workspace.data.voice

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecord
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/** Direct AudioRecord → local Vosk. No SpeechRecognizer intents, beeps, transcripts or logs. */
class OfflineSpeech(private val context: Context, private val model: Model) {
    @Volatile private var recorder: AudioRecord? = null
    data class Utterance(val text: String, val confidence: Double) {
        override fun toString() = "Utterance(REDACTED)"
    }
    suspend fun listen(seconds: Int, allowed: () -> Boolean): Utterance? = withContext(Dispatchers.IO) {
        WakeAudio.mutex.withLock {
            currentCoroutineContext().ensureActive(); check(allowed())
            val audio = context.getSystemService(AudioManager::class.java)
            check(audio.mode == AudioManager.MODE_NORMAL && !audio.isMusicActive)
            val capture = WakeAudio.recorder(context); recorder = capture
            val chunk = ShortArray(320)
            try {
                Recognizer(model, 16000f).use { recognizer ->
                    recognizer.setWords(true)
                    recognizer.setEndpointerDelays(10f, .7f, seconds.toFloat())
                    capture.startRecording()
                    val deadline = SystemClock.elapsedRealtime() + seconds * 1000
                    while (SystemClock.elapsedRealtime() < deadline) {
                        currentCoroutineContext().ensureActive()
                        check(allowed() && audio.mode == AudioManager.MODE_NORMAL && !audio.isMusicActive)
                        WakeAudio.readFrame(capture, chunk)
                        val endpoint = recognizer.acceptWaveForm(chunk, chunk.size)
                        chunk.fill(0)
                        if (!allowed()) return@withLock null
                        if (endpoint) parse(recognizer.result)?.let { return@withLock it }
                    }
                    if (allowed()) parse(recognizer.finalResult) else null
                }
            } finally { recorder = null; runCatching { capture.stop() }; capture.release(); chunk.fill(0) }
        }
    }
    fun stop() { runCatching { recorder?.stop() } }
    private fun parse(value: String): Utterance? {
        val json = JSONObject(value)
        val text = json.optString("text").trim()
        if (text.isBlank() || text.length > 4096) return null
        val words = json.optJSONArray("result") ?: return null
        if (words.length() == 0) return null
        val confidence = (0 until words.length()).minOf { words.getJSONObject(it).optDouble("conf", 0.0) }
        return Utterance(text, confidence)
    }
}
