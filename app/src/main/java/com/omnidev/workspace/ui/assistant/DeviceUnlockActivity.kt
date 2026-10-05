package com.omnidev.workspace.ui.assistant

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.omnidev.workspace.data.admin.DeviceConsentPolicy
import com.omnidev.workspace.data.admin.DeviceConsentStore
import com.omnidev.workspace.data.admin.KeyguardUnlockSession
import com.omnidev.workspace.data.assistant.AssistantRuntime
import com.omnidev.workspace.data.assistant.OmniVoiceInteractionService
import com.omnidev.workspace.data.voice.LocalVoiceSessionService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Private, bounded host for Android's own keyguard UI. No credential extras. */
class DeviceUnlockActivity : ComponentActivity() {
    private var requestId: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var session: KeyguardUnlockSession? = null
    private var unlock = false
    private var voiceSession = false
    private var resumed = false
    private var completed = false
    private var startedAt = 0L
    private val poll = object : Runnable {
        override fun run() {
            if (!ownsRequest()) return
            val consent = DeviceConsentStore(this@DeviceUnlockActivity)
            if (unlock) {
                session?.observe(SystemClock.elapsedRealtime(), consent.locked(), authorized(consent))?.let {
                    complete(it); return
                }
            } else {
                if (!authorized(consent)) { complete("DENIED: screen wake consent was revoked."); return }
                if (SystemClock.elapsedRealtime() - startedAt >= 500 &&
                    getSystemService(android.os.PowerManager::class.java)?.isInteractive == true) {
                    complete("AWAKE: display is interactive."); return
                }
                if (SystemClock.elapsedRealtime() - startedAt >= 5_000) {
                    complete("USER_ACTION_REQUIRED: Android did not wake the display."); return
                }
            }
            handler.postDelayed(this, 150)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra("request")
        unlock = intent.getBooleanExtra("unlock", false)
        voiceSession = intent.getBooleanExtra("voice_session", false)
        startedAt = savedInstanceState?.getLong("started_at") ?: SystemClock.elapsedRealtime()
        val consent = DeviceConsentStore(this)
        if (requestId !in pending || !authorized(consent)) {
            complete("DENIED: device consent unavailable."); return
        }
        requestId?.let { hosts[it] = this }
        com.omnidev.workspace.data.admin.LockScreenAwake.hold(this)
        com.omnidev.workspace.data.admin.LockScreenAwake.attach(window)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        session = KeyguardUnlockSession(startedAt + if (voiceSession) 90_000 else 60_000)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                complete("USER_ACTION_REQUIRED: unlock was cancelled. Unlock manually before continuing; no automatic retry.")
            }
        })
        handler.post(poll)
        // Do not ask SystemUI during onCreate: this window is not visible/resumed yet.
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        window.decorView.post { showPromptWhenReady() }
    }

    override fun onPause() { resumed = false; super.onPause() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) showPromptWhenReady()
    }

    private fun authorized(consent: DeviceConsentStore) =
        consent.enabled(if (unlock) DeviceConsentPolicy.Scope.UNLOCK else DeviceConsentPolicy.Scope.WAKE) &&
            (!voiceSession || consent.enabled(DeviceConsentPolicy.Scope.VOICE_CREDENTIAL))

    private fun ownsRequest() = !completed && requestId?.let { hosts[it] === this && pending[it]?.isActive == true } == true

    private fun showPromptWhenReady() {
        if (!unlock || !ownsRequest()) return
        val consent = DeviceConsentStore(this)
        session?.observe(SystemClock.elapsedRealtime(), consent.locked(), authorized(consent))?.let { complete(it); return }
        if (session?.begin(resumed, window.decorView.hasWindowFocus()) != true) return
        if (Build.VERSION.SDK_INT < 26) {
            complete("USER_ACTION_REQUIRED: unlock manually on Android versions below 8."); return
        }
        fun signal(value: KeyguardUnlockSession.Signal) {
            if (!ownsRequest()) return
            session?.callback(value, SystemClock.elapsedRealtime())
            handler.removeCallbacks(poll)
            handler.post(poll)
        }
        val manager = getSystemService(KeyguardManager::class.java)
        if (manager == null) { complete("USER_ACTION_REQUIRED: Android keyguard unavailable."); return }
        try {
            manager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = signal(KeyguardUnlockSession.Signal.SUCCEEDED)
                override fun onDismissCancelled() = signal(KeyguardUnlockSession.Signal.CANCELLED)
                override fun onDismissError() = signal(KeyguardUnlockSession.Signal.ERROR)
            })
        } catch (_: Exception) { signal(KeyguardUnlockSession.Signal.ERROR) }
    }

    private fun complete(result: String) {
        if (completed) return
        completed = true
        handler.removeCallbacksAndMessages(null)
        requestId?.let { pending[it]?.complete(result) }
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("started_at", startedAt)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        com.omnidev.workspace.data.admin.LockScreenAwake.detach(window)
        // Old callbacks from a rotated/replaced host must not complete the new host's request.
        if (requestId?.let { hosts[it] === this } == true) {
            if (!isChangingConfigurations && !completed) complete("USER_ACTION_REQUIRED: unlock host closed. Unlock manually before continuing.")
            requestId?.let { hosts.remove(it) }
        }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private val pending = mutableMapOf<String, CompletableDeferred<String>>()
        private val hosts = mutableMapOf<String, DeviceUnlockActivity>()
        private val requestGate = Mutex()
        suspend fun request(context: Context, unlock: Boolean, voiceSession: Boolean = false): String = withContext(Dispatchers.Main.immediate) {
            if (!requestGate.tryLock()) return@withContext "USER_ACTION_REQUIRED: another screen wake or unlock request is active. Complete it first; no second prompt was opened."
            val id = UUID.randomUUID().toString()
            val result = CompletableDeferred<String>()
            var restoreGeneration: Int? = null
            pending[id] = result
            try {
                val consent = DeviceConsentStore(context)
                if (!consent.enabled(if (unlock) DeviceConsentPolicy.Scope.UNLOCK else DeviceConsentPolicy.Scope.WAKE) ||
                    (voiceSession && !consent.enabled(DeviceConsentPolicy.Scope.VOICE_CREDENTIAL))) return@withContext "DENIED: device consent unavailable."
                if (unlock && !consent.locked()) return@withContext "UNLOCKED: verified with Android keyguard state."
                // Establish a fresh lease for a standalone request before launching its host.
                // Assistant/voice transfers share their existing lease; onCreate only continues it.
                com.omnidev.workspace.data.admin.LockScreenAwake.holdForRequest(context,
                    assistantHandoff = AssistantRuntime.targetingScreen || voiceSession)
                if (unlock && AssistantRuntime.targetingScreen && AssistantRuntime.hideForUnlock != null) {
                    restoreGeneration = AssistantRuntime.get(context).sessionGeneration
                    AssistantRuntime.hideForUnlock?.invoke()
                }
                context.startActivity(Intent(context, DeviceUnlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("request", id).putExtra("unlock", unlock).putExtra("voice_session", voiceSession))
                withTimeoutOrNull(if (voiceSession) 91_000 else if (unlock) 61_000 else 6_000) { result.await() }
                    ?: "USER_ACTION_REQUIRED: Android blocked or delayed the unlock activity. Open Omni in the foreground and unlock manually, then continue."
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                "USER_ACTION_REQUIRED: open Omni in the foreground; Android blocked the activity. Unlock manually, then continue."
            } finally {
                pending.remove(id)
                hosts.remove(id)?.finish()
                requestGate.unlock()
                if (restoreGeneration != null && currentCoroutineContext().isActive && !LocalVoiceSessionService.handoff &&
                    AssistantRuntime.targetingScreen && AssistantRuntime.get(context).sessionGeneration == restoreGeneration) {
                    runCatching { OmniVoiceInteractionService.resume(context) }
                }
            }
        }
    }
}
