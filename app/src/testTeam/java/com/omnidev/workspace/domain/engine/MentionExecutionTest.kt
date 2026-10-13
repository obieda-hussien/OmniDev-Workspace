package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.data.tools.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MentionExecutionTest {
    private class Tools : ToolManager {
        val executed = mutableListOf<String>()
        override fun getToolDefinitions() = listOf("web_search", "fetch_page", "read_file").map { ToolDefinition(it, it) }
        override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
            executed += name
            return ToolExecutionResult("verified content")
        }
    }

    @Test fun `agent prioritizes mentioned tools and can discover and execute another tool`() = runBlocking {
        val tools = Tools()
        var round = 0
        val pipeline = AgentPipeline(tools, completionProvider = { request ->
            assertTrue(request.tools.orEmpty().any { it.name == "read_file" })
            assertTrue(request.tools.orEmpty().any { it.name == "discover_tools" })
            when (++round) {
                1 -> CompletionResponse("", listOf(ToolCall("discover", "discover_tools", mapOf("query" to "fetch_page"))))
                2 -> {
                    assertTrue(request.tools.orEmpty().any { it.name == "fetch_page" })
                    CompletionResponse("", listOf(ToolCall("support", "fetch_page", emptyMap())))
                }
                3 -> CompletionResponse("", listOf(ToolCall("preferred", "read_file", emptyMap())))
                else -> CompletionResponse("Verified the file")
            }
        }, config = AgentConfig(maxIterations = 5, enableRetry = false, enableSelfReflection = false))
        pipeline.execute("@tool:read_file Read a file", modelId = "gpt-4o-mini", scopePath = "/tmp").toList()
        assertEquals(listOf("fetch_page", "read_file"), tools.executed)
    }

    @Test fun `disabled mentioned tool stops before any provider call`() = runBlocking {
        val pipeline = AgentPipeline(Tools(), completionProvider = { error("Must not spend tokens") })
        val events = pipeline.execute("@tool:read_file Check it", modelId = "gpt-4o-mini", scopePath = "/tmp",
            disabledToolNames = setOf("read_file")).toList()
        assertTrue(events.any { it is AgentEvent.Error && it.message.contains("unavailable") })
    }

    @Test fun `supporting tools still respect disabled access settings`() = runBlocking {
        val tools = Tools()
        var round = 0
        val pipeline = AgentPipeline(tools, completionProvider = { request ->
            assertFalse(request.tools.orEmpty().any { it.name == "fetch_page" })
            when (++round) {
                1 -> CompletionResponse("", listOf(ToolCall("disabled", "fetch_page", emptyMap())))
                2 -> CompletionResponse("", listOf(ToolCall("allowed", "read_file", emptyMap())))
                else -> CompletionResponse("Verified the file")
            }
        }, config = AgentConfig(maxIterations = 5, enableRetry = false, enableSelfReflection = false))
        pipeline.execute("@tool:read_file Read a file", modelId = "gpt-4o-mini", scopePath = "/tmp",
            disabledToolNames = setOf("fetch_page")).toList()
        assertEquals(listOf("read_file"), tools.executed)
    }

    @Test fun `chat prioritizes current mentions while supporting research tools remain usable`() = runBlocking {
        val tools = Tools()
        val base = CompletionRequest("test", listOf(
            ChatMessage(MessageRole.USER, "@tool:fetch_page old"),
            ChatMessage(MessageRole.USER, "@tool:web_search Research")))
        var round = 0
        ChatToolLoop(tools).run(base, emptySet(), "message", complete = { request ->
            assertEquals(setOf("web_search", "fetch_page", ChatToolLoop.REQUEST_MODE), request.tools.orEmpty().map { it.name }.toSet())
            assertEquals("web_search", request.tools.orEmpty().first().name)
            when (++round) {
                1 -> CompletionResponse("", listOf(ToolCall("wrong", "fetch_page", emptyMap())))
                2 -> CompletionResponse("", listOf(ToolCall("correct", "web_search", emptyMap())))
                else -> CompletionResponse("Done")
            }
        }, event = {})
        assertEquals(listOf("fetch_page", "web_search"), tools.executed)
    }

    @Test fun `team validates mentions before planner spends tokens`() = runBlocking {
        val team = SwarmOrchestrator(Tools(), completionProvider = { error("Must not spend tokens") })
        val events = team.execute("@tool:read_file Check", "gpt-4o-mini", "gpt-4o-mini", "/tmp",
            toolAccessMode = "DISABLED").toList()
        assertTrue(events.any { it is SwarmEvent.Error && it.message.contains("unavailable") })
    }
}
