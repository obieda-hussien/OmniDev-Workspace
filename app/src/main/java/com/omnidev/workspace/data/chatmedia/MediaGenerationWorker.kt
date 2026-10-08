package com.omnidev.workspace.data.chatmedia

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Persist errors before retry/exit, and never replay an ambiguous billable create request. */
class MediaGenerationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channel = "omni-media-generation"
        val manager = requireNotNull(applicationContext.getSystemService(NotificationManager::class.java))
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(channel, "Media creation", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Omni is creating your media")
            .setContentText("You can keep using your phone while the request runs.")
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return ForegroundInfo(id.hashCode() and Int.MAX_VALUE, notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getString("job") ?: return Result.failure()
        return when (MediaGenerationExecution(applicationContext, jobId, runAttemptCount).run()) {
            MediaGenerationExecution.Outcome.DONE -> Result.success()
            MediaGenerationExecution.Outcome.RETRY -> Result.retry()
            MediaGenerationExecution.Outcome.FAILED -> Result.failure()
        }
    }

    companion object {
        private const val INTERACTIVE_TAG = "omni-media-interactive"
        private fun request(id: String, workId: UUID? = null): OneTimeWorkRequest {
            val builder = OneTimeWorkRequestBuilder<MediaGenerationWorker>().setInputData(workDataOf("job" to id))
                .addTag("omni-media-created:${System.currentTimeMillis()}").addTag(INTERACTIVE_TAG)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            if (workId != null) builder.setId(workId)
            return builder.build()
        }

        /** Upgrade the existing WorkSpec in place; never cancel/replay a create request. */
        internal fun promoteQueued(context: Context, job: MediaJob, info: WorkInfo, now: Long) {
            if (job.state != "queued" || info.state != WorkInfo.State.ENQUEUED || INTERACTIVE_TAG in info.tags ||
                now - job.queuedAt < MediaQueuePolicy.PROMOTE_AFTER_MS) return
            WorkManager.getInstance(context).updateWork(request(job.id, info.id)).get(5, TimeUnit.SECONDS)
        }

        suspend fun enqueue(context: Context, id: String): Unit = withContext(Dispatchers.IO) {
            val store = MediaJobStore(context)
            val job = store.get(id) ?: return@withContext
            val restart = when {
                job.state == "failed" && MediaQueuePolicy.canStart(job) -> MediaQueuePolicy.restart(job, System.currentTimeMillis())
                job.state == "failed" && MediaGenerationFailure.canResume(job) -> job.copy(state = "waiting", phase = "checking", failures = 0)
                job.state in setOf("queued", "waiting", "processing") -> job
                else -> return@withContext
            }
            try {
                val manager = WorkManager.getInstance(context)
                val active = manager.getWorkInfosForUniqueWork("omni-media-$id").get(5, TimeUnit.SECONDS).firstOrNull { !it.state.isFinished }
                // A timed-out queue's old worker can be finishing its terminal
                // result. Do not revive its record underneath that worker.
                if (job.state == "failed" && active?.state == WorkInfo.State.RUNNING) {
                    store.compareAndUpdate(job, job.copy(error = "Android is finishing the previous background worker. Tap Start now again in a moment."))
                    return@withContext
                }
                if (restart != job && !store.compareAndUpdate(job, restart)) return@withContext
                if (active == null) manager.enqueueUniqueWork("omni-media-$id", ExistingWorkPolicy.KEEP, request(id)).result.get(5, TimeUnit.SECONDS)
                else if (active.state == WorkInfo.State.ENQUEUED) manager.updateWork(request(id, active.id)).get(5, TimeUnit.SECONDS)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                // A worker that already claimed the request owns its result, even if
                // observing the scheduling acknowledgement timed out.
                val failed = restart.copy(state = "failed", phase = "failed", errorCode = "SCHEDULING_FAILED",
                    error = "Android could not schedule this request. Check background execution settings and tap Start now.")
                if (restart.state != "processing") {
                    if (!store.compareAndUpdate(restart, failed)) store.compareAndUpdate(job, failed)
                }
            }
        }
        fun cancel(context: Context, id: String) {
            val store = MediaJobStore(context); val job = store.get(id) ?: return
            if (job.state == "completed") return
            store.update(job.copy(state = "cancelled", phase = "cancelled", prompt = "", error = "Cancelled locally. Provider work already submitted may still incur usage."))
            MediaGenerationService.cancel(id)
            WorkManager.getInstance(context).cancelUniqueWork("omni-media-$id")
        }
    }
}
