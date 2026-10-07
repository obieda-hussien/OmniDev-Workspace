package com.omnidev.workspace.data.chatmedia

import java.io.IOException

internal class MediaProviderException(val status: Int) : IOException("Provider HTTP $status")
internal data class MediaFailure(val code: String, val detail: String, val retryable: Boolean)

/** Only curated diagnostics enter chat/persistent storage; credentials/provider bodies never do. */
internal object MediaGenerationFailure {
    fun describe(error: Exception): MediaFailure = when(error) {
        is MediaProviderException -> when(error.status) {
            401, 403 -> MediaFailure("HTTP_${error.status}", "Provider access denied. Check the selected account, API key and media permissions in Providers.", false)
            400, 404, 422 -> MediaFailure("HTTP_${error.status}", "Provider rejected this request. Check the selected model, supported settings and prompt.", false)
            429 -> MediaFailure("HTTP_429", "Provider quota or rate limit reached. Check billing/quota before checking this operation again.", true)
            else -> MediaFailure("HTTP_${error.status}", "Provider request failed (HTTP ${error.status}).", error.status >= 500)
        }
        is java.io.FileNotFoundException -> MediaFailure("STORAGE_ACCESS", "The output could not be saved. Check free device storage and file access.", false)
        is IOException -> MediaFailure("NETWORK", "Connection interrupted. Check your internet connection. An existing video operation can be checked without generating it again.", true)
        is IllegalArgumentException, is IllegalStateException -> MediaFailure("INVALID_RESPONSE", "No supported media result was saved. The provider may have refused the prompt or returned an incomplete result. Check model access and settings.", false)
        else -> MediaFailure("UNEXPECTED_ERROR", "Generation stopped after an unexpected ${error.javaClass.simpleName.take(60)}. Check provider access and settings; no duplicate generation was started.", false)
    }
    fun transition(job: MediaJob, error: Exception, attempt: Int): MediaJob {
        val failure = describe(error)
        val retry = job.operation != null && failure.retryable && job.failures < 4 && attempt < 40
        return job.copy(state = if (retry) "waiting" else "failed", phase = if (retry) "retrying" else "failed",
            errorCode = failure.code, error = failure.detail, failures = job.failures + 1,
            prompt = if (retry) job.prompt else "")
    }
    fun canResume(job: MediaJob) = job.operation != null && job.state == "failed" && job.errorCode in setOf("NETWORK", "INTERRUPTED", "TIME_LIMIT", "HTTP_429", "SCHEDULING_FAILED", "WORK_STOPPED", "STORAGE_ACCESS", "HTTP_401", "HTTP_403", null) ||
        job.operation != null && job.state == "failed" && job.errorCode?.matches(Regex("HTTP_5[0-9]{2}")) == true
}
