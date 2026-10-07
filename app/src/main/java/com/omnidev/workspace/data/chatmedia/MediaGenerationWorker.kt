package com.omnidev.workspace.data.chatmedia

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Persisted video polling survives Activity/process recreation; uncertain creation is never replayed. */
class MediaGenerationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("job") ?: return Result.failure()
        val store = MediaJobStore(applicationContext)
        val job = store.get(id) ?: return Result.failure()
        if (job.state in setOf("completed", "cancelled")) return Result.success()
        if (runAttemptCount >= 40) {
            store.update(job.copy(state = "failed", error = "Video is taking longer than expected. Check status to continue the existing job."))
            return Result.failure()
        }
        // Before any billable create request, persist processing. A restart with no operation is ambiguous.
        if (job.operation == null && job.state != "queued") {
            store.update(job.copy(state = "failed", prompt = "", error = "Generation was interrupted before its result was recorded. Start a new request explicitly; no automatic duplicate generation."))
            return Result.failure()
        }
        if (!store.update(job.copy(state = "processing"))) return Result.success()
        return try {
            val updated = MediaGenerationClient(applicationContext).step(job)
            if (!store.update(updated)) return Result.success()
            if (updated.state == "processing") Result.retry() else if (updated.state == "completed") Result.success() else Result.failure()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (job.operation != null && runAttemptCount < 40) Result.retry()
            else {
                store.update((store.get(id) ?: job).copy(state = "failed", prompt = "", error = "Media generation failed or was interrupted. Check the provider API key, media access, quota and network. No automatic duplicate generation."))
                Result.failure()
            }
        }
    }
    companion object {
        fun enqueue(context: Context, id: String, replace: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<MediaGenerationWorker>().setInputData(workDataOf("job" to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("omni-media-$id", if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        }
        fun cancel(context: Context, id: String) {
            val store = MediaJobStore(context); store.get(id)?.let { store.update(it.copy(state = "cancelled", prompt = "", error = "Cancelled locally. Provider work already submitted may still incur usage.")) }
            WorkManager.getInstance(context).cancelUniqueWork("omni-media-$id")
        }
    }
}
