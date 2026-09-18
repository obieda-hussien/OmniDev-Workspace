package com.omnidev.workspace.data.tools

import com.omnidev.workspace.OmniDevApp
import com.omnidev.workspace.core.policy.TierPolicyHolder
import com.omnidev.workspace.data.skills.ChatCapabilityStore
import com.omnidev.workspace.domain.model.ToolAccessMode

/**
 * Runtime filter for both build-tier policy and the active chat's Add-to-chat policy.
 */
internal object TierToolGate {

    internal val LITE_TOOLS: Set<String> = setOf(
        "web_search", "web_search_deep", "web_scraper", "scrape_multiple", "read_file",
        "remember_fact", "search_knowledge", "update_memory", "delete_memory",
        "vector_store", "vector_search", "vector_similar", "brain_reflexion_search",
        "brain_episode_search", "brain_recent_episodes", "brain_stats",
        "causal_plan_analyze", "causal_plan_simulate", "causal_plan_what_if",
        "causal_plan_clear", "get_trust_profile", "list_earned_capabilities", "eval_expression"
    )

    internal val PRO_ONLY_TOOLS: Set<String> = setOf(
        "shizuku_command", "advanced_security_analyzer", "vuln_research_toolchain",
        "android_security_research", "android_vuln_research", "god_eye_profiler",
        "advanced_root_shell", "ui_replica_pipeline", "vpn_control"
    )

    internal val SHIZUKU_DEPENDENT_TOOLS: Set<String> = setOf("shizuku_command")

    internal val HIDDEN_LEGACY_TOOL_ALIASES: Set<String> = setOf(
        "direct_terminal", "execute_terminal_command", "termux_bridge",
        "advanced_terminal", "setup_build_environment"
    )

    private fun chatPolicy(): ChatCapabilityStore.Snapshot? = runCatching {
        ChatCapabilityStore.read(OmniDevApp.instance.applicationContext)
    }.getOrNull()

    fun filter(defs: List<ToolDefinition>): List<ToolDefinition> {
        val chat = chatPolicy()
        if (chat?.toolAccessMode == ToolAccessMode.DISABLED) return emptyList()

        var visible = defs.filterNot { it.name in HIDDEN_LEGACY_TOOL_ALIASES }
        if (chat?.headlessBrowserEnabled == false) {
            visible = visible.filterNot { it.name in ChatCapabilityStore.headlessToolNames }
        }

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
        val chat = chatPolicy()
        if (chat?.toolAccessMode == ToolAccessMode.DISABLED) {
            return "Tools are disabled for this chat from Add to chat."
        }
        if (chat?.headlessBrowserEnabled == false && toolName in ChatCapabilityStore.headlessToolNames) {
            return "Headless browser capability is disabled for this chat from Add to chat."
        }

        val policy = TierPolicyHolder.current
        return when (policy.tier) {
            "LITE" -> if (toolName in LITE_TOOLS) null
            else "The Lite tier exposes only the web-browsing toolkit plus the Persistent Memory & Vector Knowledge Base tools. Upgrade to Standard/Pro for this capability."

            "NORM" -> if (toolName !in PRO_ONLY_TOOLS) null
            else "This tool requires the Pro tier (Shizuku / root / deep-security capability)."

            "OEM" -> if (toolName !in SHIZUKU_DEPENDENT_TOOLS) null
            else "OEM builds use system-uid privilege and do not ship the Shizuku bridge."

            "ADMIN" -> null
            "UNINITIALIZED" -> "Tier policy has not been installed yet — refusing tool execution for safety."
            else -> null
        }
    }
}
