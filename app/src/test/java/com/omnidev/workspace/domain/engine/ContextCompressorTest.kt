package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ContextCompressorTest {
    @Test fun oversizedLatestToolResultFitsWithoutChangingCallOrGoal() {
        val goal = ChatMessage(MessageRole.USER, "Keep my exact instructions")
        val call = ChatMessage(MessageRole.ASSISTANT, "", toolCalls = listOf(ToolCall("c1", "read", emptyMap())))
        val reply = ChatMessage(MessageRole.TOOL, "", toolResults = listOf(
            ToolCallResult("c1", "read", "start" + "x".repeat(100_000) + "end")))
        val original = listOf(goal, call, reply)
        val trimmed = ContextCompressor.trim(original, 1000)
        assertTrue(trimmed.sumOf(ContextCompressor::estimatedTokens) <= 1000)
        assertEquals(goal, trimmed.first())
        assertEquals(call, trimmed[1])
        val result = trimmed.last().toolResults.single()
        assertEquals("c1", result.toolCallId)
        assertTrue(result.output.startsWith("start"))
        assertTrue(result.output.endsWith("end"))
        assertTrue(result.output.contains("truncated"))
        assertEquals(100_008, original.last().toolResults.single().output.length)
    }

    @Test fun oversizedUserInputIsNeverSilentlyTruncated() {
        val message = ChatMessage(MessageRole.USER, "x".repeat(10_000))
        assertEquals(listOf(message), ContextCompressor.trim(listOf(message), 100))
    }

    @Test fun compactionUsesCredentialsAndPreservesToolExchange() = runTest {
        val call = ChatMessage(MessageRole.ASSISTANT, "", toolCalls = listOf(ToolCall("c1", "read", emptyMap())))
        val result = ChatMessage(MessageRole.TOOL, "", toolResults = listOf(ToolCallResult("c1", "read", "output")))
        val messages = (listOf(ChatMessage(MessageRole.USER, "original goal")) +
            List(15) { ChatMessage(MessageRole.USER, "history ".repeat(200)) } + call + result).toMutableList()
        var request: CompletionRequest? = null
        var event: AgentEvent.ContextCompaction? = null
        ContextCompressor.checkAndCompact(messages, 4000, "CUSTOM_OPENAI::test", {
            request = it; CompletionResponse("Keep the exact file path and unresolved failure.")
        }, "test-key") { event = it }
        assertEquals("test-key", request?.apiKey)
        assertEquals("original goal", messages.first().content)
        assertTrue(messages.any { it.content.contains("exact file path") })
        assertEquals(listOf(call, result), messages.takeLast(2))
        assertNotNull(event)
    }

    @Test fun fallbackDoesNotClaimEnvironmentWasPreserved() = runTest {
        val messages = MutableList(12) { ChatMessage(MessageRole.USER, "x".repeat(1000)) }
        ContextCompressor.checkAndCompact(messages, 2000, "test", { error("offline") }) {}
        assertTrue(messages[1].content.contains("PARTIAL"))
        assertFalse(messages[1].content.contains("Environment State: Retained"))
    }

    @Test fun cancellationLeavesHistoryIntact() = runTest {
        val messages = MutableList(12) { ChatMessage(MessageRole.USER, "x".repeat(1000)) }
        val original = messages.toList()
        try {
            ContextCompressor.checkAndCompact(messages, 2000, "test", { throw CancellationException() }) {}
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { assertEquals(original, messages) }
    }

    @Test fun trimmingNeverSeparatesToolCallFromReply() {
        val messages = listOf(ChatMessage(MessageRole.USER, "goal"),
            ChatMessage(MessageRole.ASSISTANT, "old", toolCalls = listOf(ToolCall("1", "read", emptyMap()))),
            ChatMessage(MessageRole.TOOL, "result".repeat(1000)), ChatMessage(MessageRole.USER, "continue"))
        val trimmed = ContextCompressor.trim(messages, 100)
        assertEquals(listOf(messages.first(), messages.last()), trimmed)
    }
}
