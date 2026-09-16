package com.omnidev.workspace.data.tools.orchestration

import com.omnidev.workspace.data.tools.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOrchestratorSemanticTest {

    @Test
    fun `persistent semantic failures do not disable whole multipurpose tool`() = runBlocking {
        val orchestrator = ToolOrchestrator()

        repeat(6) {
            val result = orchestrator.executeTool(
                toolName = "agent_runtime",
                maxRetries = 0
            ) {
                ToolExecutionResult(
                    output = "WRONG_EXECUTION_DOMAIN: use privileged_tool",
                    isError = true,
                    classification = "WRONG_EXECUTION_DOMAIN",
                    persistentFailure = true
                )
            }.getOrThrow()

            assertTrue(result.isError)
        }

        // The same agent_runtime tool must still be usable for a legitimate Linux task.
        val recovery = orchestrator.executeTool(
            toolName = "agent_runtime",
            maxRetries = 0
        ) {
            ToolExecutionResult("Python 3.14.6")
        }.getOrThrow()

        assertFalse(recovery.isError)
        assertTrue(recovery.output.contains("status=PASS"))
    }

    @Test
    fun `second persistent semantic failure emits pivot circuit telemetry`() = runBlocking {
        val orchestrator = ToolOrchestrator()

        repeat(2) { iteration ->
            val result = orchestrator.executeTool(
                toolName = "privileged_tool",
                maxRetries = 0
            ) {
                ToolExecutionResult(
                    output = "RISH_NATIVE_LOADER_FAILURE: couldn't find librish.so",
                    isError = true,
                    classification = "RISH_NATIVE_LOADER_FAILURE",
                    persistentFailure = true
                )
            }.getOrThrow()

            if (iteration == 1) {
                assertTrue(result.output.contains("[semantic-circuit]"))
                assertTrue(result.output.contains("DO NOT retry the same backend strategy"))
            }
        }
    }
}
