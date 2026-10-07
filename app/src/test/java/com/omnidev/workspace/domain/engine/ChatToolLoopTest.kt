package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.data.tools.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChatToolLoopTest {
    private class FakeTools : ToolManager {
        val executed = mutableListOf<String>()
        override fun getToolDefinitions() = (ChatToolLoop.WEB_TOOLS + "delete_file").map { ToolDefinition(it, it, listOf(ToolParameter("query", "string", "Query", false))) }
        override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
            executed += name
            return ToolExecutionResult("verified result")
        }
    }
    private val request = CompletionRequest("test", listOf(ChatMessage(MessageRole.USER, "Research", messageId = "user")))

    @Test fun `chat exposes only allowed tools and preserves call result pairing`() = runTest {
        val tools = FakeTools()
        var round = 0
        val result = ChatToolLoop(tools).run(request, setOf("web_search_deep"), "user", complete = {
            assertFalse(it.tools.orEmpty().any { tool -> tool.name == "delete_file" || tool.name == "web_search_deep" })
            if (round++ == 0) CompletionResponse("", listOf(ToolCall("call-1", "web_search", mapOf("query" to "test"))))
            else {
                assertEquals("call-1", it.messages.last().toolResults.single().toolCallId)
                CompletionResponse("Answer with sources")
            }
        }, event = {})
        assertEquals(listOf("web_search"), tools.executed)
        assertEquals("Answer with sources", result.content)
        assertNull(result.request)
    }

    @Test fun `provider without usage metadata still consumes chat budget`() = runTest {
        val usageEvents = mutableListOf<AgentEvent.TokenUsageUpdate>()
        val result = ChatToolLoop(FakeTools()).run(request, emptySet(), "user", complete = {
            CompletionResponse("A useful answer without native usage metadata")
        }, event = { event ->
            if (event is AgentEvent.TokenUsageUpdate) usageEvents += event
        })

        assertEquals("A useful answer without native usage metadata", result.content)
        assertEquals(1, usageEvents.size)
        assertTrue(usageEvents.single().iterationTokens > 0)
        assertTrue(usageEvents.single().totalTokens > 0)
        assertEquals(32_000, usageEvents.single().budget)
    }

    @Test fun `mode tool proposes but never executes a worker`() = runTest {
        val tools = FakeTools()
        val result = ChatToolLoop(tools).run(request, emptySet(), "user", complete = {
            CompletionResponse("", listOf(ToolCall("request", ChatToolLoop.REQUEST_MODE,
                mapOf("mode" to "SWARM", "reason" to "Enable independent workers?"))))
        }, event = {})
        assertTrue(tools.executed.isEmpty())
        assertEquals("SWARM", result.request?.mode)
        assertEquals("pending", result.request?.status)
        assertEquals("user", result.request?.originMessageId)
    }

    @Test fun `unknown tools never reach executor and repetition is bounded`() = runTest {
        val tools = FakeTools()
        var completions = 0
        ChatToolLoop(tools).run(request, emptySet(), "user", complete = {
            completions++
            CompletionResponse("", listOf(ToolCall("call-$completions", "delete_file", emptyMap())))
        }, event = {})
        assertTrue(tools.executed.isEmpty())
        assertEquals(7, completions)
    }

    @Test fun `duplicate web calls execute once`() = runTest {
        val tools = FakeTools()
        ChatToolLoop(tools).run(request, emptySet(), "user", complete = {
            CompletionResponse("", listOf(ToolCall("same", "web_search", mapOf("query" to "test"))))
        }, event = {})
        assertEquals(1, tools.executed.size)
    }

    @Test fun `cancellation is not converted to tool error`() = runTest {
        val tools = object : ToolManager {
            override fun getToolDefinitions() = listOf(ToolDefinition("web_search", "search", listOf(ToolParameter("query", "string", "Query", false))))
            override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
                throw CancellationException("stopped")
            }
        }
        try {
            ChatToolLoop(tools).run(request, emptySet(), "user", complete = {
                CompletionResponse("", listOf(ToolCall("x", "web_search", emptyMap())))
            }, event = {})
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
    @Test fun `chat redacts transient secrets before model history and events`() = runTest {
        val tools = object : ToolManager {
            override fun getToolDefinitions() = listOf(ToolDefinition("web_search", "search", listOf(ToolParameter("query", "string", "Query", false))))
            override suspend fun executeTool(
                name: String,
                arguments: Map<String, String>,
                scopePath: String?
            ): ToolExecutionResult =
                ToolExecutionResult("otp=819204 result=useful")
        }
        val events = mutableListOf<AgentEvent.ToolResult>()
        var round = 0

        val result = ChatToolLoop(tools).run(
            request,
            emptySet(),
            "user",
            complete = { completion ->
                if (round++ == 0) {
                    CompletionResponse(
                        "",
                        listOf(ToolCall("secret-call", "web_search", mapOf("query" to "test")))
                    )
                } else {
                    val observation = completion.messages.last().toolResults.single().output
                    assertFalse(observation.contains("819204"))
                    assertTrue(observation.contains("[REDACTED]"))
                    CompletionResponse("safe answer")
                }
            },
            event = { event ->
                if (event is AgentEvent.ToolResult) events += event
            }
        )

        assertEquals("safe answer", result.content)
        assertTrue(events.isNotEmpty())
        assertFalse(events.last().output.contains("819204"))
        assertTrue(events.last().output.contains("[REDACTED]"))
    }


    @Test fun `image generation executes in chat and reports queued job without repeating creation`() = runTest {
        var calls = 0
        var rounds = 0
        val tools = object : ToolManager {
            override fun getToolDefinitions() = listOf(ToolDefinition("media_generation", "Generate media", listOf(ToolParameter("action", "string", "Action"))))
            override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
                calls++
                return ToolExecutionResult("{\"job_id\":\"local-1\",\"status\":\"queued\"}")
            }
        }
        val mediaRequest = request.copy(messages = listOf(ChatMessage(MessageRole.USER, "Generate an image of a purple sky")))
        val result = ChatToolLoop(tools).run(mediaRequest, emptySet(), "user", complete = {
            assertTrue(it.tools.orEmpty().any { tool -> tool.name == "media_generation" })
            if (rounds++ == 0) CompletionResponse("", listOf(ToolCall("media-1", "media_generation", mapOf("action" to "image"))))
            else CompletionResponse("Your image is generating; the card will update when ready.")
        }, event = {})
        assertEquals(1, calls)
        assertNull(result.request)
        assertTrue(result.content.contains("generating"))
    }
    @Test fun `disabled media capability is not executed from chat`() = runTest {
        var invoked = false
        val tools = object : ToolManager {
            override fun getToolDefinitions() = listOf(ToolDefinition("media_generation", "Generate media"))
            override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
                invoked = true; return ToolExecutionResult("Unexpected")
            }
        }
        var round = 0
        val mediaRequest = request.copy(messages = listOf(ChatMessage(MessageRole.USER, "Draw a picture of a tree")))
        ChatToolLoop(tools).run(mediaRequest, setOf("media_generation"), "user", complete = {
            assertFalse(it.tools.orEmpty().any { tool -> tool.name == "media_generation" })
            if (round++ == 0) CompletionResponse("", listOf(ToolCall("media-1", "media_generation", emptyMap())))
            else CompletionResponse("Media tool is disabled.")
        }, event = {})
        assertFalse(invoked)
    }

}
