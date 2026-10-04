package com.omnidev.workspace.data.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt

/** One local recorder at a time; the foreground enrollment Activity owns the microphone. */
object WakeAudio {
    internal val mutex = Mutex()
    fun recorder(context: Context): AudioRecord {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val minimum = AudioRecord.getMinBufferSize(WakeFeatures.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "16 kHz mono microphone is unavailable." }
        return AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeFeatures.RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 6400)).also {
            if (it.state != AudioRecord.STATE_INITIALIZED) { it.release(); error("Microphone could not initialize.") }
        }
    }
    suspend fun sample(context: Context, onReady: () -> Unit = {}): WakeFeatures.Sample = withContext(Dispatchers.IO) {
        withTimeout(12_000) { mutex.withLock {
            val recorder = recorder(context)
            var segmenter: WakeSegmenter? = null
            val chunk = ShortArray(320)
            try {
                recorder.startRecording()
                segmenter = WakeSegmenter(calibrate(recorder, chunk) { WakePreferences(context).userCanConfigure() })
                withContext(Dispatchers.Main) { onReady() }
                while (currentCoroutineContext().isActive) {
                    ensureActive()
                    check(WakePreferences(context).userCanConfigure()) { "Unlock the device to enroll your voice." }
                    readFrame(recorder, chunk)
                    val pcm = segmenter.accept(chunk) ?: continue
                    try { return@withLock WakeFeatures.extract(pcm) } finally { pcm.fill(0) }
                }
                error("Recording cancelled.")
            } finally {
                runCatching { recorder.stop() }; recorder.release(); chunk.fill(0); segmenter?.reset()
            }
        } }
    }
    /** 400ms ambient calibration before showing the speak prompt. No calibration audio is kept. */
    internal suspend fun calibrate(recorder: AudioRecord, chunk: ShortArray, allowed: () -> Boolean): Double {
        val levels = DoubleArray(20) {
            currentCoroutineContext().ensureActive()
            check(allowed()) { "Recording permission or device consent changed." }
            readFrame(recorder, chunk)
            sqrt(chunk.sumOf { it.toDouble() * it } / chunk.size)
        }
        chunk.fill(0); levels.sort()
        return levels[levels.size / 2].also { check(it < 5000) { "Background audio is too loud. Try a quieter room." } }
    }
    internal fun readFrame(recorder: AudioRecord, chunk: ShortArray) {
        var offset = 0
        while (offset < chunk.size) {
            val count = recorder.read(chunk, offset, chunk.size - offset, AudioRecord.READ_BLOCKING)
            check(count > 0) { "Microphone stopped or became unavailable." }
            offset += count
        }
    }
}
