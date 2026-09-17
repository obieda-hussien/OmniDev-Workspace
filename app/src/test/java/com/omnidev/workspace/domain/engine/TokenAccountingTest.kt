package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.model.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenAccountingTest {

    @Test
    fun `native provider usage is authoritative`() {
        val request = CompletionRequest(
            modelId = "test",
            messages = listOf(ChatMessage(MessageRole.USER, "hello")),
            systemPrompt = "system"
        )
        val response = CompletionResponse(
            content = "answer",
            tokensUsed = TokenUsage(promptTokens = 123, completionTokens = 45, totalTokens = 168)
        )

        val usage = TokenAccounting.usage(request, response)

        assertEquals(123, usage.inputTokens)
        assertEquals(45, usage.outputTokens)
        assertEquals(168, usage.totalTokens)
        assertFalse(usage.estimated)
    }

    @Test
    fun `missing usage metadata receives nonzero conservative estimate`() {
        val request = CompletionRequest(
            modelId = "test",
            messages = listOf(
                ChatMessage(MessageRole.USER, "Refactor the repository and verify the migration".repeat(20))
            ),
            systemPrompt = "You are an engineering agent. ".repeat(50)
        )
        val response = CompletionResponse(content = "Implemented and verified changes. ".repeat(30))

        val usage = TokenAccounting.usage(request, response)

        assertTrue(usage.estimated)
        assertTrue(usage.inputTokens > 100)
        assertTrue(usage.outputTokens > 20)
        assertEquals(usage.inputTokens + usage.outputTokens, usage.totalTokens)
    }

    @Test
    fun `larger request produces larger fallback estimate`() {
        val small = CompletionRequest(
            modelId = "test",
            messages = listOf(ChatMessage(MessageRole.USER, "read file"))
        )
        val large = CompletionRequest(
            modelId = "test",
            messages = listOf(ChatMessage(MessageRole.USER, "read and analyze file ".repeat(200)))
        )

        assertTrue(TokenAccounting.estimateInputTokens(large) > TokenAccounting.estimateInputTokens(small))
    }
}
