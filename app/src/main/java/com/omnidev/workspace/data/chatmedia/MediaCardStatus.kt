package com.omnidev.workspace.data.chatmedia

internal enum class MediaStage { LOADING, QUEUED, GENERATING, DOWNLOADING, WAITING, READY, FAILED, CANCELLED }
internal data class MediaCardStatus(val stage: MediaStage, val title: String, val detail: String, val code: String? = null) {
    val animated get() = stage in setOf(MediaStage.LOADING, MediaStage.GENERATING, MediaStage.DOWNLOADING)
    val ambientAnimated get() = !terminal
    val terminal get() = stage in setOf(MediaStage.FAILED, MediaStage.CANCELLED, MediaStage.READY)
    companion object {
        fun from(job: MediaJob?, loaded: Boolean, fileReady: Boolean, worker: String? = null, monitorError: Boolean = false): MediaCardStatus {
            if (!loaded) return MediaCardStatus(MediaStage.LOADING, "Loading creation", "Reading saved task state…")
            if (job == null) return MediaCardStatus(MediaStage.FAILED, "Creation unavailable", "The saved task record is missing or unreadable. Start a new request explicitly.", "MISSING_JOB")
            if (job.state == "completed") return if (fileReady) MediaCardStatus(MediaStage.READY, "Ready", "Saved and available in this conversation")
                else MediaCardStatus(MediaStage.FAILED, "File unavailable", "The provider finished, but the saved file is missing or unreadable. Check device storage.", "MISSING_FILE")
            if (job.state == "cancelled") return MediaCardStatus(MediaStage.CANCELLED, "Creation cancelled", job.error ?: "Stopped locally.")
            if (job.state == "failed") return MediaCardStatus(MediaStage.FAILED, "Creation failed", job.error ?: "Generation stopped without a supported output.", job.errorCode)
            if (worker in setOf("FAILED", "CANCELLED", "SUCCEEDED")) return MediaCardStatus(MediaStage.FAILED, "Creation interrupted", "Background work ended without recording a media result. An unconfirmed create request will not be repeated.", "WORK_STOPPED")
            if (monitorError) return MediaCardStatus(MediaStage.WAITING, "Task status unavailable", "Could not read background execution state. Reopen the app or check background execution settings.", "STATUS_UNAVAILABLE")
            if (job.state == "waiting" || worker in setOf("ENQUEUED", "BLOCKED") && job.state != "queued")
                return MediaCardStatus(MediaStage.WAITING, "Waiting to continue", job.error ?: "Waiting for connection or the next provider status check.", job.errorCode)
            if (job.state == "queued") return if (job.phase == "starting")
                MediaCardStatus(MediaStage.QUEUED, "Starting your request", "Starting media creation directly from your request…")
                else MediaCardStatus(MediaStage.QUEUED, "Waiting to start", "Android has not started this request yet. Check your connection or tap Start now. No provider request has been sent.")
            if (job.phase == "downloading") return MediaCardStatus(MediaStage.DOWNLOADING, "Saving your creation", "Downloading the completed provider output…")
            return MediaCardStatus(MediaStage.GENERATING, if (job.phase == "requesting") "Sending your request" else if (job.error != null) "Checking existing creation" else "Creating your ${job.kind}", job.error ?: "The provider is working. This is an animated placeholder, not a generated preview.", job.errorCode)
        }
        fun reconcile(job: MediaJob, worker: String?, workLoaded: Boolean, now: Long): MediaJob {
            if (job.state !in setOf("queued", "processing", "waiting")) return job
            if (job.state == "queued" && job.phase == "starting" && now - job.queuedAt < MediaQueuePolicy.PROMOTE_AFTER_MS)
                return job // The immediate service is starting; a previous worker's result is stale.
            if (MediaQueuePolicy.expired(job, worker, workLoaded, now)) return job.copy(state = "failed", phase = "failed",
                errorCode = "QUEUE_TIMEOUT", error = "Android did not start this request within five minutes. Check your connection and battery restrictions, then tap Start now. No provider request was sent.")
            val orphaned = workLoaded && worker == null && now - (if (job.state == "queued") job.queuedAt else job.created) > 30_000
            if (!orphaned && worker !in setOf("FAILED", "CANCELLED", "SUCCEEDED")) return job
            return job.copy(state = "failed", phase = "failed", prompt = if (job.state == "queued" && job.operation == null) job.prompt else "", errorCode = if (orphaned) "SCHEDULING_FAILED" else "WORK_STOPPED",
                error = if (orphaned) "No background worker is scheduled for this creation. Reopen the app and start a new request explicitly."
                else "Background work stopped without saving its result. Check device background restrictions; no unconfirmed request is automatically repeated.")
        }
    }
}
