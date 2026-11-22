package com.omnidev.workspace.data.network

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ProviderCompatibilityTest {
    @Test fun `supported Claude models receive bounded sampling settings`() {
        assertEquals(0.7, anthropicTemperature("ANTHROPIC::claude-sonnet-4-6", 0.7)!!, 0.0001)
        assertEquals(0.3, anthropicTemperature("claude-3-5-sonnet-20241022", 0.3)!!, 0.0001)
        assertEquals(1.0, anthropicTemperature("claude-opus-4-5", 1.5)!!, 0.0001)
        assertNull(anthropicTemperature("claude-opus-4-6", Double.NaN))
    }

    @Test fun `new or unknown Claude models omit unsupported sampling`() {
        for (model in listOf("claude-opus-4-7", "claude-opus-4-8", "claude-sonnet-5", "claude-fable-5", "claude-mythos-5", "claude-future")) {
            assertNull(model, anthropicTemperature(model, 0.7))
        }
    }

    @Test fun `retired GitHub Models cannot reach inference boundary`() {
        val failure = runCatching { requireConcreteModel("GITHUB_MODELS::openai/gpt-4o") }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure!!.message.orEmpty().contains("retired"))
        requireConcreteModel("GITHUB_COPILOT::gpt-4o")
    }
}
