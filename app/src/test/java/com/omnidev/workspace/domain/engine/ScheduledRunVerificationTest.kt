package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.domain.engine.completion.CompletionReport
import com.omnidev.workspace.domain.engine.completion.FinishStatus
import org.junit.Assert.*
import org.junit.Test

class ScheduledRunVerificationTest {
    private fun report(status: FinishStatus, verified: Boolean) =
        CompletionReport("scheduled", null, status, "result", verified,
            emptyList(), emptyList(), emptyList(), "digest")

    @Test fun `plausible text is never completion evidence`() {
        val verdict = ScheduledRunVerification.evaluate("Email sent!", null, null)
        assertFalse(verdict.succeeded)
        assertTrue(verdict.reason!!.contains("UNVERIFIED"))
    }

    @Test fun `failed P0 criteria override a cheerful model answer`() {
        assertFalse(ScheduledRunVerification.evaluate("Done!", null, report(FinishStatus.COMPLETED, false)).succeeded)
        assertFalse(ScheduledRunVerification.evaluate("Done!", null, report(FinishStatus.INCOMPLETE, false)).succeeded)
    }

    @Test fun `verified P0 completion and an actual response permit success`() {
        assertTrue(ScheduledRunVerification.evaluate("Confirmed result", null,
            report(FinishStatus.COMPLETED, true)).succeeded)
        assertFalse(ScheduledRunVerification.evaluate("", null,
            report(FinishStatus.COMPLETED, true)).succeeded)
    }

    @Test fun `runtime error cannot be overwritten by a successful-looking P0 report`() {
        val verdict = ScheduledRunVerification.evaluate("Done", "network unavailable",
            report(FinishStatus.COMPLETED, true))
        assertFalse(verdict.succeeded)
        assertEquals("network unavailable", verdict.reason)
    }
}
