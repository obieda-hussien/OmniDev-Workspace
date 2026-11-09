package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.data.tools.*
import com.omnidev.workspace.domain.engine.completion.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AgentExecutionRepairTest {
    private val config = AgentConfig(maxIterations = 12, enableRetry = false, enableMemoryTrimming = false, enableSelfReflection = false)
    private fun identity(id: String) = RunIdentity(runId = id, requireExecution = true, taskKind = TaskKind.ANALYSIS, forceFinishProtocol = true)

    @Test fun `missing local git pivots to exposed GitHub after exact blank discovery and finishes through P0`() = runTest {
        val executed = mutableListOf<String>(); var turns = 0
        val manager = object : ToolManager {
            override fun getToolDefinitions() = listOf(
                ToolDefinition("run_terminal", "Run shell", listOf(ToolParameter("command", "string", "Command", true))),
                ToolDefinition("github_manager", "GitHub repositories", listOf(ToolParameter("action", "string", "Action", true),
                    ToolParameter("repo", "string", "Repository"), ToolParameter("path", "string", "Path"))))
            override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
                executed += name
                return if (name == "run_terminal") MissingGitCapability.classify(arguments.getValue("command"), 127, "", "/system/bin/sh: git: inaccessible or not found")!!
                else ToolExecutionResult("Observed README at actual main SHA")
            }
        }
        val p = AgentPipeline(manager, completionProvider = { r ->
            assertTrue(r.systemPrompt.orEmpty().contains("not a verified checkout"))
            assertTrue(r.systemPrompt.orEmpty().contains("Use native function calls"))
            when (++turns) {
                1 -> {
                    assertTrue(r.tools.orEmpty().any { it.name == "github_manager" })
                    CompletionResponse("", listOf(ToolCall("git", "run_terminal", mapOf("command" to "git status && git log -n 5 --oneline"))))
                }
                2 -> CompletionResponse("""{"omni_operation":{"intent":"discover_tools","arguments":{}}}""")
                3 -> {
                    assertFalse(r.messages.last().content.contains("OPERATION_UNRESOLVED"))
                    CompletionResponse("", listOf(ToolCall("remote", "github_manager", mapOf("action" to "read_file", "repo" to "owner/project", "path" to "README.md"))))
                }
                else -> CompletionResponse("Read the actual repository source")
            }
        }, config = config)
        val events = p.execute("Inspect GitHub owner/project source", modelId = "gpt-4o-mini", scopePath = "/tmp", runIdentity = identity("remote-repair")).toList()
        val answer = events.filterIsInstance<AgentEvent.FinalAnswer>().single()
        assertTrue(answer.completion!!.verified)
        assertEquals(RunEvidenceLedger.Outcome.SKIPPED, answer.completion!!.evidence.first().outcome)
        assertEquals(listOf("run_terminal", "github_manager"), executed)
        assertTrue(events.filterIsInstance<AgentEvent.ToolResult>().none { it.classification == "OPERATION_UNRESOLVED" })
    }

    @Test fun `unsupported browser snapshot is preflight rejected and corrected using supported screenshot`() = runTest {
        val actions = mutableListOf<String>(); var turns = 0
        val manager = object : ToolManager {
            override fun getToolDefinitions() = listOf(ToolDefinition("headless_browser", "Browser", listOf(
                ToolParameter("action", "string", "Supported operation", true, allowedValues = BrowserToolPolicy.allActions.sorted()))))
            override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
                actions += arguments.getValue("action"); return ToolExecutionResult("Actual screenshot observation")
            }
        }
        val p = AgentPipeline(manager, completionProvider = { r -> when (++turns) {
            1 -> CompletionResponse("", listOf(ToolCall("bad", "headless_browser", mapOf("action" to "snapshot"))))
            2 -> {
                assertTrue(r.messages.last().content.contains("INVALID_TOOL_ARGUMENTS"))
                CompletionResponse("""{"omni_operation":{"intent":"headless_browser","arguments":{"action":"screenshot"}}}""")
            }
            else -> CompletionResponse("Screenshot captured")
        } }, config = config)
        val events = p.execute("Inspect the browser page", modelId = "gpt-4o-mini", scopePath = "/tmp", runIdentity = identity("browser-repair")).toList()
        assertEquals(listOf("screenshot"), actions)
        assertTrue(events.filterIsInstance<AgentEvent.FinalAnswer>().single().completion!!.verified)
    }

    @Test fun `interrupted mixed operation prose is never streamed or executed as page state`() = runTest {
        var executions = 0
        val tools = object : ToolManager {
            override fun getToolDefinitions() = listOf(ToolDefinition("headless_browser", "Browser", listOf(ToolParameter("action", "string", "Action", true))))
            override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
                executions++; return ToolExecutionResult("Should not run")
            }
        }
        val p = AgentPipeline(tools, completionProvider = { error("Streaming expected") }, streamingCompletionProvider = { _, emit ->
            emit("{"); emit("\"omni_operation\":{\"intent\":\"headless_browser\",\"arguments\":{\"action\":\"screenshot\"}}}")
            emit("Payment page opened and saved")
            throw java.io.IOException("Interrupted")
        }, config = config)
        val events = p.execute("Inspect browser", modelId = "gpt-4o-mini", scopePath = "/tmp", runIdentity = identity("interrupted-draft")).toList()
        assertEquals(0, executions)
        assertTrue(events.none { it is AgentEvent.StreamChunk || it is AgentEvent.FinalAnswer })
        assertTrue(events.filterIsInstance<AgentEvent.Error>().any { it.message.contains("interrupted after partial output") })
    }

    @Test fun `incomplete P0 terminal is never advertised as publishing a verified result`() = runTest {
        val tools = object : ToolManager {
            override fun getToolDefinitions() = listOf(ToolDefinition("read_file", "Read source"))
            override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?) = error("No execution")
        }
        val p = AgentPipeline(tools, completionProvider = { CompletionResponse("", listOf(ToolCall("finish", FinishTool.NAME,
            mapOf("status" to "incomplete", "summary" to "No repository was read")))) }, config = config)
        val events = p.execute("Inspect repository", modelId = "gpt-4o-mini", scopePath = "/tmp", runIdentity = identity("honest-report")).toList()
        assertFalse(events.filterIsInstance<AgentEvent.FinalAnswer>().single().completion!!.verified)
        assertTrue(events.filterIsInstance<AgentEvent.PhaseChanged>().none { it.detail == "Publishing verified result" })
    }
}
