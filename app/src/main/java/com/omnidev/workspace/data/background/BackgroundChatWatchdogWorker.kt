package com.omnidev.workspace.data.background

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.omnidev.workspace.data.sync.OmniSyncService
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Secondary recovery layer for aggressive OEM task killers.
 *
 * The primary path is the sticky OmniSync foreground service. This worker deliberately does not
 * execute the AI itself; it periodically revives the process/runtime while a durable run exists.
 */
class BackgroundChatWatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val token = inputData.getString(KEY_TOKEN) ?: return Result.success()
        val run = BackgroundChatTaskStore.get(applicationContext, token) ?: return Result.success()
        if (run.isTerminal) return Result.success()

        BackgroundChatRuntime.ensureStarted(applicationContext)
        BackgroundChatRuntime.kick(token)
        runCatching { OmniSyncService.start(applicationContext) }

        // Keep this WorkManager execution alive long enough to cover a service restart window.
        // The actual AI work remains in BackgroundChatRuntime/OmniSync's foreground process.
        repeat(24) {
            delay(5_000L)
            val latest = BackgroundChatTaskStore.get(applicationContext, token)
                ?: return Result.success()
            if (latest.isTerminal) return Result.success()
            BackgroundChatRuntime.kick(token)
        }
        return Result.retry()
    }

    companion object {
        private const val KEY_TOKEN = "run_token"
        private const val UNIQUE_PREFIX = "omni_background_chat_watchdog_"

        fun schedule(context: Context, token: String) {
            val request = OneTimeWorkRequestBuilder<BackgroundChatWatchdogWorker>()
                .setInputData(Data.Builder().putString(KEY_TOKEN, token).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_PREFIX + token.hashCode(),
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context, token: String) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(UNIQUE_PREFIX + token.hashCode())
        }

        fun cancelAll(context: Context) {
            // Individual unique workers are cancelled on terminal transitions. Keeping this method
            // cheap avoids broad cancellation of unrelated WorkManager jobs in the app.
            BackgroundChatTaskStore.all(context).filter { it.isTerminal }.forEach { cancel(context, it.token) }
        }
    }
}
