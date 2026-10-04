package com.omnidev.workspace.ui.assistant

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.omnidev.workspace.data.admin.DeviceConsentPolicy
import com.omnidev.workspace.data.admin.DeviceConsentStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Private, short-lived host for Android's own keyguard UI. No credential extras. */
class DeviceUnlockActivity : ComponentActivity() {
    private var requestId: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { complete("USER_ACTION_REQUIRED: unlock timed out or was blocked by Android.") }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra("request")
        val consent = DeviceConsentStore(this)
        val unlock = intent.getBooleanExtra("unlock", false)
        if (requestId !in pending || !consent.enabled(if (unlock) DeviceConsentPolicy.Scope.UNLOCK else DeviceConsentPolicy.Scope.WAKE)) {
            complete("DENIED: device consent unavailable."); return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        handler.postDelayed(timeout, 30_000)
        if (!unlock) {
            window.decorView.postDelayed({
                val interactive = getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
                complete(if (interactive) "AWAKE: display is interactive." else "USER_ACTION_REQUIRED: Android did not wake the display.")
            }, 500)
            return
        }
        if (Build.VERSION.SDK_INT < 26) { complete("USER_ACTION_REQUIRED: unlock manually on Android versions below 8."); return }
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = complete(if (!DeviceConsentStore(this@DeviceUnlockActivity).locked())
                    "UNLOCKED: verified with Android keyguard state." else "USER_ACTION_REQUIRED: Android still reports locked.")
                override fun onDismissCancelled() = complete("USER_ACTION_REQUIRED: Android unlock cancelled.")
                override fun onDismissError() = complete("USER_ACTION_REQUIRED: Android could not show its unlock prompt.")
            }) ?: complete("USER_ACTION_REQUIRED: Android keyguard unavailable.")
    }
    private fun complete(result: String) { requestId?.let { pending[it]?.complete(result) }; finish() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (!isChangingConfigurations) requestId?.let { pending[it]?.complete("USER_ACTION_REQUIRED: unlock host closed.") }
        super.onDestroy()
    }
    companion object {
        private val pending = mutableMapOf<String, CompletableDeferred<String>>()
        suspend fun request(context: Context, unlock: Boolean): String = withContext(Dispatchers.Main.immediate) {
            val id = UUID.randomUUID().toString()
            val result = CompletableDeferred<String>()
            pending[id] = result
            try {
                context.startActivity(Intent(context, DeviceUnlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("request", id).putExtra("unlock", unlock))
                withTimeoutOrNull(31_000) { result.await() } ?: "USER_ACTION_REQUIRED: Android blocked or delayed the unlock activity."
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                "USER_ACTION_REQUIRED: open Omni in the foreground; Android blocked the activity."
            } finally { pending.remove(id) }
        }
    }
}
