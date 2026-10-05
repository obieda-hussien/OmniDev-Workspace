package com.omnidev.workspace.data.voice

import android.app.Service
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.omnidev.workspace.R
import com.omnidev.workspace.data.admin.*
import com.omnidev.workspace.data.assistant.*
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.ui.assistant.AssistantSettings
import com.omnidev.workspace.ui.assistant.DeviceUnlockActivity
import com.omnidev.workspace.ui.assistant.VoiceWakeActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Bounded conversational session; credentials never cross the local command/chat boundary. */
class LocalVoiceSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: Job? = null
    @Volatile private var input: OfflineSpeech? = null
    @Volatile private var output: LocalSpeechOutput? = null
    @Volatile private var requestId: String? = null
    @Volatile private var privatePhase = false
    @Volatile private var handingOff = false
    private var credential: SpokenCredential? = null
    private var attempted = false
    private var phase = "Starting local voice…"
    private val consentListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (!allowed()) { input?.stop(); output?.stop(); stopSelf() }
    }
    private val privacy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            output?.stop()
            if (!allowed()) { input?.stop(); stopSelf() }
        }
    }
    override fun onCreate() {
        super.onCreate(); active = this
        LocalWakeService.pauseCapture()
        getSharedPreferences("device-user-consent", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(consentListener)
        getSharedPreferences(WakePreferences.NAME, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(consentListener)
        ContextCompat.registerReceiver(this, privacy, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Omni local voice session", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        try {
            val id = intent?.getStringExtra(REQUEST)
            if (id != null && synchronized(pending) { pending.containsKey(id) }) {
                if (requestId == null) { requestId = id; input?.stop() }
                else { synchronized(pending) { pending[id]?.complete("USER_ACTION_REQUIRED: another private unlock request is active. Complete it first.") }; return START_NOT_STICKY }
            }
            val notification = notification()
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(ID, notification)
            check(WakePreferences(this).autoDictation && OfflineVoiceModels(this).ready() && AssistantSettings.isSelected(this) && allowed())
            AssistantRuntime.get(this).listening(true)
            if (session?.isActive != true) session = scope.launch { runSession() }
        } catch (error: Exception) { update("Local voice unavailable. Open Hi Omni settings and check the model, microphone and permissions."); finishRequest(false); stopSelf() }
        return START_NOT_STICKY
    }
    private fun allowed(): Boolean {
        val consent = DeviceConsentStore(this)
        return AssistantSettings.isSelected(this) && WakePreferences(this).autoDictation && VoiceSessionPolicy.canCapture(consent.locked(), consent.enabled(DeviceConsentPolicy.Scope.LOCK_OVERLAY),
            consent.enabled(DeviceConsentPolicy.Scope.VOICE_CREDENTIAL), consent.enabled(DeviceConsentPolicy.Scope.UNLOCK), privateInput)
    }
    private fun update(value: String) {
        phase = value; state.value = value
        if (active === this) getSystemService(NotificationManager::class.java)?.notify(ID, notification())
    }
    private fun notification() = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("Omni · local voice session").setContentText(phase).setSilent(true).setOngoing(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, VoiceWakeActivity::class.java), IMMUTABLE))
        .addAction(0, "Stop", PendingIntent.getService(this, 1, Intent(this, LocalVoiceSessionService::class.java).setAction(STOP), IMMUTABLE)).build()
    private suspend fun say(english: String, arabic: String = english, requireUnlocked: Boolean = false) {
        check(allowed())
        check(!requireUnlocked || !DeviceConsentStore(this).locked())
        update(if (requireUnlocked) "Speaking reply…" else english)
        val spoken = if (output?.language == "ar") arabic else english
        val completed = withContext(Dispatchers.Main.immediate) {
            if (requireUnlocked && DeviceConsentStore(this@LocalVoiceSessionService).locked()) false else output?.say(spoken) == true
        }
        check(completed) { "Offline speech output unavailable. Install an offline voice in Android settings." }
        delay(180) // Speaker tail; the recorder is closed throughout TTS.
    }
    private suspend fun runSession() {
        val power = getSystemService(PowerManager::class.java) ?: run {
            update("Android power service unavailable. Unlock manually."); finishRequest(false); stopSelf(); return
        }
        val cpu = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OmniDev:VoiceSession").apply { setReferenceCounted(false) }
        try {
            cpu.acquire(240_000)
            withTimeout(220_000) {
                OfflineVoiceModels(this@LocalVoiceSessionService).withModel { model ->
                    input = OfflineSpeech(this@LocalVoiceSessionService, model)
                    output = LocalSpeechOutput(this@LocalVoiceSessionService)
                    output!!.initialize()
                    if (requestId == null) say("I'm ready. What would you like?", "أنا جاهز. تحب أعمل إيه؟")
                    repeat(8) {
                        currentCoroutineContext().ensureActive()
                        if (requestId != null) { finishRequest(unlock()); return@withModel }
                        update("Listening locally…")
                        val utterance = try { input?.listen(20) { allowed() && requestId == null } }
                            catch (error: IllegalStateException) { if (requestId != null) null else throw error }
                        if (requestId != null) return@repeat
                        if (utterance == null) return@withModel
                        if (utterance.confidence < .7) { say("I couldn't understand clearly. Please try again.", "مش سامع بوضوح. قول الطلب تاني."); return@repeat }
                        when (VoiceSessionPolicy.intent(utterance.text)) {
                            VoiceSessionPolicy.Intent.CANCEL -> return@withModel
                            VoiceSessionPolicy.Intent.UNLOCK -> {
                                if (!DeviceConsentStore(this@LocalVoiceSessionService).locked()) say("The phone is already unlocked.", "التليفون مفتوح بالفعل.")
                                else if (!unlock()) return@withModel
                            }
                            VoiceSessionPolicy.Intent.TASK -> {
                                // A locked-screen task stays only in this coroutine until unlock succeeds.
                                if (DeviceConsentStore(this@LocalVoiceSessionService).locked() && !unlock()) return@withModel
                                check(!DeviceConsentStore(this@LocalVoiceSessionService).locked())
                                val known = AssistantRuntime.get(this@LocalVoiceSessionService).chat.uiState.value.messages.map { it.messageId }.toSet()
                                withContext(Dispatchers.Main.immediate) {
                                    OmniVoiceInteractionService.resume(this@LocalVoiceSessionService)
                                    AssistantRuntime.get(this@LocalVoiceSessionService).send(utterance.text)
                                }
                                awaitReply(known)
                            }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: VoiceNativeUnavailableException) { update(error.message ?: "Offline speech library unavailable.") }
        catch (error: Exception) { update("Voice session stopped. Check the local model, offline voice and device permissions.") }
        finally {
            credential?.close(); credential = null; privatePhase = false; handingOff = false
            input?.stop(); input = null; releaseSpeechOutput()
            finishRequest(false)
            if (cpu.isHeld) cpu.release()
            stopSelf()
        }
    }
    private suspend fun awaitReply(known: Set<String>) {
        val controller = AssistantRuntime.get(this)
        var started = false
        repeat(360) {
            delay(250)
            if (requestId != null) finishRequest(unlock())
            val chat = controller.chat.uiState.value
            if (chat.isProcessing) started = true
            val answer = chat.messages.lastOrNull { it.role == MessageRole.ASSISTANT && it.messageId !in known }
            if (!chat.isProcessing && (started || answer != null)) {
                if (answer != null && !DeviceConsentStore(this).locked()) say(answer.content.take(1500), requireUnlocked = true)
                return
            }
        }
    }
    private suspend fun unlock(): Boolean {
        if (!DeviceConsentStore(this).locked()) return true
        if (attempted) return false
        val consent = DeviceConsentStore(this)
        if (!consent.enabled(DeviceConsentPolicy.Scope.VOICE_CREDENTIAL) || !consent.enabled(DeviceConsentPolicy.Scope.UNLOCK)) {
            say("Enable private spoken unlock in Device access, or unlock manually.", "فعّل فتح القفل بالصوت من صلاحيات الجهاز، أو افتح التليفون بنفسك."); return false
        }
        privatePhase = true; handingOff = true
        val generation = AssistantRuntime.get(this).sessionGeneration
        withContext(Dispatchers.Main.immediate) { AssistantRuntime.hideForUnlock?.invoke() }
        val nativePrompt = scope.async { DeviceUnlockActivity.request(this@LocalVoiceSessionService, true, voiceSession = true) }
        try {
            withTimeoutOrNull(8_000) {
                while (consent.locked() && LocalSpokenUnlock.visibleKind() == null && !nativePrompt.isCompleted) delay(150)
            }
            if (!consent.locked()) { privatePhase = false; say("Unlocked.", "تم فتح القفل."); return true }
            if (nativePrompt.isCompleted) { update(nativePrompt.await()); return false }
            val kind = LocalSpokenUnlock.visibleKind()
            if (kind == null) { say("The Android keypad is not supported. Unlock it manually.", "لوحة القفل غير مدعومة. افتح التليفون يدويًا."); return false }
            say(when (kind) {
                SpokenCredential.Kind.PIN -> "Say each PIN digit separately. Your code stays local."
                SpokenCredential.Kind.PASSWORD -> "Spell the password. Use capital for uppercase letters and names for symbols."
                SpokenCredential.Kind.PATTERN -> "Say the pattern points from one to nine. Top row is one, two, three."
            }, when (kind) {
                SpokenCredential.Kind.PIN -> "قول أرقام الرمز واحد واحد. الرمز بيبقى على الجهاز فقط."
                SpokenCredential.Kind.PASSWORD -> "هجّي كلمة المرور حرف حرف وحدد الحروف الكبيرة والرموز."
                SpokenCredential.Kind.PATTERN -> "قول نقاط النقش من واحد لتسعة. الصف العلوي واحد اتنين تلاتة."
            })
            update("Private code input · no transcript")
            val heard = input?.listen(35) { allowed() && !nativePrompt.isCompleted }
            if (!consent.locked()) return true
            if (nativePrompt.isCompleted) { update(nativePrompt.await()); return false }
            if (heard == null || heard.confidence < .9 || VoiceSessionPolicy.intent(heard.text) == VoiceSessionPolicy.Intent.CANCEL) {
                update("Private code input was cancelled or unclear. Nothing entered; unlock manually."); return false
            }
            credential = runCatching { SpokenCredentialParser.parse(heard.text, kind) }.getOrNull()
            if (credential == null) { say("I couldn't understand the code precisely. Use manual unlock. Nothing entered.", "الرمز مش واضح بالضبط. افتح يدويًا، ما دخلتش أي حاجة."); return false }
            say("Code received. Say confirm to enter it once, or cancel.", "الرمز وصل. قول تأكيد عشان أدخله مرة واحدة، أو إلغاء.")
            val confirm = input?.listen(10) { allowed() && !nativePrompt.isCompleted }
            if (!consent.locked()) return true
            if (nativePrompt.isCompleted) { update(nativePrompt.await()); return false }
            if (confirm == null || confirm.confidence < .9 || !VoiceSessionPolicy.confirmed(confirm.text)) {
                update("Private code entry was not confirmed. Nothing entered; unlock manually."); return false
            }
            // Hide the panel before any gesture; only the actual native lock UI is targeted.
            attempted = true
            val success = LocalSpokenUnlock.attempt(this, credential!!) || !consent.locked()
            credential = null
            privatePhase = false
            say(if (success) "Unlocked." else "Android did not unlock. No automatic retry. Unlock manually.",
                if (success) "تم فتح القفل." else "التليفون ما اتفتحش. مش هكرر المحاولة. افتحه يدويًا.")
            return success
        } catch (error: IllegalStateException) {
            // A manual/biometric unlock stops private capture; the waiting task can now resume.
            if (!consent.locked()) return true
            if (nativePrompt.isCompleted) { update(nativePrompt.await()); return false }
            throw error
        } finally {
            credential?.close(); credential = null; privatePhase = false; handingOff = false
            withContext(NonCancellable) { nativePrompt.cancelAndJoin() }
            if (currentCoroutineContext().isActive && AssistantRuntime.targetingScreen &&
                AssistantRuntime.get(this).sessionGeneration == generation &&
                (!consent.locked() || consent.enabled(DeviceConsentPolicy.Scope.LOCK_OVERLAY))) {
                withContext(Dispatchers.Main.immediate) { OmniVoiceInteractionService.resume(this@LocalVoiceSessionService) }
            }
        }
    }
    private fun finishRequest(success: Boolean) {
        requestId?.let { id -> synchronized(pending) {
            pending[id]?.complete(if (success && !DeviceConsentStore(this).locked()) "UNLOCKED: verified with Android keyguard state."
                else "USER_ACTION_REQUIRED: ${phase.removePrefix("USER_ACTION_REQUIRED: ")} Complete Android unlock manually, then continue the task. No automatic credential retry.")
        } }
        requestId = null
    }
    private fun releaseSpeechOutput() {
        val owned = output
        output = null
        owned?.close()
    }
    override fun onDestroy() {
        input?.stop(); releaseSpeechOutput(); session?.cancel(); scope.cancel()
        getSharedPreferences("device-user-consent", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(consentListener)
        getSharedPreferences(WakePreferences.NAME, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(consentListener)
        unregisterReceiver(privacy)
        if (active === this) { active = null; state.value = "Voice session stopped"; AssistantRuntime.get(this).listening(false) }
        finishRequest(false); super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "omni_private_voice"
        private const val ID = 9188
        private const val STOP = "stop_voice_session"
        private const val REQUEST = "private_unlock_request"
        private const val IMMUTABLE = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        @Volatile private var active: LocalVoiceSessionService? = null
        val privateInput get() = active?.privatePhase == true
        val handoff get() = active?.handingOff == true
        private val pending = mutableMapOf<String, CompletableDeferred<String>>()
        private val unlockGate = kotlinx.coroutines.sync.Mutex()
        private val state = MutableStateFlow("Voice session stopped")
        val status = state.asStateFlow()
        val running get() = active != null
        fun start(context: Context): Boolean {
            if (!WakePreferences(context).autoDictation || !OfflineVoiceModels(context).ready()) return false
            return runCatching { ContextCompat.startForegroundService(context, Intent(context, LocalVoiceSessionService::class.java)); true }.getOrDefault(false)
        }
        fun stop(context: Context) { active?.input?.stop(); active?.output?.stop(); context.stopService(Intent(context, LocalVoiceSessionService::class.java)) }
        fun unlockUnavailableReason(context: Context): String? {
            val consent = DeviceConsentStore(context)
            return VoiceSessionPolicy.unlockUnavailableReason(AssistantSettings.isSelected(context), WakePreferences(context).autoDictation,
                OfflineVoiceModels(context).ready(), ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED,
                com.omnidev.workspace.data.accessibility.OmniAccessibilityService.instance != null,
                consent.enabled(DeviceConsentPolicy.Scope.UNLOCK), consent.enabled(DeviceConsentPolicy.Scope.VOICE_CREDENTIAL),
                consent.enabled(DeviceConsentPolicy.Scope.LOCK_OVERLAY))
        }
        suspend fun requestUnlock(context: Context): Boolean = requestUnlockResult(context).startsWith("UNLOCKED:")

        suspend fun requestUnlockResult(context: Context): String = withContext(Dispatchers.Main.immediate) {
            val consent = DeviceConsentStore(context)
            if (!consent.locked()) return@withContext "UNLOCKED: verified with Android keyguard state."
            unlockUnavailableReason(context)?.let { return@withContext "USER_ACTION_REQUIRED: $it Or unlock Android manually, then continue." }
            if (!unlockGate.tryLock()) return@withContext "USER_ACTION_REQUIRED: another private unlock session is active. Complete it first."
            val id = UUID.randomUUID().toString(); val result = CompletableDeferred<String>()
            synchronized(pending) { pending[id] = result }
            try {
                // Capture the origin before showForUnlock marks the new voice host as active.
                // Its subsequent DeviceUnlockActivity is a handoff, not a second invocation.
                LockScreenAwake.holdForRequest(context, assistantHandoff = AssistantRuntime.targetingScreen)
                if (!OmniVoiceInteractionService.showForUnlock(context)) return@withContext "USER_ACTION_REQUIRED: Android could not show the private assistant. Open Omni in the foreground and unlock manually."
                ContextCompat.startForegroundService(context, Intent(context, LocalVoiceSessionService::class.java).putExtra(REQUEST, id))
                val outcome = withTimeoutOrNull(110_000) { result.await() }
                if (!consent.locked()) "UNLOCKED: verified with Android keyguard state."
                else outcome?.takeUnless { it.startsWith("UNLOCKED:") }
                    ?: "USER_ACTION_REQUIRED: private voice unlock timed out or Android remained locked. Unlock manually, then continue."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { "USER_ACTION_REQUIRED: Android could not start private voice unlock. Open Omni in the foreground and unlock manually." }
            finally {
                synchronized(pending) { pending.remove(id) }
                if (active?.requestId == id) stop(context)
                unlockGate.unlock()
            }
        }
    }
}
