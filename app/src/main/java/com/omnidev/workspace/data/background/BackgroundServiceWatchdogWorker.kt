package com.omnidev.workspace.data.background

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Process-independent heartbeat watchdog.
 *
 * WorkManager owns the scheduling state outside the lifetime of OmniDev's process. If an OEM kills
 * the foreground-service process, this worker can recreate the application process and ask the
 * supervisor to rebuild all durable work. It intentionally does not execute AI work itself.
 */
class BackgroundServiceWatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(KEY_FORCE_RECOVERY, false)
        val reason = inputData.getString(KEY_REASON)
            ?: if (force) "one_shot_watchdog" else "periodic_watchdog"

        return try {
            // Messaging listeners have their own persisted enabled-state and should be repaired
            // even when the central sync runtime was explicitly disabled by the user.
            IntegrationServiceRecovery.recoverEnabledNow(applicationContext, reason)

            if (!BackgroundServiceSupervisor.hasDurableWork(applicationContext)) {
                return Result.success()
            }

            val heartbeatStale = BackgroundServiceSupervisor.heartbeatAgeMs(applicationContext) >=
                BackgroundServiceSupervisor.STALE_HEARTBEAT_MS
            val hasChatRuns = BackgroundChatTaskStore.active(applicationContext).isNotEmpty()

            if (!force && !heartbeatStale && !hasChatRuns) return Result.success()

            val requested = BackgroundServiceSupervisor.recoverNow(applicationContext, reason)
            if (requested) Log.i(TAG, "Background recovery requested: $reason")
            Result.success()
        } catch (error: Throwable) {
            BackgroundServiceSupervisor.recordFailure(
                applicationContext,
                "Watchdog recovery failed: ${error.javaClass.simpleName}: ${error.message}"
            )
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "BgServiceWatchdog"
        private const val PERIODIC_WORK = "omni_background_heartbeat_watchdog"
        const val KEY_FORCE_RECOVERY = "force_recovery"
        const val KEY_REASON = "recovery_reason"

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackgroundServiceWatchdogWorker>(
                15, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
