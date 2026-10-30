package com.omnidev.workspace.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerRecoveryPlannerTest {
    @Test fun `a plain failure is retried`() {
        assertTrue(WorkerRecoveryPlanner.shouldRetry("Worker ended without a final response.", false, false))
    }

    @Test fun `success redirects and serial replans are never retried`() {
        assertFalse(WorkerRecoveryPlanner.shouldRetry(null, false, false))
        assertFalse(WorkerRecoveryPlanner.shouldRetry("boom", true, false))
        assertFalse(WorkerRecoveryPlanner.shouldRetry("READ_ONLY_WORKER: mutation", false, true))
    }

    @Test fun `running out of tokens is not retried from scratch`() {
        assertFalse(WorkerRecoveryPlanner.shouldRetry("Token budget exhausted for this worker", false, false))
        assertFalse(WorkerRecoveryPlanner.shouldRetry("Team budget exhausted before serial recovery.", false, false))
    }

    @Test fun `infrastructure failures wait longer and the wait grows`() {
        assertEquals(1_500L, WorkerRecoveryPlanner.waitMs(2, false))
        assertEquals(8_000L, WorkerRecoveryPlanner.waitMs(2, true))
        assertEquals(16_000L, WorkerRecoveryPlanner.waitMs(3, true))
    }

    @Test fun `the recovery note carries the failure and partial evidence but no fabricated claims`() {
        val note = WorkerRecoveryPlanner.note(
            attempt = 2,
            error = "TERMUX_RESULT_TIMEOUT",
            previousResult = "[FAILED] TERMUX_RESULT_TIMEOUT\n[Partial evidence]\nFound tizen_bridge.py"
        )
        assertTrue(note.contains("Recovery attempt 2/3"))
        assertTrue(note.contains("TERMUX_RESULT_TIMEOUT"))
        assertTrue(note.contains("Found tizen_bridge.py"))
        assertTrue(note.contains("Do not repeat the failing call"))
        assertTrue(note.contains("Never fabricate results"))
    }

    @Test fun `the note omits the evidence section when there is none`() {
        assertFalse(WorkerRecoveryPlanner.note(2, "x", "[FAILED] x").contains("Evidence the previous attempt"))
    }

    @Test fun `the incomplete marker matches the agent loop`() {
        assertEquals("[Incomplete]", WorkerRecoveryPlanner.INCOMPLETE_PREFIX)
    }
}
