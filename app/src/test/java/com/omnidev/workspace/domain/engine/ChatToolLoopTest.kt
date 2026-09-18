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
        override fun getToolDefinitions() = (ChatToolLoop.WEB_TOOLS + "delete_file").map { ToolDefinition(it, it) }
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
            override fun getToolDefinitions() = listOf(ToolDefinition("web_search", "search"))
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
            override fun getToolDefinitions() = listOf(ToolDefinition("web_search", "search"))
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


}
