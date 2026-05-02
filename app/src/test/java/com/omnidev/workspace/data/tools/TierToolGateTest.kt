package com.omnidev.workspace.data.tools

import com.omnidev.workspace.core.policy.ConfirmationGate
import com.omnidev.workspace.core.policy.TierPolicy
import com.omnidev.workspace.core.policy.TierPolicyHolder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TierToolGateTest {

    @After
    fun tearDown() {
        // Leave the holder in a known no-op state between tests.
        TierPolicyHolder.install(stubPolicy("UNINITIALIZED"))
    }

    private fun stubPolicy(name: String) = object : TierPolicy {
        override val tier: String = name
        override val allowRoot = false
        override val allowShizuku = false
        override val allowAccessibility = false
        override val allowDeepSecurity = false
        override val allowDeviceAdminWipe = false
        override val allowLocalSlm = false
        override val allowSystemIntegration = false
        override val autoApproveConfirmations = false
        override fun confirmationGate(uiGate: ConfirmationGate): ConfirmationGate = uiGate
    }

    private val fullDefs = listOf(
        ToolDefinition("web_search", "", emptyList()),
        ToolDefinition("web_search_deep", "", emptyList()),
        ToolDefinition("web_scraper", "", emptyList()),
        ToolDefinition("scrape_multiple", "", emptyList()),
        ToolDefinition("read_file", "", emptyList()),
        ToolDefinition("remember_fact", "", emptyList()),
        ToolDefinition("search_knowledge", "", emptyList()),
        ToolDefinition("update_memory", "", emptyList()),
        ToolDefinition("delete_memory", "", emptyList()),
        ToolDefinition("vector_store", "", emptyList()),
        ToolDefinition("vector_search", "", emptyList()),
        ToolDefinition("vector_similar", "", emptyList()),
        // Agent Brain 2.0 — on-device, read-only, safe for Lite/Play-Store
        ToolDefinition("brain_reflexion_search", "", emptyList()),
        ToolDefinition("brain_episode_search", "", emptyList()),
        ToolDefinition("brain_recent_episodes", "", emptyList()),
        ToolDefinition("brain_stats", "", emptyList()),
        // Causal Chain Planner — on-device analysis, no side effects, safe for Lite
        ToolDefinition("causal_plan_analyze", "", emptyList()),
        ToolDefinition("causal_plan_simulate", "", emptyList()),
        ToolDefinition("causal_plan_what_if", "", emptyList()),
        ToolDefinition("causal_plan_clear", "", emptyList()),
        ToolDefinition("terminal_command", "", emptyList()),
        ToolDefinition("git_status", "", emptyList()),
        ToolDefinition("semantic_ui_action", "", emptyList()),
        ToolDefinition("shizuku_command", "", emptyList()),
        ToolDefinition("advanced_security_analyzer", "", emptyList()),
        ToolDefinition("vuln_research_toolchain", "", emptyList()),
        ToolDefinition("vpn_control", "", emptyList()),
        ToolDefinition("god_eye_profiler", "", emptyList())
    )

    @Test
    fun `lite tier exposes only the 4 approved tools plus scrape_multiple companion`() {
        TierPolicyHolder.install(stubPolicy("LITE"))
        val filtered = TierToolGate.filter(fullDefs).map { it.name }.toSet()
        assertEquals(TierToolGate.LITE_TOOLS, filtered)
    }

    @Test
    fun `lite tier denies every non-Lite tool at executeTool entry`() {
        TierPolicyHolder.install(stubPolicy("LITE"))
        assertNull("web_search should pass", TierToolGate.denyReason("web_search"))
        assertNull("read_file should pass",  TierToolGate.denyReason("read_file"))
        assertNotNull("shizuku_command must be denied",         TierToolGate.denyReason("shizuku_command"))
        assertNotNull("terminal_command must be denied",        TierToolGate.denyReason("terminal_command"))
        assertNotNull("semantic_ui_action must be denied",      TierToolGate.denyReason("semantic_ui_action"))
        assertNotNull("advanced_security_analyzer must be denied",
                      TierToolGate.denyReason("advanced_security_analyzer"))
    }

    @Test
    fun `norm tier strips pro-only tools but keeps everything else`() {
        TierPolicyHolder.install(stubPolicy("NORM"))
        val filtered = TierToolGate.filter(fullDefs).map { it.name }.toSet()
        assertTrue("Norm must keep web_search",         "web_search"          in filtered)
        assertTrue("Norm must keep terminal_command",   "terminal_command"    in filtered)
        assertTrue("Norm must keep semantic_ui_action", "semantic_ui_action"  in filtered)
        assertTrue("Norm must keep git_status",         "git_status"          in filtered)
        // Pro-only:
        assertTrue("Norm must drop shizuku_command",              "shizuku_command"           !in filtered)
        assertTrue("Norm must drop advanced_security_analyzer",   "advanced_security_analyzer" !in filtered)
        assertTrue("Norm must drop vuln_research_toolchain",      "vuln_research_toolchain"    !in filtered)
        assertTrue("Norm must drop vpn_control",                  "vpn_control"                !in filtered)

        assertNotNull(TierToolGate.denyReason("shizuku_command"))
        assertNull(TierToolGate.denyReason("semantic_ui_action"))
    }

    @Test
    fun `pro tier exposes every tool unchanged`() {
        TierPolicyHolder.install(stubPolicy("PRO"))
        val filtered = TierToolGate.filter(fullDefs)
        assertEquals(fullDefs.size, filtered.size)
        for (def in fullDefs) {
            assertNull("Pro must allow ${def.name}", TierToolGate.denyReason(def.name))
        }
    }

    @Test
    fun `oem tier exposes everything except Shizuku-specific tools`() {
        TierPolicyHolder.install(stubPolicy("OEM"))
        val filtered = TierToolGate.filter(fullDefs).map { it.name }.toSet()
        assertTrue("OEM keeps advanced_security_analyzer (android.uid.system)",
                   "advanced_security_analyzer" in filtered)
        assertTrue("OEM keeps vpn_control",       "vpn_control"       in filtered)
        assertTrue("OEM keeps god_eye_profiler",  "god_eye_profiler"  in filtered)
        assertTrue("OEM drops shizuku_command",   "shizuku_command"   !in filtered)
        assertNotNull(TierToolGate.denyReason("shizuku_command"))
        assertNull(TierToolGate.denyReason("advanced_security_analyzer"))
    }

    @Test
    fun `uninitialised tier fails safe to Lite behaviour`() {
        TierPolicyHolder.install(stubPolicy("UNINITIALIZED"))
        val filtered = TierToolGate.filter(fullDefs).map { it.name }.toSet()
        assertEquals(TierToolGate.LITE_TOOLS, filtered)
        assertNotNull(TierToolGate.denyReason("terminal_command"))
    }
}
