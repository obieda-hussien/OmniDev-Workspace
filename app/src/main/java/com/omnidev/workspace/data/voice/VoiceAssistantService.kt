package com.omnidev.workspace.data.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.omnidev.workspace.MainActivity
import com.omnidev.workspace.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Always-on background service that continuously listens for a wake phrase using Android's
 * [SpeechRecognizer]. Runs as a Foreground Service with [ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE]
 * so Android allows microphone access from the background (Android 10+ requirement).
 *
 * **Wake phrases:** Any utterance containing "hey omni" or "wake up" triggers the voice session.
 *
 * **Flow:**
 * 1. Service starts and calls [startWakeWordLoop].
 * 2. Continuous [SpeechRecognizer] session polls for speech.
 * 3. On wake phrase detection: vibrate, stop wake loop, emit [WakeState.TRIGGERED].
 * 4. Caller (e.g. ChatViewModel) starts a [VoiceManager] STT session for the real query.
 * 5. After the session completes, call [resumeWakeWordLoop] to restart listening.
 *
 * Start: `startService(Intent(context, VoiceAssistantService::class.java))`
 * Stop:  `stopService(Intent(context, VoiceAssistantService::class.java))`
 */
class VoiceAssistantService : Service() {

    // ── State ─────────────────────────────────────────────────────────────────────────────────────

    enum class WakeState { IDLE, LISTENING, TRIGGERED }

    private val _wakeState = MutableStateFlow(WakeState.IDLE)

    companion object {
        val wakeState: StateFlow<WakeState>
            get() = instance?._wakeState?.asStateFlow() ?: MutableStateFlow(WakeState.IDLE).asStateFlow()

        private var instance: VoiceAssistantService? = null

        /** Resume the wake-word loop after a voice session completes. */
        fun resumeWakeWordLoop() {
            instance?.startWakeWordLoop()
        }

        val isRunning: Boolean
            get() = instance != null

        private val WAKE_PHRASES = listOf("hey omni", "wake up", "هيي أومني", "استيقظ")

        private const val NOTIFICATION_ID = 8001
        private const val CHANNEL_ID = "omni_voice_channel"
        private const val CHANNEL_NAME = "Omni Voice Daemon"

        /** Delay between SpeechRecognizer sessions (ms) — avoids tight loops on error. */
        private const val RESTART_DELAY_MS = 500L
    }

    private var wakeRecognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isDestroyed = false

    // ── Service lifecycle ────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            startWakeWordLoop()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isDestroyed = true
        instance = null
        stopWakeWordLoop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Foreground notification ──────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Omni wake-word listener"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun startForeground() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Omni Voice Daemon")
            .setContentText("Listening for \"Hey Omni\"…")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ── Wake word loop ───────────────────────────────────────────────────────────────────────────

    fun startWakeWordLoop() {
        if (isDestroyed) return
        _wakeState.value = WakeState.LISTENING
        handler.post { launchSession() }
    }

    private fun stopWakeWordLoop() {
        wakeRecognizer?.destroy()
        wakeRecognizer = null
        _wakeState.value = WakeState.IDLE
    }

    private fun launchSession() {
        if (isDestroyed || _wakeState.value == WakeState.TRIGGERED) return
        wakeRecognizer?.destroy()

        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        wakeRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val heard = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.joinToString(" ")
                        ?.lowercase(Locale.getDefault()) ?: ""
                    if (WAKE_PHRASES.any { heard.contains(it) }) {
                        triggerWake()
                    } else {
                        scheduleRestart()
                    }
                }

                override fun onError(error: Int) {
                    scheduleRestart()
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Shorter silence timeout keeps the loop responsive
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }
        wakeRecognizer?.startListening(intent)
    }

    private fun scheduleRestart() {
        if (isDestroyed || _wakeState.value == WakeState.TRIGGERED) return
        handler.postDelayed({ launchSession() }, RESTART_DELAY_MS)
    }

    private fun triggerWake() {
        _wakeState.value = WakeState.TRIGGERED
        wakeRecognizer?.destroy()
        wakeRecognizer = null
        vibrate()
        // Notify the running instance observer (ChatViewModel will call resumeWakeWordLoop after query)
    }

    // ── Haptic feedback ──────────────────────────────────────────────────────────────────────────

    private fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(120)
                }
            }
        } catch (_: Exception) {}
    }
}
