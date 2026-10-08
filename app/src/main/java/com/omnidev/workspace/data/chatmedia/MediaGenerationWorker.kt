package com.omnidev.workspace.data.chatmedia

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
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
        val id = inputData.getString("job") ?: return Result.failure()
        val store = MediaJobStore(applicationContext)
        val job = store.get(id) ?: return Result.failure()
        if (job.state == "cancelled") return Result.success()
        if (job.state == "completed") return deliver(job)
        if (job.state == "failed") return Result.failure()
        try {
            val kind = MediaKind.fromAction(job.kind) ?: error("Unknown media kind")
            if (!MediaSettingsStore(applicationContext).get()[kind].enabled) { cancel(applicationContext, id); return Result.success() }
            if (runAttemptCount >= 40) return fail(store, job, "TIME_LIMIT", "The provider has not finished in the allowed time. Check the existing video operation later; do not generate a duplicate.")
            if (job.operation == null && job.state != "queued") return fail(store, job, job.errorCode ?: "INTERRUPTED",
                job.error ?: "Generation was interrupted before its result was recorded. Start a new request explicitly; no automatic duplicate generation.")
            // Claim the saved snapshot before submitting a billable provider request.
            if (!store.compareAndUpdate(job, job.copy(state = "processing", phase = if (job.operation == null) "requesting" else "checking"))) return Result.success()
            val updated = MediaGenerationClient(applicationContext, onPhase = { phase ->
                store.get(id)?.let { current -> store.update(current.copy(phase = phase)) }
            }).step(job)
            val saved = updated.copy(phase = if (updated.state == "completed") "completed" else if (updated.state == "failed") "failed" else "generating",
                failures = 0, errorCode = if (updated.state == "failed") updated.errorCode ?: "PROVIDER_REJECTED" else null)
            if (!store.update(saved)) return Result.success()
            return when(saved.state) { "processing" -> Result.retry(); "completed" -> deliver(saved); else -> fail(store, saved, saved.errorCode ?: "PROVIDER_REJECTED", saved.error ?: "Provider returned no supported media output.") }
        } catch (cancelled: CancellationException) {
            // A stopped worker can otherwise leave an immortal "generating" record.
            withContext(NonCancellable) {
                store.get(id)?.takeIf { it.state !in setOf("completed", "cancelled", "failed") }?.let { current ->
                    val stopped = current.copy(state = if (current.operation == null) "failed" else "waiting", phase = "interrupted",
                        prompt = if (current.operation == null) "" else current.prompt, errorCode = "INTERRUPTED",
                        error = "Generation worker stopped. An existing video operation can resume when work is scheduled again; an unconfirmed create request is never repeated.")
                    if (store.update(stopped) && stopped.state == "failed") {
                        try { MediaCompletionPublisher.failure(applicationContext, stopped) }
                        catch (_: Exception) { /* The persisted card still reports the interruption. */ }
                    }
                }
            }
            throw cancelled
        } catch (error: Exception) {
            val current = store.get(id) ?: return Result.failure()
            if (current.state in setOf("cancelled", "completed")) return Result.success()
            val next = MediaGenerationFailure.transition(current, error, runAttemptCount)
            if (next.state == "waiting") { store.update(next); return Result.retry() }
            return fail(store, next, next.errorCode ?: "UNEXPECTED_ERROR", next.error ?: "Generation failed.")
        }
    }
    private suspend fun fail(store: MediaJobStore, job: MediaJob, code: String, detail: String): Result {
        val failed = job.copy(state = "failed", phase = "failed", errorCode = code, error = detail, prompt = "")
        if (store.update(failed)) {
            try { MediaCompletionPublisher.failure(applicationContext, failed) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Failure stays visible in the persisted card. */ }
        }
        return Result.failure()
    }
    private suspend fun deliver(job: MediaJob): Result = try {
        MediaCompletionPublisher.deliver(applicationContext, job); Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { if (runAttemptCount < 40) Result.retry() else Result.failure() }

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
            WorkManager.getInstance(context).cancelUniqueWork("omni-media-$id")
        }
    }
}
