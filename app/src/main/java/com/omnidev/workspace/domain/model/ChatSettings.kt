package com.omnidev.workspace.domain.model

/**
 * Capability policy attached to the active chat.
 *
 * The UI intentionally separates general tool exposure from Agent Skills: a user
 * can keep normal tools on-demand while pinning one or more skills into context,
 * or disable tools/skills independently.
 */
data class ChatSettings(
    val webSearchEnabled: Boolean = true,
    val deepResearchEnabled: Boolean = true,
    val fetchPageEnabled: Boolean = true,
    val headlessBrowserEnabled: Boolean = true,
    val toolAccessMode: ToolAccessMode = ToolAccessMode.ON_DEMAND,
    val skillAccessMode: SkillAccessMode = SkillAccessMode.ON_DEMAND,
    /** true = every globally enabled skill is eligible in this chat. */
    val useAllEnabledSkills: Boolean = true,
    /** Used only when [useAllEnabledSkills] is false. Names are canonical skill IDs. */
    val selectedSkillNames: Set<String> = emptySet()
) {
    /** Tool names hidden from the model for this chat. */
    fun disabledToolNames(): Set<String> = buildSet {
        if (!webSearchEnabled) {
            add("web_search")
            add("web_search_deep")
        } else if (!deepResearchEnabled) {
            add("web_search_deep")
        }

        if (!fetchPageEnabled) {
            add("fetch_page")
            add("web_scraper")
            add("scrape_multiple")
        }

        if (!headlessBrowserEnabled) {
            add("headless_browser")
            add("browser_navigate")
            add("browser_execute_js")
            add("browser_get_dom")
            add("browser_click")
            add("browser_type")
            add("browser_screenshot")
        }
    }

    fun isSkillAllowed(name: String): Boolean =
        skillAccessMode != SkillAccessMode.DISABLED &&
            (useAllEnabledSkills || name.trim().lowercase() in selectedSkillNames)
}

/** How native tools are exposed to the model for the active chat. */
enum class ToolAccessMode(val label: String, val subtitle: String) {
    DISABLED(
        label = "Off",
        subtitle = "No tools are exposed in this chat"
    ),
    AUTO(
        label = "Auto (legacy)",
        subtitle = "Migrates to optimized on-demand mode"
    ),
    ON_DEMAND(
        label = "On demand",
        subtitle = "Recommended · tools are callable without duplicating verbose docs"
    ),
    ALWAYS_AVAILABLE(
        label = "Always available",
        subtitle = "Keep detailed tool guidance loaded in every request"
    );

    companion object {
        fun fromKey(key: String): ToolAccessMode = when (key) {
            AUTO.name -> ON_DEMAND
            else -> entries.firstOrNull { it.name == key } ?: ON_DEMAND
        }
    }
}

/** How selected Agent Skills are hydrated into this chat. */
enum class SkillAccessMode(val label: String, val subtitle: String) {
    DISABLED(
        label = "Off",
        subtitle = "Do not advertise or load Agent Skills in this chat"
    ),
    ON_DEMAND(
        label = "On demand",
        subtitle = "Advertise selected skills and load a matching SKILL.md only when needed"
    ),
    ALWAYS_LOADED(
        label = "Always loaded",
        subtitle = "Inject selected skill instructions up front, within a bounded context budget"
    );

    companion object {
        fun fromKey(key: String): SkillAccessMode =
            entries.firstOrNull { it.name == key } ?: ON_DEMAND
    }
}
