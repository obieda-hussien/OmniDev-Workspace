package com.omnidev.workspace.data.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.omnidev.workspace.MainActivity
import com.omnidev.workspace.R
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.ui.overlay.OmniBubbleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

/**
 * Full on-device voice controller — zero API keys, zero cloud dependencies.
 * Combines:
 *  - **Wake-word loop** (always-on, continuous [SpeechRecognizer] listening for "Hey Omni")
 *  - **Main STT** ([startListening] / [stopListening] for user queries)
 *  - **TTS output** ([speak] / [stopSpeaking] for agent responses)
 *
 * All three engines run on-device using Android's built-in APIs:
 *  - STT: `android.speech.SpeechRecognizer` (Google on-device model, pre-installed)
 *  - TTS: `android.speech.tts.TextToSpeech` (Android TTS engine, pre-installed)
 *
 * Runs as a Foreground Service with [ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE].
 *
 * **State model:** [voiceState] is the authoritative state emitted as a [StateFlow].
 * [transcriptFlow] emits finalized transcript strings; [partialFlow] emits live partial results.
 *
 * Start: `startService(Intent(context, VoiceAssistantService::class.java))`
 * Stop:  `stopService(Intent(context, VoiceAssistantService::class.java))`
 */
class VoiceAssistantService : Service() {

    // ── VoiceState ────────────────────────────────────────────────────────────────────────────────

    sealed class VoiceState {
        /** Mic and TTS both idle. */
        data object Idle : VoiceState()
        /** Mic is open, waiting for speech. */
        data object Listening : VoiceState()
        /** Partial STT result available while user is still speaking. */
        data class PartialResult(val text: String) : VoiceState()
        /** STT finished; transcript delivered to pipeline. */
        data object Processing : VoiceState()
        /** TTS is currently synthesizing and playing the agent response. */
        data class Speaking(val text: String) : VoiceState()
        /** An error occurred; [message] describes what went wrong. */
        data class Error(val message: String) : VoiceState()
    }

    // Legacy wake state (kept for callers that still observe it)
    enum class WakeState { IDLE, LISTENING, TRIGGERED }

    // ── Instance flows ────────────────────────────────────────────────────────────────────────────

    private val _voiceState = MutableStateFlow<VoiceState>(VoiceState.Idle)
    private val _transcriptFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val _partialFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val _wakeState = MutableStateFlow(WakeState.IDLE)

    /** Coroutine scope for async work inside this service (greeting TTS, name lookup). */
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Single SettingsRepository instance for this service lifecycle.
     * Initialised lazily on first use (requires applicationContext).
     */
    private val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(applicationContext)
    }

    // ── Companion (static access for UI / ViewModel) ──────────────────────────────────────────────

    companion object {

        private var instance: VoiceAssistantService? = null

        val isRunning: Boolean get() = instance != null

        /** Current voice controller state. */
        val voiceState: StateFlow<VoiceState>
            get() = instance?._voiceState?.asStateFlow()
                ?: MutableStateFlow(VoiceState.Idle).asStateFlow()

        /** Emits the final STT transcript each time the user finishes speaking. */
        val transcriptFlow: SharedFlow<String>
            get() = instance?._transcriptFlow?.asSharedFlow()
                ?: MutableSharedFlow()

        /** Emits live partial STT results while the user is speaking. */
        val partialFlow: SharedFlow<String>
            get() = instance?._partialFlow?.asSharedFlow()
                ?: MutableSharedFlow()

        /** Legacy wake state. */
        val wakeState: StateFlow<WakeState>
            get() = instance?._wakeState?.asStateFlow()
                ?: MutableStateFlow(WakeState.IDLE).asStateFlow()

        // ── Static action APIs ──────────────────────────────────────────────────────────────────

        /** Start capturing main-query speech. */
        fun startListening() { instance?.startMainListening() }

        /** Stop and discard the current STT session. */
        fun stopListening() { instance?.stopMainListening() }

        /** Synthesise [text] via on-device TTS. */
        fun speak(text: String) { instance?.speakText(text) }

        /** Interrupt any ongoing TTS utterance. */
        fun stopSpeaking() { instance?.stopTts() }

        /** Resume the wake-word loop after a voice session completes. */
        fun resumeWakeWordLoop() { instance?.startWakeWordLoop() }

        /**
         * Requests the user to whitelist this app from battery optimization.
         *
         * Android's "doze" and app standby modes kill background mic listeners.
         * This prompts the system dialog that lets the user tap "Allow" once,
         * making the wake-word daemon behave like an OS-level service.
         *
         * Requires [android.Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS]
         * declared in AndroidManifest.xml.
         */
        fun requestBatteryOptimizationBypass(context: Context) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.w("VoiceAssistantService", "Could not show battery optimisation dialog: ${e.message}")
                    // Fallback: open the generic battery optimization settings screen
                    val fallback = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(fallback)
                    } catch (e2: Exception) {
                        Log.e("VoiceAssistantService", "Could not open battery settings: ${e2.message}")
                    }
                }
            }
        }

        // ── Constants ───────────────────────────────────────────────────────────────────────────

        private val WAKE_PHRASES = listOf(
            "hey omni", "wake up", "هيي أومني", "استيقظ",
            // Arabic wake command added for natural voice activation
            "اصحي يا اومني", "صحي يا اومني", "اصحي اومني"
        )

        private const val NOTIFICATION_ID = 8001
        private const val CHANNEL_ID = "omni_voice_channel"
        private const val CHANNEL_NAME = "Omni Voice"

        private const val RESTART_DELAY_MS = 500L
    }

    // ── Recognizers & TTS ─────────────────────────────────────────────────────────────────────────

    /** Dedicated recognizer for the always-on wake-word loop. */
    private var wakeRecognizer: SpeechRecognizer? = null

    /** Dedicated recognizer for main user queries (started on demand). */
    private var queryRecognizer: SpeechRecognizer? = null

    /** On-device TTS for agent responses. */
    private var tts: TextToSpeech? = null

    /**
     * Keeps the CPU running even when the screen is off, so the wake-word loop
     * keeps listening (same mechanism used by music-playback and navigation apps).
     */
    private var wakeLock: PowerManager.WakeLock? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isDestroyed = false

    // ── Service lifecycle ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        acquireWakeLock()
        createNotificationChannel()
        startForeground()
        initTts()
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
        serviceScope.cancel()
        stopWakeWordLoop()
        stopMainListening()
        tts?.stop()
        tts?.shutdown()
        tts = null
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── TTS initialisation ────────────────────────────────────────────────────────────────────────

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.getDefault())
                tts?.setSpeechRate(1.0f)
                tts?.setPitch(1.0f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        // state already set in speakText()
                    }
                    override fun onDone(utteranceId: String?) {
                        if (_voiceState.value is VoiceState.Speaking) {
                            _voiceState.value = VoiceState.Idle
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (_voiceState.value is VoiceState.Speaking) {
                            _voiceState.value = VoiceState.Idle
                        }
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (_voiceState.value is VoiceState.Speaking) {
                            _voiceState.value = VoiceState.Idle
                        }
                    }
                })
            }
        }
    }

    // ── Main-query STT ────────────────────────────────────────────────────────────────────────────

    private fun startMainListening() {
        if (isDestroyed) return
        if (_voiceState.value is VoiceState.Listening) return

        // Pause wake-word loop while main query is active
        stopWakeWordLoop()

        queryRecognizer?.destroy()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            _voiceState.value = VoiceState.Error("Speech recognition not available on this device")
            return
        }

        queryRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _voiceState.value = VoiceState.Listening
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!partial.isNullOrBlank()) {
                        _voiceState.value = VoiceState.PartialResult(partial)
                        _partialFlow.tryEmit(partial)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    queryRecognizer?.destroy()
                    queryRecognizer = null
                    if (!text.isNullOrBlank()) {
                        _voiceState.value = VoiceState.Processing
                        _transcriptFlow.tryEmit(text)
                    } else {
                        _voiceState.value = VoiceState.Idle
                        startWakeWordLoop()
                    }
                }

                override fun onError(error: Int) {
                    queryRecognizer?.destroy()
                    queryRecognizer = null
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timed out"
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        else -> "Speech error ($error)"
                    }
                    _voiceState.value = VoiceState.Error(msg)
                    startWakeWordLoop()
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        queryRecognizer?.startListening(intent)
    }

    private fun stopMainListening() {
        queryRecognizer?.stopListening()
        queryRecognizer?.destroy()
        queryRecognizer = null
        if (_voiceState.value is VoiceState.Listening || _voiceState.value is VoiceState.PartialResult) {
            _voiceState.value = VoiceState.Idle
        }
    }

    // ── TTS output ────────────────────────────────────────────────────────────────────────────────

    private fun speakText(text: String) {
        if (isDestroyed || tts == null) return
        tts?.stop()
        _voiceState.value = VoiceState.Speaking(text)
        val utteranceId = UUID.randomUUID().toString()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun stopTts() {
        tts?.stop()
        if (_voiceState.value is VoiceState.Speaking) {
            _voiceState.value = VoiceState.Idle
        }
    }

    // ── Foreground notification ───────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Omni voice controller"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun startForeground() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Omni Voice")
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

    // ── Wake-word loop ────────────────────────────────────────────────────────────────────────────

    fun startWakeWordLoop() {
        if (isDestroyed) return
        _wakeState.value = WakeState.LISTENING
        handler.post { launchWakeSession() }
    }

    private fun stopWakeWordLoop() {
        handler.removeCallbacksAndMessages(null)
        wakeRecognizer?.destroy()
        wakeRecognizer = null
        _wakeState.value = WakeState.IDLE
    }

    private fun launchWakeSession() {
        if (isDestroyed || _wakeState.value == WakeState.TRIGGERED) return
        wakeRecognizer?.destroy()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        wakeRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val heard = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.joinToString(" ")?.lowercase(Locale.getDefault()) ?: ""
                    if (WAKE_PHRASES.any { heard.contains(it) }) triggerWake()
                    else scheduleWakeRestart()
                }
                override fun onError(error: Int) { scheduleWakeRestart() }
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
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }
        wakeRecognizer?.startListening(intent)
    }

    private fun scheduleWakeRestart() {
        if (isDestroyed || _wakeState.value == WakeState.TRIGGERED) return
        handler.postDelayed({ launchWakeSession() }, RESTART_DELAY_MS)
    }

    private fun triggerWake() {
        _wakeState.value = WakeState.TRIGGERED
        wakeRecognizer?.destroy()
        wakeRecognizer = null
        vibrate()
        // Greet the user by name and open the overlay
        serviceScope.launch {
            val userName = settingsRepository
                .observeUserName()
                .first()
            val greeting = if (userName.isNullOrBlank())
                "أنا هنا، قولي عايز إيه؟"
            else
                "أنا هنا يا $userName، قولي عايز إيه؟"
            withContext(Dispatchers.Main) {
                OmniBubbleService.startWithGreeting(
                    context = this@VoiceAssistantService,
                    userName = userName ?: "",
                    greeting = greeting
                )
                speakText(greeting)
            }
        }
    }

    // ── WakeLock ──────────────────────────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OmniDev:VoiceAssistantWakeLock"
        ).apply {
            setReferenceCounted(false)
            // 12-hour safety timeout — prevents indefinite hold if onDestroy is never called.
            // In normal operation the service releases the lock explicitly in onDestroy().
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.w("VoiceAssistantService", "Failed to release wake lock: ${e.message}")
        }
        wakeLock = null
    }

    // ── Haptic feedback ───────────────────────────────────────────────────────────────────────────

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
