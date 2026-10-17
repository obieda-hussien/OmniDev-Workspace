package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.data.tools.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AgentRuntimeRecoveryTest {
    private class RuntimeTools : ToolManager {
        val executed = mutableListOf<Map<String, String>>()
        var restored = false
        var commandFailure = false
        override fun getToolDefinitions() = listOf(ToolDefinition("agent_runtime", "Execute developer and Android scripts", listOf(
            ToolParameter("action", "string", "Action"), ToolParameter("script", "string", "Script", required = false))))
        override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
            executed += arguments
            if (arguments["action"] == "env_probe") {
                restored = true
                return ToolExecutionResult("Fresh probe", backend = "runtime-probe",
                    verification = SetupState(privilegeBackend = "shizuku-user-service", termuxPrefix = "/termux").probeEvidence())
            }
            return if (commandFailure) ToolExecutionResult("Unknown option", true, classification = "NON_ZERO_EXIT", backend = "shizuku-user-service")
            else if (arguments["script"]?.startsWith("pm ") == true && !restored)
                ToolExecutionResult("Start Shizuku", true, classification = "SHIZUKU_UNAVAILABLE", backend = "shizuku-user-service", persistentFailure = true)
            else ToolExecutionResult("Completed", backend = if (restored) "shizuku-user-service" else "termux")
        }
    }
    private fun shell(id: String, script: String) = ToolCall(id, "agent_runtime", mapOf("action" to "shell_script", "script" to script))
    private val config = AgentConfig(maxIterations = 6, enableRetry = false, enableMemoryTrimming = false,
        enableSelfReflection = false, enableParallelToolExecution = false)

    @Test fun `persistent shell failure finishes with honest answer instead of error checkpoint`() = runTest {
        val tools = RuntimeTools()
        var round = 0
        val pipeline = AgentPipeline(tools, completionProvider = { request ->
            when (++round) {
                1 -> CompletionResponse("", listOf(shell("first", "pm list packages -f")))
                2 -> CompletionResponse("", listOf(shell("lookup", "command -v pm dumpsys am")))
                3 -> CompletionResponse("", listOf(shell("repeat", "export PATH=\$PATH:/system/bin && pm path com.omni")))
                else -> {
                    assertTrue(request.tools.orEmpty().isEmpty())
                    assertTrue(request.systemPrompt.orEmpty().contains("No tool calls"))
                    CompletionResponse("The developer shell worked. Android package inspection requires Shizuku access.")
                }
            }
        }, config = config)
        val events = pipeline.execute("Inspect Omni Android packages using the runtime shell", modelId = "gpt-4o-mini",
            scopePath = "/tmp", preferredToolNames = setOf("agent_runtime")).toList()
        assertEquals(2, tools.executed.size)
        assertEquals(4, round)
        assertTrue(events.none { it is AgentEvent.Error })
        assertTrue(events.filterIsInstance<AgentEvent.ToolResult>().any { it.output.contains("RUNTIME_BACKEND_BLOCKED") })
        val final = events.filterIsInstance<AgentEvent.FinalAnswer>().single()
        assertTrue(final.content.contains("not completed or verified"))
        assertEquals(final.content, final.conversationHistory.last().content)
    }

    @Test fun `model can report first missing backend without automatic tool rediscovery loop`() = runTest {
        val tools = RuntimeTools()
        var round = 0
        val pipeline = AgentPipeline(tools, completionProvider = {
            if (++round == 1) CompletionResponse("", listOf(shell("first", "pm list packages")))
            else CompletionResponse("Please start Shizuku and grant OmniDev access so package inspection can proceed.")
        }, config = config)
        val events = pipeline.execute("Inspect Android packages with agent_runtime", modelId = "gpt-4o-mini", scopePath = "/tmp",
            preferredToolNames = setOf("agent_runtime")).toList()
        assertEquals(2, round)
        assertTrue(events.none { it is AgentEvent.Error })
        assertTrue(events.filterIsInstance<AgentEvent.FinalAnswer>().single().content.contains("SHIZUKU_UNAVAILABLE"))
    }

    @Test fun `fresh probe after recovery allows real execution and verified completion`() = runTest {
        val tools = RuntimeTools()
        var round = 0
        val pipeline = AgentPipeline(tools, completionProvider = {
            when (++round) {
                1 -> CompletionResponse("", listOf(shell("first", "pm list packages")))
                2 -> CompletionResponse("", listOf(ToolCall("probe", "agent_runtime", mapOf("action" to "env_probe"))))
                3 -> CompletionResponse("", listOf(shell("retry", "pm list packages")))
                else -> CompletionResponse("Package inspection completed.")
            }
        }, config = config)
        val events = pipeline.execute("Inspect Android packages using runtime", modelId = "gpt-4o-mini", scopePath = "/tmp",
            preferredToolNames = setOf("agent_runtime")).toList()
        assertEquals(3, tools.executed.size)
        assertTrue(events.none { it is AgentEvent.Error })
        assertEquals("Package inspection completed.", events.filterIsInstance<AgentEvent.FinalAnswer>().single().content)
    }

    @Test fun `ordinary command errors retain failed execution guard`() = runTest {
        val tools = RuntimeTools().apply { commandFailure = true }
        var round = 0
        val pipeline = AgentPipeline(tools, completionProvider = {
            if (++round == 1) CompletionResponse("", listOf(shell("first", "pm unsupported")))
            else CompletionResponse("Inspection succeeded")
        }, config = config)
        val events = pipeline.execute("Inspect Android packages using runtime", modelId = "gpt-4o-mini", scopePath = "/tmp",
            preferredToolNames = setOf("agent_runtime")).toList()
        assertTrue(events.none { it is AgentEvent.FinalAnswer })
        assertTrue(events.any { it is AgentEvent.Error })
    }
}
