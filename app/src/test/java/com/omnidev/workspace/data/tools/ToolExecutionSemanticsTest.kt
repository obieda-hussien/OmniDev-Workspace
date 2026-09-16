package com.omnidev.workspace.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionSemanticsTest {

    @Test
    fun `security exception hidden behind final echo becomes error`() {
        val raw = ToolExecutionResult(
            output = """
                SUCCESS
                [stderr]
                Exception occurred while executing 'put':
                java.lang.SecurityException: Permission Denial: getCurrentUser() requires android.permission.INTERACT_ACROSS_USERS
            """.trimIndent()
        )

        val result = ToolExecutionSemantics.normalize("agent_runtime", raw)

        assertTrue(result.isError)
        assertEquals("ANDROID_PERMISSION_DENIED", result.classification)
        assertTrue(result.persistentFailure)
        assertTrue(result.output.startsWith("[omni-outcome] status=FAIL"))
    }

    @Test
    fun `old crash log on stdout is data not current command failure`() {
        val raw = ToolExecutionResult(
            output = """
                2026-09-16 archived.log
                java.lang.SecurityException: Permission Denial: historical crash only
                stack trace follows here
            """.trimIndent()
        )

        val result = ToolExecutionSemantics.normalize("agent_runtime", raw)

        assertFalse(result.isError)
        assertEquals("SUCCESS", result.classification)
        assertTrue(result.output.startsWith("[omni-outcome] status=PASS"))
    }

    @Test
    fun `nonzero exit marker cannot be reported success`() {
        val raw = ToolExecutionResult(
            output = "❌ terminal failed (exit=1) [Termux err=-1]\nNo su program found on this device"
        )

        val result = ToolExecutionSemantics.normalize("agent_runtime", raw)

        assertTrue(result.isError)
        assertEquals(1, result.exitCode)
        assertEquals("ROOT_UNAVAILABLE", result.classification)
    }

    @Test
    fun `rish native loader failure is persistent when tool already reports error`() {
        val raw = ToolExecutionResult(
            output = "java.lang.UnsatisfiedLinkError: couldn't find \"librish.so\"",
            isError = true
        )

        val result = ToolExecutionSemantics.normalize("privileged_tool", raw)

        assertTrue(result.isError)
        assertEquals("RISH_NATIVE_LOADER_FAILURE", result.classification)
        assertTrue(ToolExecutionSemantics.isPersistentFailure(result))
    }

    @Test
    fun `normal terminal output stays successful`() {
        val result = ToolExecutionSemantics.normalize(
            "agent_runtime",
            ToolExecutionResult("Python 3.14.6")
        )

        assertFalse(result.isError)
        assertEquals("SUCCESS", result.classification)
    }

    @Test
    fun `large observations are compacted while telemetry survives`() {
        val result = ToolExecutionSemantics.normalize(
            "agent_runtime",
            ToolExecutionResult("x".repeat(20_000))
        )

        assertTrue(result.truncated)
        assertTrue(result.output.length < 5_000)
        assertTrue(result.output.startsWith("[omni-outcome]"))
        assertTrue(result.output.contains("observation compacted"))
    }
}
