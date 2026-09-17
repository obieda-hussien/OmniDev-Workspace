package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCall

/** Local conservative policy deciding whether one model-emitted tool batch may run concurrently. */
object ToolBatchPolicy {

    fun canRunBatchInParallel(calls: List<ToolCall>): Boolean {
        if (calls.size <= 1) return false
        return calls.all(::isReadOnly)
    }

    internal fun isReadOnly(call: ToolCall): Boolean {
        val name = call.name.lowercase()
        if (name.startsWith("mcp_")) return isKnownReadMcp(name)

        if (name == "semantic_ui") {
            val action = call.arguments["action"]?.lowercase().orEmpty()
            return action in setOf("dump_tree", "get_node", "find_node", "list_nodes")
        }

        if (name in EXPLICIT_READ_TOOLS) return true
        if (MUTATION_HINTS.any(name::contains)) return false
        if (READ_HINTS.any(name::contains)) return true

        // Unknown capabilities are serialized. Correctness beats speculative parallelism.
        return false
    }

    private fun isKnownReadMcp(name: String): Boolean =
        listOf("get", "list", "search", "read", "fetch", "query", "inspect", "status", "find")
            .any { marker -> name.contains("_$marker") || name.endsWith(marker) }

    private val EXPLICIT_READ_TOOLS = setOf(
        "read_file_lines", "search_codebase", "list_directory", "web_search", "web_search_deep",
        "web_scraper", "fetch_page", "scrape_multiple", "grep_search", "find_files",
        "get_device_info", "read_notifications", "vector_search", "vector_similar",
        "browser_get_dom", "screenshot_tool"
    )

    private val READ_HINTS = listOf(
        "read", "search", "grep", "find", "list", "inspect", "query", "fetch", "status",
        "info", "scan", "analyze", "dump", "get_"
    )

    private val MUTATION_HINTS = listOf(
        "write", "patch", "create", "delete", "remove", "update", "set_", "execute", "terminal",
        "shell", "install", "uninstall", "deploy", "commit", "push", "merge", "rename", "move",
        "click", "type", "tap", "scroll", "press", "grant", "revoke", "communicate", "planner"
    )
}
