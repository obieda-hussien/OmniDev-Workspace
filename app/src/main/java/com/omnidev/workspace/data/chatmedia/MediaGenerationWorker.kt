package com.omnidev.workspace.data.chatmedia

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Persist errors before retry/exit, and never replay an ambiguous billable create request. */
class MediaGenerationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("job") ?: return Result.failure()
        val store = MediaJobStore(applicationContext)
        val job = store.get(id) ?: return Result.failure()
        if (job.state == "cancelled") return Result.success()
        if (job.state == "completed") return deliver(job)
        try {
            val kind = MediaKind.fromAction(job.kind) ?: error("Unknown media kind")
            if (!MediaSettingsStore(applicationContext).get()[kind].enabled) { cancel(applicationContext, id); return Result.success() }
            if (runAttemptCount >= 40) return fail(store, job, "TIME_LIMIT", "The provider has not finished in the allowed time. Check the existing video operation later; do not generate a duplicate.")
            if (job.operation == null && job.state != "queued") return fail(store, job, job.errorCode ?: "INTERRUPTED",
                job.error ?: "Generation was interrupted before its result was recorded. Start a new request explicitly; no automatic duplicate generation.")
            if (!store.update(job.copy(state = "processing", phase = if (job.operation == null) "requesting" else "checking"))) return Result.success()
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
        fun enqueue(context: Context, id: String, replace: Boolean = false) {
            val store = MediaJobStore(context)
            val job = store.get(id) ?: return
            if (job.state == "failed" && MediaGenerationFailure.canResume(job)) store.update(job.copy(state = "waiting", phase = "checking", failures = 0))
            try {
                val request = OneTimeWorkRequestBuilder<MediaGenerationWorker>().setInputData(workDataOf("job" to id))
                    .addTag("omni-media-created:${System.currentTimeMillis()}")
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).build()
                WorkManager.getInstance(context).enqueueUniqueWork("omni-media-$id", if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
            } catch (_: Exception) {
                store.get(id)?.let { store.update(it.copy(state = "failed", phase = "failed", prompt = "", errorCode = "SCHEDULING_FAILED", error = "Generation could not be scheduled. Reopen the app and check background execution settings. No new provider request was submitted.")) }
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
