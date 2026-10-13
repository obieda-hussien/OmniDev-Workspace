package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.data.tools.*
import com.omnidev.workspace.data.network.PartialCompletionException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChatCompletionRecoveryTest {
    @Test fun `503 retries only completion and preserves completed action`() = runTest {
        var executions = 0
        var attempts = 0
        val tools = object : ToolManager {
            override fun getToolDefinitions() = listOf(ToolDefinition("web_search", "Search"))
            override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
                executions++; return ToolExecutionResult("verified source")
            }
        }
        val events = mutableListOf<AgentEvent>()
        val result = ChatToolLoop(tools).run(CompletionRequest("test", listOf(ChatMessage(MessageRole.USER, "Research"))),
            emptySet(), "user", complete = { request ->
                assertTrue(request.retryManagedByCaller)
                when (attempts++) {
                    0 -> CompletionResponse("", listOf(ToolCall("search", "web_search", emptyMap())))
                    1 -> { assertEquals("verified source", request.messages.last().toolResults.single().output.substringAfterLast("\n")); throw IOException("HTTP 503") }
                    else -> { assertEquals("search", request.messages.last().toolResults.single().toolCallId); CompletionResponse("Answer") }
                }
            }, event = { events += it })
        assertEquals("Answer", result.content)
        assertEquals(1, executions)
        assertEquals(3, attempts)
        assertTrue(events.any { it is AgentEvent.PhaseChanged })
        assertFalse(events.any { it is AgentEvent.Error })
    }

    @Test fun `bounded retry fails after three attempts`() = runTest {
        var attempts = 0
        try { CompletionRetry.run { attempts++; throw IOException("503 unavailable") }; fail("Expected failure") }
        catch (_: IOException) { assertEquals(3, attempts) }
    }

    @Test fun `permanent partial and cancelled responses never retry`() = runTest {
        for (failure in listOf(IOException("HTTP 401"), PartialCompletionException(IOException("503")), CancellationException("stop"))) {
            var attempts = 0
            try { CompletionRetry.run { attempts++; throw failure }; fail("Expected failure") }
            catch (error: Exception) { assertSame(failure, error); assertEquals(1, attempts) }
        }
    }

    @Test fun `explicit compression preserves original history and paired errors`() = runTest {
        val original = listOf(ChatMessage(MessageRole.USER, "Keep the exact goal"),
            ChatMessage(MessageRole.ASSISTANT, "", toolCalls = listOf(ToolCall("old", "web_search", emptyMap()))),
            ChatMessage(MessageRole.TOOL, "x".repeat(6000), toolResults = listOf(ToolCallResult("old", "web_search", "x".repeat(6000), true))))
        val tools = object : ToolManager {
            override fun getToolDefinitions() = listOf(ContextCompressionTool.definition())
            override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?) = ContextCompressionTool.result()
        }
        var round = 0
        ChatToolLoop(tools).run(CompletionRequest("test", original), emptySet(), "user", complete = {
            if (round++ == 0) CompletionResponse("", listOf(ToolCall("compact", "compress_context", emptyMap())))
            else {
                val old = it.messages.first { message -> message.toolResults.any { result -> result.toolCallId == "old" } }.toolResults.single()
                assertTrue(old.isError)
                assertTrue(old.output.length < 1000)
                assertTrue(it.messages.any { message -> message.content == "Keep the exact goal" })
                CompletionResponse("Done")
            }
        }, event = {})
        assertEquals(6000, original.last().toolResults.single().output.length)
    }
}
