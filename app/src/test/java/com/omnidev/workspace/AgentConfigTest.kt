package com.omnidev.workspace

import com.omnidev.workspace.domain.engine.AgentConfig
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [AgentConfig] presets and validation.
 */
class AgentConfigTest {

    @Test
    fun `default config has reasonable values`() {
        val config = AgentConfig()
        assertTrue("Default max iterations should be positive", config.maxIterations > 0)
        assertTrue("Default max retries should be non-negative", config.maxRetries >= 0)
        assertTrue("Default base retry delay should be positive", config.baseRetryDelayMs > 0)
        assertTrue("Context window buffer should be positive", config.contextWindowBuffer > 0)
    }

    @Test
    fun `BUDGET preset has restricted iteration and token limits`() {
        val budget = AgentConfig.BUDGET
        assertTrue("BUDGET max iterations should be <= 10", budget.maxIterations <= 10)
        assertNotNull("BUDGET should have a token budget", budget.tokenBudget)
        assertTrue("BUDGET token budget should be positive", budget.tokenBudget!! > 0)
        assertFalse("BUDGET should not retry", budget.enableRetry)
    }

    @Test
    fun `THOROUGH preset has higher iteration limits than default`() {
        val thorough = AgentConfig.THOROUGH
        assertTrue(
            "THOROUGH max iterations should exceed default",
            thorough.maxIterations > AgentConfig().maxIterations
        )
        assertTrue(
            "THOROUGH max retries should be >= default",
            thorough.maxRetries >= AgentConfig().maxRetries
        )
    }

    @Test
    fun `INLINE preset has maxIterations of 1`() {
        val inline = AgentConfig.INLINE
        assertEquals("INLINE max iterations should be 1", 1, inline.maxIterations)
        assertFalse("INLINE should not retry", inline.enableRetry)
    }
}
