package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.data.tools.*
import com.omnidev.workspace.registry.ModelRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AgentPipelineToolContractTest {
    private class FakeTools : ToolManager {
        val executed = mutableListOf<String>()
        override fun getToolDefinitions() = listOf(ToolDefinition("read_file", "Read a file", listOf(ToolParameter("path", "string", "Path"))))
        override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
            executed += name
            return ToolExecutionResult("verified file content")
        }
    }
    private val config = AgentConfig(maxIterations = 6, enableRetry = false, enableMemoryTrimming = false)

    @Test fun `invented tool triggers runtime recovery without a discover call`() = runTest {
        val tools = FakeTools()
        var round = 0
        val pipeline = AgentPipeline(tools, completionProvider = { request ->
            when (++round) {
                1 -> CompletionResponse("", listOf(ToolCall("bad", "imaginary", emptyMap())))
                2 -> {
                    assertTrue(request.messages.last().content.contains("automatically retrieved"))
                    CompletionResponse("", listOf(ToolCall("read", "read_file", mapOf("path" to "a.kt"))))
                }
                else -> CompletionResponse("Verified")
            }
        }, config = config)
        val events = pipeline.execute("Read a file", modelId = "gpt-4o-mini", scopePath = "/tmp").toList()
        assertEquals(listOf("read_file"), tools.executed)
        assertTrue(events.any { it is AgentEvent.FinalAnswer })
    }

    @Test fun `operation routing cannot bypass worker scope`() = runTest {
        val tools = FakeTools()
        val pipeline = AgentPipeline(tools, completionProvider = {
            CompletionResponse("""{"omni_operation":{"intent":"read_file","arguments":{"path":"a.kt"}}}""")
        }, toolCallEligibility = { "READ_ONLY_WORKER: not allowed" }, config = config)
        val events = pipeline.execute("Read a file", modelId = "gpt-4o-mini", scopePath = "/tmp").toList()
        assertTrue(tools.executed.isEmpty())
        assertTrue(events.filterIsInstance<AgentEvent.Error>().any { it.message.startsWith("READ_ONLY_WORKER:") })
    }

    @Test fun `model without discovery can recover from unavailable tool reply`() = runTest {
        val tools = FakeTools()
        var round = 0
        val pipeline = AgentPipeline(tools, completionProvider = { request ->
            when (++round) {
                1 -> CompletionResponse("I cannot find a tool for this")
                2 -> {
                    assertTrue(request.messages.last().content.contains("automatically retrieved"))
                    CompletionResponse("""{"omni_operation":{"intent":"read_file","arguments":{"path":"a.kt"}}}""")
                }
                else -> CompletionResponse("Verified")
            }
        }, config = config)
        val events = pipeline.execute("Read a file", modelId = "gpt-4o-mini", scopePath = "/tmp").toList()
        assertEquals(listOf("read_file"), tools.executed)
        assertTrue(events.any { it is AgentEvent.FinalAnswer })
    }

    @Test fun `operation proposal still requires argument preflight`() = runTest {
        val tools = FakeTools()
        val pipeline = AgentPipeline(tools, completionProvider = {
            CompletionResponse("""{"omni_operation":{"intent":"read_file","arguments":{"wrong":"a.kt"}}}""")
        }, config = config)
        pipeline.execute("Read a file", modelId = "gpt-4o-mini", scopePath = "/tmp").toList()
        assertTrue(tools.executed.isEmpty())
    }

    @Test fun `unavailable tool replies are bounded and not published as success`() = runTest {
        val tools = FakeTools()
        var round = 0
        val pipeline = AgentPipeline(tools, completionProvider = {
            round++
            CompletionResponse("مفيش أداة مناسبة")
        }, config = config)
        val events = pipeline.execute("Read a file", modelId = "gpt-4o-mini", scopePath = "/tmp").toList()
        assertEquals(3, round)
        assertTrue(events.none { it is AgentEvent.FinalAnswer })
        assertTrue(tools.executed.isEmpty())
    }

    @Test fun `weak model corrects invented tool using discovery before execution`() = runTest {
        val tools = FakeTools()
        var round = 0
        val pipeline = AgentPipeline(tools, completionProvider = { request ->
            when (++round) {
                1 -> CompletionResponse("", listOf(ToolCall("bad", "fake_read", emptyMap())))
                2 -> {
                    assertTrue(request.messages.last().content.contains("TOOL_NOT_EXPOSED"))
                    CompletionResponse("", listOf(ToolCall("discover", "discover_tools", mapOf("query" to "read_file"))))
                }
                3 -> CompletionResponse("", listOf(ToolCall("read", "read_file", mapOf("path" to "a.kt"))))
                else -> CompletionResponse("Verified")
            }
        }, config = config)
        val events = pipeline.execute("Read the file", modelId = "gpt-4o-mini", scopePath = "/tmp").toList()
        assertEquals(listOf("read_file"), tools.executed)
        assertEquals("Verified", events.filterIsInstance<AgentEvent.FinalAnswer>().single().content)
    }
    @Test fun `three invalid batches stop without executor calls`() = runTest {
        val tools = FakeTools()
        var calls = 0
        val pipeline = AgentPipeline(tools, completionProvider = {
            CompletionResponse("", listOf(ToolCall("bad-${++calls}", "imaginary", emptyMap())))
        }, config = config)
        val events = pipeline.execute("Read a file", modelId = "gpt-4o-mini", scopePath = "/tmp").toList()
        assertTrue(tools.executed.isEmpty())
        assertEquals(3, calls)
        assertTrue(events.filterIsInstance<AgentEvent.Error>().any { it.message.contains("three corrections") })
    }
    @Test fun `unsupported success claim after failed batch is not published as completion`() = runTest {
        var round = 0
        val tools = FakeTools()
        val pipeline = AgentPipeline(tools, completionProvider = {
            if (++round == 1) CompletionResponse("", listOf(ToolCall("bad", "read_file", emptyMap())))
            else CompletionResponse("I verified the file successfully")
        }, config = config)
        val events = pipeline.execute("Read the file", modelId = "gpt-4o-mini", scopePath = "/tmp").toList()
        assertTrue(tools.executed.isEmpty())
        assertTrue(events.none { it is AgentEvent.FinalAnswer })
        assertTrue(events.filterIsInstance<AgentEvent.Error>().any { it.message.contains("unverified") })
    }
    @Test fun `disabled tools cannot be restored with preferred names or discovery`() = runTest {
        val tools = FakeTools()
        val pipeline = AgentPipeline(tools, completionProvider = {
            assertTrue(it.tools.orEmpty().isEmpty())
            CompletionResponse("", listOf(ToolCall("bad", "read_file", mapOf("path" to "a"))))
        }, config = config.copy(maxIterations = 1))
        pipeline.execute("Read a file", modelId = "gpt-4o-mini", scopePath = "/tmp",
            disabledToolNames = setOf("read_file"), preferredToolNames = setOf("read_file")).toList()
        assertTrue(tools.executed.isEmpty())
    }
    @Test fun `nonfunction model executes through text protocol without native payloads`() = runTest {
        val id = "contract-text-model"
        val originalModels = ModelRegistry.modelsByProvider[ModelProvider.OPENAI].orEmpty()
        ModelRegistry.setProviderModels(ModelProvider.OPENAI, originalModels + AIModel(id, "Contract text model", ModelProvider.OPENAI,
            contextWindow = 64_000, supportsFunctionCalling = false))
        val tools = FakeTools()
        var round = 0
        try {
            val pipeline = AgentPipeline(tools, completionProvider = { request ->
                assertNull(request.tools)
                assertTrue(request.systemPrompt.orEmpty().contains("CURRENT LOADED TOOLS"))
                assertTrue(request.messages.all { it.toolCalls.isEmpty() && it.toolResults.isEmpty() && it.role != MessageRole.TOOL })
                if (++round == 1) CompletionResponse("""{"omni_tool_call":{"name":"read_file","arguments":{"path":"a.kt"}}}""")
                else {
                    assertTrue(request.messages.last().content.contains("verified file content"))
                    CompletionResponse("Verified")
                }
            }, config = config)
            pipeline.execute("Read file", modelId = id, scopePath = "/tmp").toList()
            assertEquals(listOf("read_file"), tools.executed)
        } finally { ModelRegistry.setProviderModels(ModelProvider.OPENAI, originalModels) }
    }
}
