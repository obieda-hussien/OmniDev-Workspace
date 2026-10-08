package com.omnidev.workspace.data.chatmedia

import org.junit.Assert.*
import org.junit.Test

class MediaQueuePolicyTest {
    private val queued = MediaJob("song", "music", "gemini", "lyria-3-clip-preview", "song", "", created = 1000)

    @Test fun `hour old enqueued music stops waiting and can start its original request`() {
        val failed = MediaCardStatus.reconcile(queued, "ENQUEUED", true, 3_601_000)
        assertEquals("failed", failed.state)
        assertEquals("QUEUE_TIMEOUT", failed.errorCode)
        assertEquals("song", failed.prompt)
        assertTrue(MediaQueuePolicy.canStart(failed))
        assertEquals(MediaStage.FAILED, MediaCardStatus.from(failed, true, false).stage)
        val restarted = MediaQueuePolicy.restart(failed, 3_601_000)
        assertEquals(queued.id, restarted.id)
        assertEquals(queued.created, restarted.created)
        assertEquals(3_601_000L, restarted.queuedAt)
        assertEquals(restarted, MediaCardStatus.reconcile(restarted, "ENQUEUED", true, 3_601_001))
    }

    @Test fun `running or unknown work is never expired as an unsubmitted request`() {
        for (state in listOf("RUNNING", null)) {
            val result = MediaCardStatus.reconcile(queued, state, state != null, 3_601_000)
            assertEquals(queued, result)
        }
        val submitted = queued.copy(state = "processing", operation = "operations/existing", prompt = "")
        assertEquals(submitted, MediaCardStatus.reconcile(submitted, "ENQUEUED", true, 3_601_000))
    }

    @Test fun `missing schedule can recover but ambiguous submitted failures cannot replay`() {
        val orphaned = MediaCardStatus.reconcile(queued, null, true, 40_000)
        assertEquals("SCHEDULING_FAILED", orphaned.errorCode)
        assertTrue(MediaQueuePolicy.canStart(orphaned))
        for (code in listOf("NETWORK", "INTERRUPTED", "PROVIDER_REJECTED", "WORK_STOPPED")) {
            val ambiguous = queued.copy(state = "failed", errorCode = code)
            assertFalse(MediaQueuePolicy.canStart(ambiguous))
            assertTrue(runCatching { MediaQueuePolicy.restart(ambiguous, 40_000) }.isFailure)
        }
        assertFalse(MediaQueuePolicy.canStart(queued.copy(operation = "operations/existing")))
        assertFalse(MediaQueuePolicy.canStart(queued.copy(state = "cancelled")))
    }

    @Test fun `waiting cards retain truthful progress while their atmosphere can move`() {
        for (stage in listOf(MediaStage.QUEUED, MediaStage.WAITING)) {
            val status = MediaCardStatus(stage, "Waiting", "Not started")
            assertFalse(status.animated)
            assertTrue(status.ambientAnimated)
        }
        for (stage in listOf(MediaStage.CANCELLED, MediaStage.FAILED, MediaStage.READY))
            assertFalse(MediaCardStatus(stage, "Done", "").ambientAnimated)
    }
}
