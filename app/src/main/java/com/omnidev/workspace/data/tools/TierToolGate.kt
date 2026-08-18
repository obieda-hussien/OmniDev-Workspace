package com.omnidev.workspace.data.tools

import com.omnidev.workspace.core.policy.TierPolicyHolder

/**
 * Runtime tier-based filter that decides which tools are **visible** to the agent
 * (via [com.omnidev.workspace.data.tools.ToolManager.getToolDefinitions]) and
 * which are **executable** (via [com.omnidev.workspace.data.tools.ToolManager.executeTool]).
 *
 * This is a transitional runtime enforcement. Once the
 * [com.omnidev.workspace.MODULARIZATION_ROADMAP.md] follow-up PR has physically
 * relocated the tools into `:tools:lite`, `:tools:standard`, and
 * `:tools:advanced`, this class becomes redundant for LITE / NORM builds (those
 * variants won't even link against the advanced tools). For now it ensures the
 * correct tool set at runtime for every variant.
 *
 * ## Filter rules
 *
 * | Tier  | Visible tools                                                                          |
 * |-------|----------------------------------------------------------------------------------------|
 * | LITE  | Web tools + `read_file` + **Persistent Memory & Vector Knowledge Base** tools          |
 * |       | (`remember_fact`, `search_knowledge`, `update_memory`, `delete_memory`,                |
 * |       |  `vector_store`, `vector_search`, `vector_similar`).                                   |
 * | NORM  | Everything except pro-only tools (Shizuku commands, deep security, vuln research).     |
 * | PRO   | Every tool (subject to per-tool runtime capability checks).                            |
 * | OEM   | Every tool EXCEPT the Shizuku-centric ones (OEM uses android.uid.system, not Shizuku). |
 * | ADMIN | Master-key: every tool, no filter, no denial.                                          |
 */
internal object TierToolGate {

    /**
     * Exact tool names exposed to the LITE flavor.
     *
     * Lite ships the canonical safe web-browsing toolkit plus the Persistent
     * Memory / Vector Knowledge Base so that even the Play-Store-safe consumer
     * build can maintain long-term context and perform semantic recall
     * entirely on-device (SQLite + cosine similarity — no privileged APIs).
     */
    internal val LITE_TOOLS: Set<String> = setOf(
        // Web browsing (pre-existing)
        "web_search",
        "web_search_deep",
        "web_scraper",
        "scrape_multiple",       // companion of web_scraper; sibling variant
        "read_file",

        // ── Persistent Memory / Knowledge Base (on-device SQLite, Play-safe) ──
        "remember_fact",
        "search_knowledge",
        "update_memory",
        "delete_memory",

        // ── Vector Knowledge Base (cosine similarity, all local) ──────────────
        "vector_store",
        "vector_search",
        "vector_similar",

        // ── Agent Brain 2.0 (on-device, no privileges, mobile-friendly) ──
        // Context note read-only Context note Context note Context note Context note Context note Context note Lite/Play-Store
        "brain_reflexion_search",
        "brain_episode_search",
        "brain_recent_episodes",
        "brain_stats",

        // ── Causal Chain Planner (on-device analysis, no side effects) ────────
        // Context note Context note Context note — Context note Context note Context note Context note Context note Context note Tiers
        "causal_plan_analyze",
        "causal_plan_simulate",
        "causal_plan_what_if",
        "causal_plan_clear",

        // ── Progressive Trust (read-only — safe for all tiers) ───────────────
        "get_trust_profile",
        "list_earned_capabilities",
        // reset_trust is intentionally omitted from Lite (destructive)

        // ── eval_expression (pure math — no side effects, safe for Lite) ─────
        "eval_expression"
    )

    /** Tools that require PRO-tier capabilities (Shizuku / root / deep-security). */
    internal val PRO_ONLY_TOOLS: Set<String> = setOf(
        "shizuku_command",
        "advanced_security_analyzer",
        "vuln_research_toolchain",
        "android_security_research",
        "android_vuln_research",
        "god_eye_profiler",
        "advanced_root_shell",
        "ui_replica_pipeline",     // root-assisted variants only; kept off NORM
        "vpn_control"              // privileged network tool
    )

    /** Tools that rely specifically on Shizuku and have no OEM equivalent. */
    internal val SHIZUKU_DEPENDENT_TOOLS: Set<String> = setOf(
        "shizuku_command"
    )

    /**
     * Returns the subset of [defs] that the active tier is allowed to see.
     * The ordering of the input list is preserved.
     */
    fun filter(defs: List<ToolDefinition>): List<ToolDefinition> {
        val policy = TierPolicyHolder.current
        return when (policy.tier) {
            "LITE"          -> defs.filter { it.name in LITE_TOOLS }
            "NORM"          -> defs.filterNot { it.name in PRO_ONLY_TOOLS }
            "PRO"           -> defs
            "OEM"           -> defs.filterNot { it.name in SHIZUKU_DEPENDENT_TOOLS }
            "ADMIN"         -> defs   // Master key: every tool, unfiltered.
            "UNINITIALIZED" -> defs.filter { it.name in LITE_TOOLS } // fail-safe to Lite
            else            -> defs
        }
    }

    /**
     * Returns a human-readable reason string if [toolName] is NOT allowed in the
     * current tier, or `null` if it is permitted. Intended for use at the top of
     * [com.omnidev.workspace.data.tools.ToolManager.executeTool] to short-circuit
     * forbidden calls before they produce any side effect.
     */
    fun denyReason(toolName: String): String? {
        val policy = TierPolicyHolder.current
        return when (policy.tier) {
            "LITE" -> if (toolName in LITE_TOOLS) null
                      else "The Lite tier exposes only the web-browsing toolkit plus the Persistent Memory & Vector Knowledge Base tools. Upgrade to Standard/Pro for this capability."

            "NORM" -> if (toolName !in PRO_ONLY_TOOLS) null
                      else "This tool requires the Pro tier (Shizuku / root / deep-security capability)."

            "OEM"  -> if (toolName !in SHIZUKU_DEPENDENT_TOOLS) null
                      else "OEM builds use system-uid privilege and do not ship the Shizuku bridge."

            // ADMIN is the master-key build — every tool is permitted.
            "ADMIN" -> null

            "UNINITIALIZED" ->
                "Tier policy has not been installed yet — refusing tool execution for safety."

            // PRO (and any unknown future tier) permits everything.
            else   -> null
        }
    }
}
