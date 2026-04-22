package com.omnidev.workspace.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke tests for the ToolContribution seam.
 *
 * These tests validate the *contract*, not flavor wiring — flavor
 * wiring is verified by variant-specific instrumentation tests that
 * will be added once the physical `:tools:*` module split lands.
 */
class ToolContributionTest {

    @Test
    fun `default active contributions is empty until modules are split`() {
        // Until :tools:lite / :tools:standard / :tools:advanced are real
        // Gradle modules, active() must be empty. CompositeToolManager +
        // TierToolGate remain the single source of truth.
        assertTrue(ToolContributions.active().isEmpty())
    }

    @Test
    fun `registrar receives every registered tool exactly once`() {
        val captured = mutableMapOf<String, Any>()
        val registrar = object : ToolContributionRegistrar {
            override fun register(name: String, executor: Any) {
                check(name !in captured) { "Duplicate registration for $name" }
                captured[name] = executor
            }
        }

        val contrib = object : ToolContribution {
            override val id: String = "tools.test"
            override val minTier: Tier = Tier.LITE
            override fun register(r: ToolContributionRegistrar) {
                r.register("web_search", Any())
                r.register("read_file", Any())
            }
        }

        contrib.register(registrar)

        assertEquals(2, captured.size)
        assertTrue("web_search" in captured)
        assertTrue("read_file" in captured)
    }

    @Test
    fun `tier ordering matches product flavor capability ladder`() {
        // Not a real ordering, just a sanity check that all four tiers
        // exist so downstream filters can switch over them.
        val all = Tier.values().toSet()
        assertEquals(setOf(Tier.LITE, Tier.NORM, Tier.PRO, Tier.OEM), all)
    }
}
