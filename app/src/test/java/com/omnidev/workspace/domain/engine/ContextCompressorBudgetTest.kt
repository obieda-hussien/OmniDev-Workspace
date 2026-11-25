package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.model.TokenUsage
import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.model.ToolCallResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompressorBudgetTest {

    private fun largeHistory(): MutableList<ChatMessage> =
        MutableList(14) { index ->
            ChatMessage(MessageRole.USER, "history-$index " + "x".repeat(900))
        }

    @Test
    fun `model compaction reports hidden token usage`() = runTest {
        val messages = largeHistory()
        val report = ContextCompressor.checkAndCompact(
            messages = messages,
            tokenBudget = 2_500,
            modelId = "test",
            completionProvider = {
                CompletionResponse(
                    content = "bounded summary",
                    tokensUsed = TokenUsage(promptTokens = 700, completionTokens = 80, totalTokens = 780)
                )
            },
            remainingTokenBudget = 10_000
        ) { }

        assertTrue(report.usedModel)
        assertEquals(780, report.tokensUsed)
        assertTrue(messages.any { it.content.contains("bounded summary") })
    }

    @Test
    fun `low remaining budget switches to local digest without model call`() = runTest {
        val messages = largeHistory()
        var providerCalled = false
        val report = ContextCompressor.checkAndCompact(
            messages = messages,
            tokenBudget = 2_500,
            modelId = "test",
            completionProvider = {
                providerCalled = true
                CompletionResponse("should not happen")
            },
            remainingTokenBudget = 900
        ) { }

        assertFalse(providerCalled)
        assertFalse(report.usedModel)
        assertEquals(0, report.tokensUsed)
        assertTrue(report.degraded)
        assertTrue(messages.any { it.content.contains("PARTIAL") })
    }

    @Test
    fun `large summary request uses local digest when its input exceeds the remaining budget`() = runTest {
        val messages = largeHistory().apply {
            add(1, ChatMessage(MessageRole.SYSTEM, "[COMPACTED HISTORY]\n" + "earlier fact ".repeat(400)))
        }
        val latest = messages.last()
        var providerCalled = false
        val report = ContextCompressor.checkAndCompact(
            messages = messages,
            tokenBudget = 24_000,
            modelId = "test",
            completionProvider = {
                providerCalled = true
                CompletionResponse("request exceeded the budget")
            },
            remainingTokenBudget = 2_400,
            force = true
        ) { }

        assertFalse(providerCalled)
        assertEquals(0, report.tokensUsed)
        assertTrue(report.degraded)
        assertEquals(latest, messages.last())
    }

    @Test
    fun `summary usage fallback counts the full request`() = runTest {
        val messages = largeHistory()
        var expectedUsage = 0
        val report = ContextCompressor.checkAndCompact(
            messages = messages,
            tokenBudget = 2_500,
            modelId = "test",
            completionProvider = { request ->
                CompletionResponse("bounded summary").also {
                    expectedUsage = TokenAccounting.usage(request, it).totalTokens
                }
            },
            remainingTokenBudget = 10_000
        ) { }

        assertTrue(report.usedModel)
        assertEquals(expectedUsage, report.tokensUsed)
    }
    @Test
    fun `rolling tool compaction keeps latest observation full and shrinks older dumps`() {
        val oldDump = "old-evidence-" + "x".repeat(8_000)
        val latestDump = "latest-evidence-" + "y".repeat(8_000)
        val messages = mutableListOf(
            ChatMessage(MessageRole.USER, "Find the answer"),
            ChatMessage(
                MessageRole.ASSISTANT,
                "",
                toolCalls = listOf(ToolCall("c1", "run_terminal", mapOf("command" to "content query old")))
            ),
            ChatMessage(
                MessageRole.TOOL,
                oldDump,
                toolResults = listOf(ToolCallResult("c1", "run_terminal", oldDump, false))
            ),
            ChatMessage(
                MessageRole.ASSISTANT,
                "",
                toolCalls = listOf(ToolCall("c2", "run_terminal", mapOf("command" to "content query latest")))
            ),
            ChatMessage(
                MessageRole.TOOL,
                latestDump,
                toolResults = listOf(ToolCallResult("c2", "run_terminal", latestDump, false))
            )
        )

        ContextCompressor.compactHistoricalToolEvidence(messages, keepRecentToolGroups = 1)

        val toolMessages = messages.filter { it.role == MessageRole.TOOL }
        assertEquals(2, toolMessages.size)
        assertTrue(toolMessages[0].content.length < 1_000)
        assertTrue(toolMessages[0].toolResults.single().output.length < 1_000)
        assertEquals(latestDump, toolMessages[1].content)
        assertEquals(latestDump, toolMessages[1].toolResults.single().output)
    }


}
