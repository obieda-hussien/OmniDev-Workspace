package com.omnidev.workspace.data.tools.orchestration

import com.omnidev.workspace.data.tools.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
    @Test
    fun `mutation transport exception is never retried by default`() = runBlocking {
        val orchestrator = ToolOrchestrator()
        var attempts = 0

        val result = orchestrator.executeTool(
            toolName = "write_file",
            maxRetries = 3,
            retrySafe = false
        ) {
            attempts++
            throw IllegalStateException("transport dropped after uncertain write")
        }

        assertTrue(result.isFailure)
        assertEquals(1, attempts)
    }

    @Test
    fun `explicit read-only transport retry may recover`() = runBlocking {
        val orchestrator = ToolOrchestrator()
        var attempts = 0

        val result = orchestrator.executeTool(
            toolName = "read_file_lines",
            maxRetries = 2,
            baseRetryDelayMs = 1,
            retrySafe = true
        ) {
            attempts++
            if (attempts == 1) throw IllegalStateException("temporary transport failure")
            ToolExecutionResult("recovered read")
        }.getOrThrow()

        assertEquals(2, attempts)
        assertFalse(result.isError)
    }

    @Test
    fun `failed dependency skips dependent tool task`() = runBlocking {
        val orchestrator = ToolOrchestrator()
        var dependentExecuted = false

        val results = orchestrator.executeParallel(
            listOf(
                ToolOrchestrator.ToolTask(name = "prepare") {
                    ToolExecutionResult(
                        output = "permission denied",
                        isError = true,
                        classification = "ANDROID_PERMISSION_DENIED",
                        persistentFailure = true
                    )
                },
                ToolOrchestrator.ToolTask(
                    name = "apply",
                    dependencies = listOf("prepare")
                ) {
                    dependentExecuted = true
                    ToolExecutionResult("should not run")
                }
            )
        )

        assertFalse(dependentExecuted)
        assertTrue(results["apply"]?.isFailure == true)
        assertTrue(results["apply"]?.exceptionOrNull()?.message.orEmpty().contains("dependency 'prepare' failed"))
    }

    @Test
    fun `missing dependency fails without polling forever`() = runBlocking {
        val orchestrator = ToolOrchestrator()

        val results = orchestrator.executeParallel(
            listOf(
                ToolOrchestrator.ToolTask(
                    name = "consumer",
                    dependencies = listOf("missing")
                ) {
                    ToolExecutionResult("should not run")
                }
            )
        )

        assertTrue(results["consumer"]?.isFailure == true)
        assertTrue(results["consumer"]?.exceptionOrNull()?.message.orEmpty().contains("missing dependencies"))
    }

    @Test
    fun `cyclic dependency graph terminates with explicit failures`() = runBlocking {
        val orchestrator = ToolOrchestrator()

        val results = orchestrator.executeParallel(
            listOf(
                ToolOrchestrator.ToolTask(name = "a", dependencies = listOf("b")) {
                    ToolExecutionResult("a")
                },
                ToolOrchestrator.ToolTask(name = "b", dependencies = listOf("a")) {
                    ToolExecutionResult("b")
                }
            )
        )

        assertTrue(results["a"]?.isFailure == true)
        assertTrue(results["b"]?.isFailure == true)
        assertTrue(results["a"]?.exceptionOrNull()?.message.orEmpty().contains("cyclic"))
    }


}
