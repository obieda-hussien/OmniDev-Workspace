package com.omnidev.workspace.data.tools

/** Named page operations are separate from arbitrary script and session-secret access. */
object BrowserToolPolicy {
    val liteActions = setOf(
        "new_session", "new_incognito_session", "list_sessions", "switch_session", "close_session", "destroy_all",
        "navigate", "back", "forward", "reload", "get_current_url", "get_title", "get_readiness_score",
        "get_dom", "get_html", "find_element", "find_all", "count_elements", "click", "type_text", "fill_form",
        "submit_form", "scroll_to", "select_option", "hover", "clear_input", "wait_for_element", "wait_for_text",
        "wait_for_network_idle", "wait_ms", "wait_for_url_change", "screenshot", "extract_links", "extract_table",
        "extract_meta", "extract_text", "extract_json"
    )
    val mutations = setOf("click", "type_text", "fill_form", "submit_form", "select_option", "clear_input")
    fun allowed(tier: String, action: String) = when (tier) {
        "LITE" -> action.lowercase(java.util.Locale.ROOT) in liteActions
        "NORM", "PRO", "OEM", "ADMIN" -> true
        else -> false
    }
}
