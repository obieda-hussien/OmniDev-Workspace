package com.omnidev.workspace.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPrivilegedCommandOutcomeTest {
    @Test fun confirmedFailureRetainsPartialOutputAndTransportIdentity() {
        val raw = AndroidPrivilegedCommandRouter.mapShizukuFailure(
            ShizukuResult.Failure("Command failed", 13, "partial listing", truncated = true)
        )

        assertTrue(raw.isError)
        assertEquals("NON_ZERO_EXIT", raw.classification)
        assertEquals(13, raw.exitCode)
        assertEquals("shizuku-user-service", raw.backend)
        assertTrue(raw.truncated)
        assertTrue(raw.output.contains("partial listing"))
    }

    @Test fun confirmedFailureCannotInheritRetryFromItsPartialOutput() {
        val raw = AndroidPrivilegedCommandRouter.mapShizukuFailure(
            ShizukuResult.Failure("Command failed", 2, "old log: Shizuku request timeout")
        )

        val result = ToolExecutionSemantics.normalize("run_terminal", raw)

        assertTrue(result.isError)
        assertEquals(2, result.exitCode)
        assertFalse(result.retryable)
    }

    @Test fun transportTimeoutRetainsItsClassificationWithoutAProcessExit() {
        val raw = AndroidPrivilegedCommandRouter.mapShizukuFailure(
            ShizukuResult.Failure("Shizuku request timeout")
        )

        val result = ToolExecutionSemantics.normalize("run_terminal", raw)

        assertTrue(result.isError)
        assertEquals("SHIZUKU_CONNECTION_TIMEOUT", result.classification)
        assertNull(result.exitCode)
        assertTrue(result.retryable)
    }
}
