package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.data.tools.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AgentPipelineRecoveryTest {
    private class FakeTools(count: Int = 1) : ToolManager {
        val executed = mutableListOf<String>()
        private val definitions = (1..count).map {
            ToolDefinition(if (count == 1) "read_file" else "tool_$it", "operation_$it", listOf(ToolParameter("path", "string", "Path")))
        }
        override fun getToolDefinitions() = definitions
        override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
            executed += name
            return ToolExecutionResult("verified file content")
        }
    }

    private val config = AgentConfig(maxIterations = 10, enableRetry = false, enableMemoryTrimming = false)

    @Test fun `a permitted tool that was not loaded is loaded and executed in the same step`() = runTest {
        val tools = FakeTools(count = 80)
        var round = 0
        val pipeline = AgentPipeline(tools, completionProvider = { request ->
            when (++round) {
                1 -> CompletionResponse("", listOf(ToolCall("c1", "tool_80", mapOf("path" to "a.kt"))))
                else -> {
                    assertFalse(request.messages.last().content.contains("TOOL_NOT_EXPOSED"))
                    CompletionResponse("Verified")
                }
            }
        }, config = config)
        val events = pipeline.execute("Do something unrelated", modelId = "gpt-4o-mini", scopePath = "/tmp").toList()
        assertEquals(listOf("tool_80"), tools.executed)
        assertEquals("Verified", events.filterIsInstance<AgentEvent.FinalAnswer>().single().content)
    }

    @Test fun `a tool outside the permitted registry is still rejected`() = runTest {
        val tools = FakeTools()
        var round = 0
        val pipeline = AgentPipeline(tools, completionProvider = { request ->
            when (++round) {
                1 -> CompletionResponse("", listOf(ToolCall("c1", "not_registered", emptyMap())))
                else -> {
                    if (round == 2) assertTrue(request.messages.last().content.contains("TOOL_NOT_EXPOSED"))
                    CompletionResponse("", listOf(ToolCall("c2", "read_file", mapOf("path" to "a.kt"))))
                }
            }
        }, config = config)
        pipeline.execute("Read a file", modelId = "gpt-4o-mini", scopePath = "/tmp").toList()
        assertFalse("not_registered" in tools.executed)
    }

    @Test fun `an empty reply is recovered instead of being published`() = runTest {
        val tools = FakeTools()
        var round = 0
        val pipeline = AgentPipeline(tools, completionProvider = { request ->
            when (++round) {
                1 -> CompletionResponse("")
                else -> {
                    assertTrue(request.messages.last().content.contains("Omni runtime recovery"))
                    CompletionResponse("Verified result")
                }
            }
        }, config = config)
        val events = pipeline.execute("Read a file", modelId = "gpt-4o-mini", scopePath = "/tmp").toList()
        assertEquals("Verified result", events.filterIsInstance<AgentEvent.FinalAnswer>().single().content)
        assertTrue(events.none { it is AgentEvent.Error })
    }

    @Test fun `persistent empty replies end with a visible explanation not a blank answer`() = runTest {
        val tools = FakeTools()
        val pipeline = AgentPipeline(tools, completionProvider = { CompletionResponse("") }, config = config)
        val events = pipeline.execute("Read a file", modelId = "gpt-4o-mini", scopePath = "/tmp").toList()
        val final = events.filterIsInstance<AgentEvent.FinalAnswer>().single()
        assertTrue(final.content.isNotBlank())
        assertTrue(final.content.contains("no answer"))
    }

    @Test fun `announced work without action is pushed to act`() = runTest {
        val tools = FakeTools()
        var round = 0
        val pipeline = AgentPipeline(tools, completionProvider = { request ->
            when (++round) {
                1 -> CompletionResponse("I'll start by reading the file:")
                2 -> {
                    assertTrue(request.messages.last().content.contains("did not do it"))
                    CompletionResponse("", listOf(ToolCall("c1", "read_file", mapOf("path" to "a.kt"))))
                }
                else -> CompletionResponse("Verified")
            }
        }, config = config)
        val events = pipeline.execute("Read the file", modelId = "gpt-4o-mini", scopePath = "/tmp").toList()
        assertEquals(listOf("read_file"), tools.executed)
        assertEquals("Verified", events.filterIsInstance<AgentEvent.FinalAnswer>().single().content)
    }

    @Test fun `a repeated identical call is blocked and the run continues instead of being cancelled`() = runTest {
        val tools = FakeTools()
        var round = 0
        val pipeline = AgentPipeline(tools, completionProvider = { request ->
            ++round
            if (round <= 4) CompletionResponse("", listOf(ToolCall("c$round", "read_file", mapOf("path" to "a.kt"))))
            else {
                assertTrue(request.messages.any { m -> m.content.contains("DUPLICATE_CALL_BLOCKED") })
                CompletionResponse("Verified from the evidence already gathered")
            }
        }, config = AgentConfig(maxIterations = 10, enableRetry = false, enableMemoryTrimming = false, maxRepeatedToolCalls = 2))
        val events = pipeline.execute("Read the file", modelId = "gpt-4o-mini", scopePath = "/tmp").toList()
        assertTrue(events.none { it is AgentEvent.Error })
        assertEquals("Verified from the evidence already gathered", events.filterIsInstance<AgentEvent.FinalAnswer>().single().content)
        assertEquals(2, tools.executed.size)
    }
}
