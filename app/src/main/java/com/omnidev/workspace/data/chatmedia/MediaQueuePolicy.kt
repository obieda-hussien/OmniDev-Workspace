package com.omnidev.workspace.data.chatmedia

/** Only a request that has never reached the provider can be restarted from the queue. */
internal object MediaQueuePolicy {
    const val PROMOTE_AFTER_MS = 30_000L
    const val START_TIMEOUT_MS = 5 * 60_000L
    fun canStart(job: MediaJob) = job.operation == null && job.prompt.isNotBlank() &&
        (job.state == "queued" || job.state == "failed" && job.errorCode in setOf("QUEUE_TIMEOUT", "SCHEDULING_FAILED", "START_FAILED"))

    fun restart(job: MediaJob, now: Long): MediaJob {
        require(canStart(job)) { "Only an unsubmitted queued request can be started." }
        return job.copy(state = "queued", phase = "queued", error = null, errorCode = null,
            failures = 0, failureAnnounced = false, queuedAt = now)
    }

    fun expired(job: MediaJob, worker: String?, workLoaded: Boolean, now: Long) =
        job.state == "queued" && job.operation == null && workLoaded && worker != "RUNNING" &&
            now - job.queuedAt >= START_TIMEOUT_MS
}
