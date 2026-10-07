package com.omnidev.workspace.data.chatmedia

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class MediaCardStatusTest {
    private val job = MediaJob("job", "video", "gemini", "veo-3.1-fast-generate-preview", "prompt", "16:9", created = 1000)
    @Test fun `missing records and completed but missing files never animate as generating`() {
        assertEquals(MediaStage.LOADING, MediaCardStatus.from(null, false, false).stage)
        listOf(MediaCardStatus.from(null, true, false), MediaCardStatus.from(job.copy(state = "completed"), true, false)).forEach {
            assertEquals(MediaStage.FAILED, it.stage); assertFalse(it.animated); assertTrue(it.detail.isNotBlank())
        }
    }
    @Test fun `terminal worker outcomes reconcile orphaned generation without replaying it`() {
        for (worker in listOf("FAILED", "CANCELLED", "SUCCEEDED", null)) {
            val failed = MediaCardStatus.reconcile(job.copy(state = "processing", operation = "operations/one"), worker, true, 40000)
            assertEquals("failed", failed.state); assertEquals("operations/one", failed.operation); assertEquals("", failed.prompt)
            assertFalse(MediaCardStatus.from(failed, true, false).animated)
        }
        assertEquals(job, MediaCardStatus.reconcile(job, null, true, 1005))
        assertEquals(job, MediaCardStatus.reconcile(job, null, false, 40000))
    }
    @Test fun `finished and cancelled records cannot be downgraded by stale worker info`() {
        for (state in listOf("completed", "failed", "cancelled")) {
            val terminal = job.copy(state = state)
            assertEquals(terminal, MediaCardStatus.reconcile(terminal, "FAILED", true, 40000))
        }
        assertEquals(MediaStage.READY, MediaCardStatus.from(job.copy(state = "completed", path = "/video.mp4"), true, true).stage)
    }
    @Test fun `retry state retains an actionable error and stops the generation animation`() {
        val waiting = job.copy(state = "waiting", operation = "operations/one", error = "Connection interrupted", errorCode = "NETWORK", failures = 1)
        val status = MediaCardStatus.from(waiting, true, false, "ENQUEUED")
        assertEquals(MediaStage.WAITING, status.stage); assertFalse(status.animated); assertEquals("NETWORK", status.code)
        assertTrue(status.detail.contains("Connection"))
        assertTrue(MediaCardStatus.from(waiting.copy(state = "processing", phase = "checking"), true, false, "RUNNING").detail.contains("Connection"))
    }
    @Test fun `network retries only poll existing operations and stop after four consecutive failures`() {
        val createFailed = MediaGenerationFailure.transition(job, IOException("secret-key"), 0)
        assertEquals("failed", createFailed.state); assertEquals("", createFailed.prompt)
        var video = job.copy(operation = "operations/one", prompt = "", state = "processing")
        repeat(4) { video = MediaGenerationFailure.transition(video, IOException("secret-key"), it); assertEquals("waiting", video.state) }
        video = MediaGenerationFailure.transition(video, IOException("secret-key"), 4)
        assertEquals("failed", video.state); assertTrue(MediaGenerationFailure.canResume(video))
        assertFalse(video.error!!.contains("secret-key"))
    }
    @Test fun `authentication rejection and unknown exceptions are visible and never automatically retried`() {
        val operation = job.copy(operation = "operations/one")
        for (error in listOf(MediaProviderException(401), IllegalArgumentException("api-key-private"), RuntimeException("api-key-private"))) {
            val failed = MediaGenerationFailure.transition(operation, error, 0)
            assertEquals("failed", failed.state); assertNotNull(failed.errorCode); assertFalse(failed.error!!.contains("api-key-private"))
        }
        assertFalse(MediaGenerationFailure.canResume(operation.copy(state = "failed", errorCode = "PROVIDER_REJECTED")))
    }
}
