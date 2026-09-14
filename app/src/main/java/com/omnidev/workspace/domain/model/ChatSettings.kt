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
    val deepResearchEnabled: Boolean = false,
    val fetchPageEnabled: Boolean = true,
    val toolAccessMode: ToolAccessMode = ToolAccessMode.AUTO
) {
    /**
     * Returns the set of tool names that should be hidden from the agent given the
     * current toggle state.  The names match the `ToolDefinition.name` values in
     * [WebSearchTool] and [WebScraperTool].
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
 * - [AUTO]             The agent chooses which tools to call (default — current behavior).
 * - [ON_DEMAND]        Tool definitions are registered but NOT described in the system prompt.
 *                      The model must infer or request them.  Produces more messages but uses
 *                      fewer tokens per turn.
 * - [ALWAYS_AVAILABLE] Tools are fully described in the system prompt from the very first turn.
 *                      Fewer messages are required; better accuracy on tool-heavy tasks.
 */
enum class ToolAccessMode(val label: String, val subtitle: String) {
    AUTO(
        label = "Auto",
        subtitle = "Agent chooses for you"
    ),
    ON_DEMAND(
        label = "On demand",
        subtitle = "Load when needed. More messages, lower accuracy"
    ),
    ALWAYS_AVAILABLE(
        label = "Always available",
        subtitle = "Ready from start. Fewer messages, better accuracy"
    );

    companion object {
        fun fromKey(key: String): ToolAccessMode =
            entries.firstOrNull { it.name == key } ?: AUTO
    }
}
