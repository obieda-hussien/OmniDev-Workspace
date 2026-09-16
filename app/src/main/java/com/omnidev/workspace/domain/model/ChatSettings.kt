package com.omnidev.workspace.domain.model

/**
 * Per-conversation settings that control which tools are offered to the agent
 * and how they are made available, mirroring Claude AI's "Add to chat" panel.
 *
 * @property webSearchEnabled      Whether the `web_search` tool is active.
 * @property deepResearchEnabled   Whether the `web_search_deep` tool is active.
 * @property fetchPageEnabled      Whether the `web_scraper` / `scrape_multiple` tools are active.
 * @property toolAccessMode        How tools are exposed to the agent (see [ToolAccessMode]).
 */
data class ChatSettings(
    val webSearchEnabled: Boolean = true,
    val deepResearchEnabled: Boolean = true,
    val fetchPageEnabled: Boolean = true,
    val toolAccessMode: ToolAccessMode = ToolAccessMode.ON_DEMAND
) {
    /**
     * Returns the set of tool names that should be hidden from the agent given the
     * current toggle state. The names match the ToolDefinition.name values.
     */
    fun disabledToolNames(): Set<String> = buildSet {
        if (!webSearchEnabled) add("web_search")
        if (!deepResearchEnabled || !webSearchEnabled) add("web_search_deep")
        if (!fetchPageEnabled) {
            add("fetch_page")
            add("web_scraper")
            add("scrape_multiple")
        }
    }
}

/**
 * Controls how tool definitions are surfaced to the agent.
 *
 * - [ON_DEMAND]        Recommended/default. Function schemas remain available to the
 *                      model but the huge prose copy of every tool is omitted from the
 *                      system prompt. This materially reduces repeated input tokens.
 * - [ALWAYS_AVAILABLE] Duplicates detailed tool prose into the system prompt. Useful only
 *                      for models that struggle to infer function schemas.
 * - [AUTO]             Legacy persisted value. It is migrated to [ON_DEMAND] on read.
 */
enum class ToolAccessMode(val label: String, val subtitle: String) {
    AUTO(
        label = "Auto (legacy)",
        subtitle = "Migrates to optimized on-demand mode"
    ),
    ON_DEMAND(
        label = "On demand",
        subtitle = "Recommended · lower context cost, tools still callable"
    ),
    ALWAYS_AVAILABLE(
        label = "Always available",
        subtitle = "Verbose tool descriptions in every model request"
    );

    companion object {
        fun fromKey(key: String): ToolAccessMode = when (key) {
            AUTO.name -> ON_DEMAND
            else -> entries.firstOrNull { it.name == key } ?: ON_DEMAND
        }
    }
}
