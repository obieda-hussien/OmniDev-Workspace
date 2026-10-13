package com.omnidev.workspace.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptSanitizerTest {

    @Test
    fun `removes explicit chain of thought disclosure instructions`() {
        val prompt = """
            You are Omni.
            ## THINKING PROCESS (Chain of Thought)
            Before taking any action, output your internal reasoning:
            - Observation: x
            - Reasoning: y
            - Plan: z
            - Action: tool
            ## CONTINUITY & LEARNING
            Verify changes.
        """.trimIndent()

        val sanitized = AgentPromptSanitizer.sanitizeSystemPrompt(prompt).orEmpty()

        assertFalse(sanitized.contains("output your internal reasoning", ignoreCase = true))
        assertFalse(sanitized.contains("Chain of Thought", ignoreCase = true))
        assertTrue(sanitized.contains("Keep private chain-of-thought internal"))
        assertTrue(sanitized.contains("CONTINUITY"))
    }

    @Test
    fun `thinking content is stripped from replay messages`() {
        val messages = listOf(
            ChatMessage(MessageRole.USER, "hello"),
            ChatMessage(MessageRole.ASSISTANT, "answer", thinkingContent = "private reasoning")
        )

        val sanitized = AgentPromptSanitizer.sanitizeMessages(messages)

        assertNull(sanitized[1].thinkingContent)
        assertTrue(sanitized[1].content == "answer")
    }

    @Test
    fun `completion response never retains raw thinking`() {
        val response = CompletionResponse(content = "done", thinkingContent = "secret thought")
        assertNull(response.thinkingContent)
    }
    @Test fun `request copies keep one policy and preserve appended task focus`() {
        var request = CompletionRequest("test", listOf(ChatMessage(MessageRole.USER, "Question")), systemPrompt = "You are Omni.")
        request = request.copy(systemPrompt = request.systemPrompt + "\nSelected capability: launcher.open_drawer")
        val initialLength = request.systemPrompt!!.length
        repeat(10) { request = request.copy(maxTokens = 500) }
        val prompt = request.systemPrompt!!
        org.junit.Assert.assertEquals(initialLength, prompt.length)
        org.junit.Assert.assertEquals(1, Regex("## OBSERVABLE AGENT POLICY").findAll(prompt).count())
        assertTrue(prompt.contains("Selected capability: launcher.open_drawer"))
        org.junit.Assert.assertEquals(prompt, AgentPromptSanitizer.sanitizeSystemPrompt(prompt))
    }

}
