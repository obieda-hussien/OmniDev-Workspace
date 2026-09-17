package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.model.TokenUsage
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
}
