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
    @Test
    fun `run terminal exit zero with security exception is still failure`() {
        val raw = ToolExecutionResult(
            output = """
                $ content query --uri content://sms
                [exit_code: 0]
                [stdout]
                (empty)
                [stderr]
                Error while accessing provider:sms
                java.lang.SecurityException: Permission Denial: requires android.permission.ACCESS_CONTENT_PROVIDERS_EXTERNALLY
            """.trimIndent(),
            exitCode = 0,
            backend = "app-shell"
        )

        val result = ToolExecutionSemantics.normalize("run_terminal", raw)

        assertTrue(result.isError)
        assertEquals("ANDROID_PERMISSION_DENIED", result.classification)
        assertEquals("app-shell", result.backend)
        assertTrue(result.persistentFailure)
    }

    @Test
    fun `unsupported Android shell argument is not reported as success`() {
        val raw = ToolExecutionResult(
            output = """
                usage: adb shell content query --uri <URI>
                [ERROR] Unsupported argument: --limit
            """.trimIndent()
        )

        val result = ToolExecutionSemantics.normalize("shizuku_command", raw)

        assertTrue(result.isError)
        assertEquals("UNSUPPORTED_ARGUMENT", result.classification)
    }

    @Test
    fun `Termux RunCommand transport failure gets stable persistent class`() {
        val raw = ToolExecutionResult(
            output = "[termux] Android could not resolve/start Termux RunCommandService.",
            isError = true
        )

        val result = ToolExecutionSemantics.normalize("agent_runtime", raw)

        assertTrue(result.isError)
        assertEquals("TERMUX_RUN_COMMAND_UNAVAILABLE", result.classification)
        assertTrue(ToolExecutionSemantics.isPersistentFailure(result))
    }


    @Test
    fun `degraded diagnostics remain successful when one backend warning is embedded`() {
        val raw = ToolExecutionResult(
            output = """
                Overall       : DEGRADED_BUT_USABLE
                Preferred now : shizuku-user-service
                Termux smoke  : [termux] Android could not resolve/start Termux RunCommandService
                Shizuku       : PASS uid=2000(shell)
            """.trimIndent(),
            isError = false,
            classification = "DEGRADED_BUT_USABLE",
            backend = "shizuku-user-service",
            verification = "functional backend=shizuku-user-service"
        )

        val result = ToolExecutionSemantics.normalize("execution_diagnostics", raw)

        assertFalse(result.isError)
        assertEquals("DEGRADED_BUT_USABLE", result.classification)
        assertTrue(result.output.startsWith("[omni-outcome] status=PASS"))
    }


    @Test
    fun `pending user approval is a persistent non-retryable outcome`() {
        val raw = ToolExecutionResult(
            output =
                "USER_ACTION_REQUIRED: Requested runtime permission READ_SMS. Android is waiting for user approval.",
            isError = true
        )

        val result = ToolExecutionSemantics.normalize("request_permission", raw)

        assertTrue(result.isError)
        assertEquals("USER_ACTION_REQUIRED", result.classification)
        assertTrue(ToolExecutionSemantics.isPersistentFailure(result))
        assertFalse(result.retryable)
    }


}
