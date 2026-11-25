package com.omnidev.workspace.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxReadOutcomeTest {
    private val grep = RuntimeReadPipeline.search("grep -n -- example source", 2)

    @Test
    fun grepNoMatchIsAnEmptySuccessfulObservationWithItsRealExitCode() {
        val result = observation(exitCode = 1)

        assertFalse(result.isError)
        assertEquals("NO_MATCHES", result.classification)
        assertEquals(1, result.exitCode)
        assertTrue(ToolExecutionSemantics.isEmptyObservation(result.output))
    }

    @Test
    fun grepExitOneWithDiagnosticsOrTransportFailureIsStillAFailure() {
        assertTrue(observation(exitCode = 1, stderr = "Permission denied").isError)
        assertTrue(observation(exitCode = 1, transportError = -1).isError)
        assertTrue(observation(exitCode = 1, originalStderrLength = 20).isError)
        assertTrue(observation(exitCode = 1, pipeline = RuntimeReadPipeline.read("ls source", 2)).isError)
    }

    @Test
    fun partialOutputDoesNotClearAnExecutionError() {
        val result = observation(exitCode = 2, stdout = "1:example\n", stderr = "grep: private: Permission denied")

        assertTrue(result.isError)
        assertEquals("FILESYSTEM_PERMISSION_DENIED", result.classification)
        assertEquals(2, result.exitCode)
        assertEquals("termux", result.backend)
        assertTrue(result.output.contains("1:example\n[stderr]\ngrep: private: Permission denied"))
    }

    @Test
    fun longSuccessfulReadPreservesTruncationAndExitStatus() {
        val result = observation(exitCode = 0, stdout = "first\nsecond\nextra\n")

        assertFalse(result.isError)
        assertTrue(result.truncated)
        assertEquals(0, result.exitCode)
        assertFalse(result.output.contains("extra"))
    }

    private fun observation(
        exitCode: Int,
        stdout: String = "",
        stderr: String = "",
        transportError: Int = 0,
        originalStderrLength: Int = stderr.length,
        pipeline: RuntimeReadPipeline = grep
    ): ToolExecutionResult = ToolExecutionSemantics.normalize(
        "agent_runtime",
        EnvironmentSetupManager.toToolResult(
            TermuxRunCommandBridge.TermuxCommandResult(
                exitCode, stdout, stderr, transportError, null, stdout.length, originalStderrLength
            ),
            operation = "terminal",
            readPipeline = pipeline
        )
    )
}
