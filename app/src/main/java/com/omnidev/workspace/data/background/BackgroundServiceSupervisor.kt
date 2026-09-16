package com.omnidev.workspace.data.background

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.omnidev.workspace.data.sync.OmniSyncService
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Durable supervisor for OmniDev background execution.
 *
 * A foreground service is still allowed to be killed by Android/OEM firmware. The important part
 * is therefore not pretending a process is immortal, but making the desired runtime state durable
 * and giving Android several independent, policy-compliant ways to reconstruct it.
 *
 * Recovery layers:
 *  - START_STICKY service restart (inside OmniSyncService)
 *  - AlarmManager rescue scheduled from onTaskRemoved/onDestroy
 *  - WorkManager one-shot rescue with backoff
 *  - Periodic WorkManager heartbeat watchdog
 *  - boot/package-replaced recovery receivers
 *
 * If the user explicitly stops the runtime, [setSyncDesired] stores that intent so watchdogs do
 * not resurrect it behind the user's back.
 */
object BackgroundServiceSupervisor {
    private const val TAG = "BgServiceSupervisor"
    private const val PREFS = "omnidev_background_supervisor"
    private const val KEY_SYNC_DESIRED = "sync_desired"
    private const val KEY_LAST_HEARTBEAT = "sync_last_heartbeat"
    private const val KEY_LAST_RECOVERY = "last_recovery"
    private const val KEY_RESTART_STREAK = "restart_streak"
    private const val KEY_LAST_FAILURE = "last_failure"

    private const val RECOVERY_WORK = "omni_background_recovery"
    private const val RECOVERY_REQUEST_CODE = 9402
    private const val DEFAULT_RECOVERY_DELAY_MS = 1_500L
    const val STALE_HEARTBEAT_MS = 90_000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Existing behavior is "always-on" unless the user explicitly stops it. */
    fun isSyncDesired(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SYNC_DESIRED, true)

    fun setSyncDesired(context: Context, desired: Boolean) {
        prefs(context).edit().putBoolean(KEY_SYNC_DESIRED, desired).apply()
        if (desired) {
            scheduleRecovery(context, "desired_state_enabled", 250L)
        } else {
            cancelPendingRecovery(context)
        }
    }

    fun recordHeartbeat(context: Context) {
        prefs(context).edit()
            .putLong(KEY_LAST_HEARTBEAT, System.currentTimeMillis())
            .putInt(KEY_RESTART_STREAK, 0)
            .remove(KEY_LAST_FAILURE)
            .apply()
    }

    fun heartbeatAgeMs(context: Context): Long {
        val last = prefs(context).getLong(KEY_LAST_HEARTBEAT, 0L)
        if (last == 0L) return Long.MAX_VALUE
        return (System.currentTimeMillis() - last).coerceAtLeast(0L)
    }

    fun hasDurableWork(context: Context): Boolean =
        isSyncDesired(context) || BackgroundChatTaskStore.active(context.applicationContext).isNotEmpty()

    /**
     * Called on every normal app process creation. Scheduling work is safe even when Android does
     * not currently allow an immediate FGS start; WorkManager/AlarmManager will retry later.
     */
    fun bootstrap(context: Context) {
        val app = context.applicationContext
        BackgroundServiceWatchdogWorker.schedulePeriodic(app)
        if (hasDurableWork(app)) scheduleRecovery(app, "application_bootstrap", 500L)
    }

    fun scheduleRecovery(
        context: Context,
        reason: String,
        delayMs: Long = DEFAULT_RECOVERY_DELAY_MS
    ) {
        val app = context.applicationContext
        if (!hasDurableWork(app)) return

        val streak = prefs(app).getInt(KEY_RESTART_STREAK, 0).coerceAtMost(8)
        val adaptiveDelay = min(60_000L, delayMs + (streak * streak * 750L))
        scheduleAlarm(app, reason, adaptiveDelay)

        val request = OneTimeWorkRequestBuilder<BackgroundServiceWatchdogWorker>()
            .setInputData(
                Data.Builder()
                    .putBoolean(BackgroundServiceWatchdogWorker.KEY_FORCE_RECOVERY, true)
                    .putString(BackgroundServiceWatchdogWorker.KEY_REASON, reason)
                    .build()
            )
            .setInitialDelay(adaptiveDelay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(app).enqueueUniqueWork(
            RECOVERY_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
        Log.i(TAG, "Recovery scheduled in ${adaptiveDelay}ms: $reason")
    }

    /** Best-effort immediate reconstruction. Returns true when startup was requested. */
    fun recoverNow(context: Context, reason: String): Boolean {
        val app = context.applicationContext
        if (!hasDurableWork(app)) return false

        val p = prefs(app)
        p.edit()
            .putLong(KEY_LAST_RECOVERY, System.currentTimeMillis())
            .putInt(KEY_RESTART_STREAK, (p.getInt(KEY_RESTART_STREAK, 0) + 1).coerceAtMost(20))
            .apply()

        var requested = false
        if (isSyncDesired(app)) {
            requested = runCatching {
                OmniSyncService.startFromRecovery(app, reason)
                true
            }.onFailure {
                recordFailure(app, "OmniSyncService: ${it.javaClass.simpleName}: ${it.message}")
            }.getOrDefault(false)
        }

        if (BackgroundChatTaskStore.active(app).isNotEmpty()) {
            requested = true
            runCatching {
                BackgroundChatRuntime.ensureStarted(app)
                BackgroundChatRuntime.recoverAll(app)
            }.onFailure {
                recordFailure(app, "BackgroundChatRuntime: ${it.javaClass.simpleName}: ${it.message}")
            }
        }

        if (requested) Log.i(TAG, "Recovery requested: $reason")
        return requested
    }

    fun onServiceHealthy(context: Context) {
        recordHeartbeat(context)
        cancelAlarmOnly(context)
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(RECOVERY_WORK)
    }

    fun recordFailure(context: Context, detail: String) {
        prefs(context).edit().putString(KEY_LAST_FAILURE, detail.take(1_000)).apply()
        Log.w(TAG, detail)
    }

    fun cancelPendingRecovery(context: Context) {
        cancelAlarmOnly(context)
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(RECOVERY_WORK)
    }

    private fun scheduleAlarm(context: Context, reason: String, delayMs: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = recoveryPendingIntent(context, reason)
        val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(250L)

        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms() ->
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
                else -> alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
            }
        }.onFailure { recordFailure(context, "Alarm scheduling failed: ${it.message}") }
    }

    private fun recoveryPendingIntent(context: Context, reason: String): PendingIntent {
        val intent = Intent(context, BackgroundRecoveryReceiver::class.java).apply {
            action = BackgroundRecoveryReceiver.ACTION_RECOVER_BACKGROUND
            putExtra(BackgroundRecoveryReceiver.EXTRA_REASON, reason)
        }
        return PendingIntent.getBroadcast(
            context,
            RECOVERY_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelAlarmOnly(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarmManager.cancel(recoveryPendingIntent(context, "cancel")) }
    }
}
