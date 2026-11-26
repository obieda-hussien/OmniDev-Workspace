package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.ipc.toPrivilegedProcessFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedFileProcessFailureTest {
    @Test fun completedProcessFailureCarriesExitCodeAndPartialOutput() {
        val source = ShizukuResult.Failure(
            reason = "Command failed",
            exitCode = 13,
            output = "visible entry\n[stderr]\nPermission denied"
        )

        val result = AdvancedFileTools.formatPrivilegedFailure(requireNotNull(source.toPrivilegedProcessFailure()))

        assertTrue(result.isError)
        assertEquals(13, result.exitCode)
        assertEquals("NON_ZERO_EXIT", result.classification)
        assertEquals("shizuku-user-service", result.backend)
        assertTrue(result.output.contains("visible entry"))
        assertTrue(result.output.contains("Permission denied"))
        assertFalse(result.retryable)
    }

    @Test fun truncatedProcessFailureRemainsAnError() {
        val source = ShizukuResult.Failure("Command failed", 2, "partial output", truncated = true)

        val result = AdvancedFileTools.formatPrivilegedFailure(requireNotNull(source.toPrivilegedProcessFailure()))

        assertEquals(2, result.exitCode)
        assertTrue(result.isError)
        assertTrue(result.truncated)
        assertTrue(result.output.contains("partial output"))
    }

    @Test fun serviceFailureDoesNotAcquireAProcessExitCode() {
        assertNull(ShizukuResult.Failure("Shizuku UserService disconnected").toPrivilegedProcessFailure())
    }
}
