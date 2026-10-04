package com.omnidev.workspace.data.voice

import android.app.*
import android.app.Service
import android.content.*
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.omnidev.workspace.R
import com.omnidev.workspace.data.assistant.AssistantRuntime
import com.omnidev.workspace.data.assistant.OmniVoiceInteractionService
import com.omnidev.workspace.ui.assistant.AssistantActivity
import com.omnidev.workspace.ui.assistant.AssistantSettings
import com.omnidev.workspace.ui.assistant.VoiceWakeActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock

/** Explicitly user-started microphone FGS. Never started from boot, WorkManager or a tool. */
class LocalWakeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var capture: AudioRecord? = null
    private var failure: String? = null
    private val preferences by lazy { WakePreferences(this) }
    private val main = Handler(Looper.getMainLooper())
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (!preferences.enabled) stopSelf()
        else if (!preferences.allowedNow()) interruptCapture()
    }
    private val deviceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (!preferences.allowedNow()) interruptCapture()
    }
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!preferences.allowedNow()) interruptCapture()
        }
    }
    override fun onCreate() {
        super.onCreate(); active = this
        getSharedPreferences(WakePreferences.NAME, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener)
        getSharedPreferences("device-user-consent", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(deviceListener)
        ContextCompat.registerReceiver(this, lockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Hi Omni local listening", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { preferences.setEnabled(false); stopSelf(); return START_NOT_STICKY }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification("Starting local detector…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(ID, notification("Starting local detector…"))
            check(preferences.enabled && AssistantSettings.isSelected(this)) { "Select Omni as Android's default assistant, then start listening in settings." }
            if (job?.isActive != true) job = scope.launch { listen() }
        } catch (error: Exception) { fail("Cannot start listening. Check microphone permission and open Hi Omni settings.") }
        return START_NOT_STICKY
    }
    private suspend fun listen() {
        try {
            val model = WakeProfileStore(this).load()
            val audio = getSystemService(AudioManager::class.java)
            var lastWake = -10_000L
            while (currentCoroutineContext().isActive && preferences.enabled) {
                if (!AssistantSettings.isSelected(this)) { fail("Default assistant changed. Start listening again after selecting Omni."); return }
                if (!preferences.allowedNow() || AssistantRuntime.targetingScreen || LocalVoiceSessionService.running ||
                    audio.mode != AudioManager.MODE_NORMAL || audio.isMusicActive || SystemClock.elapsedRealtime() - lastWake < 8000) {
                    update(when {
                        !preferences.allowedNow() -> "Paused · wake/lock-screen permissions are required for this state"
                        AssistantRuntime.targetingScreen || LocalVoiceSessionService.running -> "Paused · assistant is active"
                        audio.mode != AudioManager.MODE_NORMAL -> "Paused · call or another voice session"
                        audio.isMusicActive -> "Paused · audio playback"
                        else -> "Paused · wake cooldown"
                    })
                    delay(250); continue
                }
                var matched = false
                WakeAudio.mutex.withLock {
                    val recorder = WakeAudio.recorder(this)
                    capture = recorder
                    val cpu = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OmniDev:LocalWake").apply { setReferenceCounted(false) }
                    var renewed = 0L
                    var segmenter: WakeSegmenter? = null
                    val chunk = ShortArray(320)
                    try {
                        cpu.acquire(60_000); renewed = SystemClock.elapsedRealtime()
                        recorder.startRecording(); update("Calibrating background noise…")
                        segmenter = WakeSegmenter(WakeAudio.calibrate(recorder, chunk) {
                            preferences.allowedNow() && !AssistantRuntime.targetingScreen && !LocalVoiceSessionService.running && audio.mode == AudioManager.MODE_NORMAL && !audio.isMusicActive
                        })
                        update("Listening locally · say ${preferences.phrase}")
                        while (currentCoroutineContext().isActive && preferences.allowedNow() && !AssistantRuntime.targetingScreen && !LocalVoiceSessionService.running &&
                            audio.mode == AudioManager.MODE_NORMAL && !audio.isMusicActive) {
                            val now = SystemClock.elapsedRealtime()
                            if (!cpu.isHeld || now - renewed >= 30_000) { cpu.acquire(60_000); renewed = now }
                            WakeAudio.readFrame(recorder, chunk)
                            if (!preferences.allowedNow() || AssistantRuntime.targetingScreen || LocalVoiceSessionService.running) break
                            val pcm = segmenter.accept(chunk) ?: continue
                            val match = try { runCatching { model.match(WakeFeatures.extract(pcm), preferences.personalVoice) }.getOrNull() }
                                finally { pcm.fill(0) }
                            if (match?.accepted == true) { matched = true; break }
                        }
                    } catch (error: IllegalStateException) {
                        if (preferences.allowedNow() && !AssistantRuntime.targetingScreen && !LocalVoiceSessionService.running && audio.mode == AudioManager.MODE_NORMAL && !audio.isMusicActive) throw error
                    } finally {
                        capture = null; runCatching { recorder.stop() }; recorder.release()
                        if (cpu.isHeld) cpu.release()
                        chunk.fill(0); segmenter?.reset()
                    }
                }
                if (matched) {
                    lastWake = SystemClock.elapsedRealtime()
                    // Microphone has been released before Android shows the assistant.
                    main.post {
                        if (active !== this || !preferences.allowedNow() || AssistantRuntime.targetingScreen || LocalVoiceSessionService.running) return@post
                        update("Wake phrase detected · opening Omni")
                        if (!OmniVoiceInteractionService.wake(this)) {
                            update("Wake phrase detected · tap to open assistant")
                            getSystemService(NotificationManager::class.java).notify(INVOCATION_ID,
                                NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
                                    .setContentTitle("Wake phrase detected").setContentText("Tap to open Omni. Android's native voice service is unavailable.")
                                    .setContentIntent(PendingIntent.getActivity(this, 2, Intent(this, AssistantActivity::class.java), immutable()))
                                    .setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { fail("Local listening stopped. Check the microphone or re-enroll the voice profile in settings.") }
    }
    private fun interruptCapture() { runCatching { capture?.stop() } }
    private fun fail(message: String) { main.post {
        if (active !== this) return@post
        failure = message; state.value = message; preferences.setEnabled(false); stopSelf()
    } }
    private fun update(message: String) {
        if (active !== this || !preferences.enabled) return
        if (state.value == message) return
        state.value = message
        getSystemService(NotificationManager::class.java).notify(ID, notification(message))
    }
    private fun notification(message: String): Notification {
        val settings = PendingIntent.getActivity(this, 0, Intent(this, VoiceWakeActivity::class.java), immutable())
        val stop = PendingIntent.getService(this, 1, Intent(this, LocalWakeService::class.java).setAction(STOP), immutable())
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Hi Omni · local voice wake").setContentText(message).setContentIntent(settings)
            .setOngoing(true).setSilent(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "Stop listening", stop).build()
    }
    override fun onDestroy() {
        preferences.setEnabled(false)
        getSharedPreferences(WakePreferences.NAME, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
        getSharedPreferences("device-user-consent", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(deviceListener)
        unregisterReceiver(lockReceiver)
        interruptCapture(); scope.cancel(); main.removeCallbacksAndMessages(null)
        if (active === this) active = null
        getSystemService(NotificationManager::class.java).cancel(INVOCATION_ID)
        state.value = failure ?: "Stopped"
        super.onDestroy()
    }
    companion object {
        val running get() = active != null
        private const val CHANNEL = "local_hi_omni"
        private const val ID = 9186
        private const val INVOCATION_ID = 9187
        private const val STOP = "stop_local_wake"
        private val state = MutableStateFlow("Stopped")
        val status = state.asStateFlow()
        @Volatile private var active: LocalWakeService? = null
        private fun immutable() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        fun start(context: Context): Boolean {
            fun unavailable(reason: String): Boolean { state.value = reason; return false }
            if (!WakePreferences(context).userCanConfigure()) return unavailable("Unlock the phone, then start listening from Voice activation settings.")
            if (!AssistantSettings.isSelected(context)) return unavailable("Choose Omni as Android's default digital assistant first.")
            if (!WakeProfileStore(context).exists()) return unavailable("No trained wake profile. Complete the voice recordings in Step 2 first.")
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                return unavailable("Microphone permission is missing. Allow it in Voice activation settings.")
            if (context.getSystemService(AudioManager::class.java)?.isMicrophoneMute == true)
                return unavailable("The microphone is muted. Enable microphone access in Android, then try again.")
            if (!WakePreferences(context).setEnabled(true)) return unavailable("Could not save listening activation. Try again.")
            return runCatching { ContextCompat.startForegroundService(context, Intent(context, LocalWakeService::class.java)); true }
                .getOrElse { WakePreferences(context).setEnabled(false); state.value = "Android could not start the microphone service. Open settings and try again."; false }
        }
        fun stop(context: Context) { WakePreferences(context).setEnabled(false); active?.interruptCapture(); context.stopService(Intent(context, LocalWakeService::class.java)) }
        fun pauseCapture() { active?.interruptCapture() }
    }
}
