package com.omnidev.workspace.data.tools

import com.omnidev.workspace.core.policy.TierPolicyHolder

/**
 * Runtime tier-based filter for the agent-visible tool surface.
 *
 * Legacy execution aliases may remain callable for compatibility, but aliases
 * that duplicate the canonical AgentRuntimeTool are deliberately hidden from
 * function calling so the model has one obvious terminal/package route.
 */
internal object TierToolGate {

    internal val LITE_TOOLS: Set<String> = setOf(
        "web_search",
        "web_search_deep",
        "web_scraper",
        "scrape_multiple",
        "read_file",
        "remember_fact",
        "search_knowledge",
        "update_memory",
        "delete_memory",
        "vector_store",
        "vector_search",
        "vector_similar",
        "brain_reflexion_search",
        "brain_episode_search",
        "brain_recent_episodes",
        "brain_stats",
        "causal_plan_analyze",
        "causal_plan_simulate",
        "causal_plan_what_if",
        "causal_plan_clear",
        "get_trust_profile",
        "list_earned_capabilities",
        "eval_expression"
    )

    internal val PRO_ONLY_TOOLS: Set<String> = setOf(
        "shizuku_command",
        "advanced_security_analyzer",
        "vuln_research_toolchain",
        "android_security_research",
        "android_vuln_research",
        "god_eye_profiler",
        "advanced_root_shell",
        "ui_replica_pipeline",
        "vpn_control"
    )

    internal val SHIZUKU_DEPENDENT_TOOLS: Set<String> = setOf(
        "shizuku_command"
    )

    /**
     * Old public surfaces now superseded by `agent_runtime` + the unified runtime
     * coordinator. They stay executable through CompositeToolManager so stored
     * prompts/workflows do not break, but are no longer advertised to the model.
     */
    internal val HIDDEN_LEGACY_TOOL_ALIASES: Set<String> = setOf(
        "direct_terminal",
        "termux_bridge",
        "advanced_terminal",
        "setup_build_environment"
    )

    fun filter(defs: List<ToolDefinition>): List<ToolDefinition> {
        val visible = defs.filterNot { it.name in HIDDEN_LEGACY_TOOL_ALIASES }
        val policy = TierPolicyHolder.current
        return when (policy.tier) {
            "LITE"          -> visible.filter { it.name in LITE_TOOLS }
            "NORM"          -> visible.filterNot { it.name in PRO_ONLY_TOOLS }
            "PRO"           -> visible
            "OEM"           -> visible.filterNot { it.name in SHIZUKU_DEPENDENT_TOOLS }
            "ADMIN"         -> visible
            "UNINITIALIZED" -> visible.filter { it.name in LITE_TOOLS }
            else            -> visible
        }
    }

    fun denyReason(toolName: String): String? {
        val policy = TierPolicyHolder.current
        return when (policy.tier) {
            "LITE" -> if (toolName in LITE_TOOLS) null
                      else "The Lite tier exposes only the web-browsing toolkit plus the Persistent Memory & Vector Knowledge Base tools. Upgrade to Standard/Pro for this capability."

            "NORM" -> if (toolName !in PRO_ONLY_TOOLS) null
                      else "This tool requires the Pro tier (Shizuku / root / deep-security capability)."

            "OEM" -> if (toolName !in SHIZUKU_DEPENDENT_TOOLS) null
                     else "OEM builds use system-uid privilege and do not ship the Shizuku bridge."

            "ADMIN" -> null

            "UNINITIALIZED" ->
                "Tier policy has not been installed yet — refusing tool execution for safety."

            else -> null
        }
    }
}
