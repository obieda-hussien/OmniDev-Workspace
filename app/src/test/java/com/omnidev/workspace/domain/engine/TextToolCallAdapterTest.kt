package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.*
import org.junit.Assert.*
import org.junit.Test

class TextToolCallAdapterTest {
    private val envelope = """{"omni_tool_call":{"name":"read_file","arguments":{"path":"a.kt"}}}"""
    @Test fun `exact whole response envelope becomes a proposed call`() {
        val adapted = TextToolCallAdapter.adapt(CompletionResponse(envelope))
        assertEquals("read_file", adapted.toolCalls.single().name)
        assertEquals("a.kt", adapted.toolCalls.single().arguments["path"])
        assertNull(adapted.toolCalls.single().argumentError)
    }
    @Test fun `prose and ordinary JSON are never scanned for commands`() {
        for (raw in listOf("Example: $envelope", "Do this $envelope now", "{\"name\":\"delete_file\"}", "{broken}")) {
            assertTrue(TextToolCallAdapter.adapt(CompletionResponse(raw)).toolCalls.isEmpty())
        }
    }
    @Test fun `native call remains authoritative and provider metadata survives`() {
        val original = CompletionResponse(envelope, listOf(ToolCall("x", "web_search", emptyMap())))
        assertSame(original, TextToolCallAdapter.adapt(original))
    }
    @Test fun `malformed attempted envelope requests correction instead of becoming a final answer`() {
        for (raw in listOf("""{"omni_tool_call":broken}""", """{"omni_tool_call":null}""",
            """{"omni_tool_call":{"name":42,"arguments":{}}}""")) {
            assertNotNull(TextToolCallAdapter.adapt(CompletionResponse(raw)).toolCalls.single().argumentError)
        }
    }
    @Test fun `null arguments and extra envelope keys are refused by preflight`() {
        for (raw in listOf("""{"omni_tool_call":{"name":"erase","arguments":null}}""",
            """{"omni_tool_call":{"name":"erase","arguments":{},"confirmation":true}}""")) {
            assertNotNull(TextToolCallAdapter.adapt(CompletionResponse(raw)).toolCalls.single().argumentError)
        }
    }
    @Test fun `plain model history has observations and arguments without native roles`() {
        val history = ToolTextProtocol.messages(listOf(
            ChatMessage(MessageRole.ASSISTANT, "", toolCalls = listOf(ToolCall("1", "read_file", mapOf("path" to "a.kt")))),
            ChatMessage(MessageRole.TOOL, "", toolResults = listOf(ToolCallResult("1", "read_file", "value")))
        ))
        assertTrue(history.all { it.toolCalls.isEmpty() && it.toolResults.isEmpty() && it.role != MessageRole.TOOL })
        assertTrue(history.first().content.contains("a.kt"))
        assertTrue(history.last().content.contains("value"))
    }
    @Test fun `text fallback never forges native tool calls and leaves real native metadata intact`() {
        val text = TextToolCallAdapter.adapt(CompletionResponse(envelope)).toolCalls.single()
        val native = ToolCall("native", "web_search", emptyMap(),
            extraContent = kotlinx.serialization.json.Json.parseToJsonElement("{\"signature\":\"opaque\"}") as kotlinx.serialization.json.JsonObject)
        val history = ToolTextProtocol.messages(listOf(
            ChatMessage(MessageRole.ASSISTANT, "", toolCalls = listOf(text)),
            ChatMessage(MessageRole.TOOL, "", toolResults = listOf(ToolCallResult(text.id, text.name, "file"))),
            ChatMessage(MessageRole.ASSISTANT, "", toolCalls = listOf(native)),
            ChatMessage(MessageRole.TOOL, "", toolResults = listOf(ToolCallResult("native", "web_search", "source")))
        ), textCallsOnly = true)
        assertTrue(history[0].toolCalls.isEmpty())
        assertEquals(MessageRole.USER, history[1].role)
        assertEquals(native.extraContent, history[2].toolCalls.single().extraContent)
        assertEquals(MessageRole.TOOL, history[3].role)
    }
}
