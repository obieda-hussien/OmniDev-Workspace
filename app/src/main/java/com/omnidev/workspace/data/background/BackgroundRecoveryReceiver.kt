package com.omnidev.workspace.data.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Receives AlarmManager rescue events after task removal/process teardown. */
class BackgroundRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_RECOVER_BACKGROUND) return
        val reason = intent.getStringExtra(EXTRA_REASON) ?: "alarm_recovery"
        try {
            val requested = BackgroundServiceSupervisor.recoverNow(context.applicationContext, reason)
            Log.i(TAG, "Alarm recovery handled: reason=$reason requested=$requested")
        } catch (error: Throwable) {
            BackgroundServiceSupervisor.recordFailure(
                context.applicationContext,
                "Alarm recovery failed: ${error.javaClass.simpleName}: ${error.message}"
            )
            // If a background FGS start is temporarily disallowed, hand recovery back to
            // WorkManager instead of repeatedly crashing a broadcast receiver.
            BackgroundServiceSupervisor.scheduleRecovery(
                context.applicationContext,
                "alarm_retry:$reason",
                10_000L
            )
        }
    }

    companion object {
        private const val TAG = "BgRecoveryReceiver"
        const val ACTION_RECOVER_BACKGROUND =
            "com.omnidev.workspace.action.RECOVER_BACKGROUND"
        const val EXTRA_REASON = "recovery_reason"
    }
}
