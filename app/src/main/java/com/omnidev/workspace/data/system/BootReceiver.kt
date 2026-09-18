package com.omnidev.workspace.data.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.omnidev.workspace.OmniDevApp
import com.omnidev.workspace.data.background.BackgroundServiceSupervisor
import com.omnidev.workspace.data.debug.DebugLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Reconstructs durable OmniDev background work after reboot, user unlock, or app replacement.
 *
 * Recovery is intentionally registered only for post-unlock boot events because Room, DataStore
 * and WorkManager state live in credential-protected storage.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = context.applicationContext

        if (action !in SUPPORTED_ACTIONS) return
        val pending = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                Log.i(TAG, "Background recovery trigger: $action")
                DebugLogManager.appendInfo(TAG, "Background recovery trigger: $action")

                OmniDevApp.ensureWorkManagerInitialized(app)
                BackgroundServiceSupervisor.bootstrap(app)

                val requested = BackgroundServiceSupervisor.recoverNow(
                    app,
                    "boot:${action.substringAfterLast('.')}"
                )
                if (!requested && BackgroundServiceSupervisor.hasDurableWork(app)) {
                    BackgroundServiceSupervisor.scheduleRecovery(
                        app,
                        reason = "boot_deferred:${action.substringAfterLast('.')}",
                        delayMs = 5_000L
                    )
                }
            } catch (error: Exception) {
                // A boot/package-replaced broadcast must never take the whole process down.
                // Direct-boot/storage/WorkManager races are recoverable and will be retried by the
                // next unlock/package/boot signal or by the normal supervisor once the app opens.
                Log.e(TAG, "Background recovery trigger failed: $action", error)
                runCatching { DebugLogManager.appendError(TAG, error) }
                runCatching {
                    BackgroundServiceSupervisor.recordFailure(
                        app,
                        "Boot recovery failed: ${error.javaClass.simpleName}: ${error.message}"
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON"
        )
    }
}
