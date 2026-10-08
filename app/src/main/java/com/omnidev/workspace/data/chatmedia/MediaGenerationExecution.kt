package com.omnidev.workspace.data.chatmedia

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The worker and immediate foreground path share one provider submission and recovery contract. */
internal class MediaGenerationExecution(private val context: Context, private val id: String, private val attempt: Int) {
    enum class Outcome { DONE, RETRY, FAILED }
    suspend fun run(): Outcome = MediaExecutionLease.run(id) { execute() } ?: Outcome.RETRY

    private suspend fun execute(): Outcome {
        val store = MediaJobStore(context)
        val job = store.get(id) ?: return Outcome.FAILED
        if (job.state == "cancelled") return Outcome.DONE
        if (job.state == "completed") return deliver(job)
        if (job.state == "failed") return Outcome.FAILED
        try {
            val kind = MediaKind.fromAction(job.kind) ?: error("Unknown media kind")
            if (!MediaSettingsStore(context).get()[kind].enabled) { MediaGenerationWorker.cancel(context, id); return Outcome.DONE }
            if (attempt >= 40) return fail(store, job, "TIME_LIMIT", "The provider has not finished in the allowed time. Check the existing video operation later; do not generate a duplicate.")
            if (job.operation == null && job.state != "queued") return fail(store, job, job.errorCode ?: "INTERRUPTED",
                job.error ?: "Generation was interrupted before its result was recorded. Start a new request explicitly; no automatic duplicate generation.")
            // Claim the saved snapshot before submitting a billable provider request.
            if (!store.compareAndUpdate(job, job.copy(state = "processing", phase = if (job.operation == null) "requesting" else "checking"))) return Outcome.DONE
            val updated = MediaGenerationClient(context, onPhase = { phase ->
                store.get(id)?.let { current -> store.update(current.copy(phase = phase)) }
            }).step(job)
            val saved = updated.copy(phase = if (updated.state == "completed") "completed" else if (updated.state == "failed") "failed" else "generating",
                failures = 0, errorCode = if (updated.state == "failed") updated.errorCode ?: "PROVIDER_REJECTED" else null)
            if (!store.update(saved)) return Outcome.DONE
            return when(saved.state) { "processing" -> Outcome.RETRY; "completed" -> deliver(saved); else -> fail(store, saved, saved.errorCode ?: "PROVIDER_REJECTED", saved.error ?: "Provider returned no supported media output.") }
        } catch (cancelled: CancellationException) {
            // A stopped worker can otherwise leave an immortal "generating" record.
            withContext(NonCancellable) {
                store.get(id)?.takeIf { it.state !in setOf("completed", "cancelled", "failed") }?.let { current ->
                    val stopped = current.copy(state = if (current.operation == null) "failed" else "waiting", phase = "interrupted",
                        prompt = if (current.operation == null) "" else current.prompt, errorCode = "INTERRUPTED",
                        error = "Generation worker stopped. An existing video operation can resume when work is scheduled again; an unconfirmed create request is never repeated.")
                    if (store.update(stopped) && stopped.state == "failed") {
                        try { MediaCompletionPublisher.failure(context, stopped) }
                        catch (_: Exception) { /* The persisted card still reports the interruption. */ }
                    }
                }
            }
            throw cancelled
        } catch (error: Exception) {
            val current = store.get(id) ?: return Outcome.FAILED
            if (current.state in setOf("cancelled", "completed")) return Outcome.DONE
            val next = MediaGenerationFailure.transition(current, error, attempt)
            if (next.state == "waiting") { store.update(next); return Outcome.RETRY }
            return fail(store, next, next.errorCode ?: "UNEXPECTED_ERROR", next.error ?: "Generation failed.")
        }
    }
    private suspend fun fail(store: MediaJobStore, job: MediaJob, code: String, detail: String): Outcome {
        val failed = job.copy(state = "failed", phase = "failed", errorCode = code, error = detail, prompt = "")
        if (store.update(failed)) {
            try { MediaCompletionPublisher.failure(context, failed) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Failure stays visible in the persisted card. */ }
        }
        return Outcome.FAILED
    }
    private suspend fun deliver(job: MediaJob): Outcome = try {
        MediaCompletionPublisher.deliver(context, job); Outcome.DONE
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { if (attempt < 40) Outcome.RETRY else Outcome.FAILED }

}
