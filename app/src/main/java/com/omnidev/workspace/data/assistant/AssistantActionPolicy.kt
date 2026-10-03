package com.omnidev.workspace.data.assistant

/** Authorization is derived solely from the user's typed/spoken request, never screen content. */
object AssistantActionPolicy {
    private val readers = setOf("dump_tree", "dump_screen", "get_summary", "find_element", "describe", "verify", "macro_list", "get_tree", "get_ui", "inspect", "find", "find_node", "find_nodes", "get_screen", "screenshot", "status", "get_profile", "list", "list_apps", "get_current_app", "get_focused", "get_text", "wait", "wait_for", "read", "get_windows")
    private val guardedTools = setOf("semantic_ui", "ui_automation", "autofill_assist", "headless_browser", "app_manager", "browser_type", "browser_click", "browser_execute_js")
    private val fill = Regex("^(?:(?:please|can you|could you|عايزك|ممكن|من فضلك|لو سمحت)\\s+)?(?:type|fill|enter|write in|اكتب|املا|املأ|ادخل|دخل|حط)(?:\\s|$)", RegexOption.IGNORE_CASE)
    private val navigate = Regex("^(?:(?:please|can you|عايزك|ممكن|لو سمحت)\\s+)?(?:open|launch|go to|scroll|افتح|روح|انزل|اطلع)(?:\\s|$)", RegexOption.IGNORE_CASE)

    fun isReadOnly(tool: String, args: Map<String, String>): Boolean {
        val action = args["action"].orEmpty().lowercase()
        return tool in guardedTools && (action in readers || action.startsWith("get_") || action.startsWith("read_") || action.startsWith("list_"))
    }

    fun requiresConsent(tool: String, args: Map<String, String>, request: String): Boolean {
        if (tool !in guardedTools) return false // Other tools retain the application's existing gates.
        val action = if (tool == "browser_type") "type" else args["action"].orEmpty().lowercase()
        if (isReadOnly(tool, args)) return false
        val text = request.trim()
        if (Regex("مش عارف|مش عارفه|مش عارفة|لا أعرف|don.t know|what (do|should|can) i|how (do|can) i|اكتب (اي|إيه|ايه)|ازاي|كيف", RegexOption.IGNORE_CASE).containsMatchIn(text)) return true
        if (action in setOf("type", "type_text", "input_text", "fill_focused", "fill", "smart_fill", "force_type") && fill.containsMatchIn(text)) return false
        if (action in setOf("launch", "open", "navigate", "scroll") && navigate.containsMatchIn(text)) return false
        return true // Chains, taps, submissions and ambiguous instructions get an exact action button.
    }

    fun preview(tool: String, args: Map<String, String>): String {
        val safe = args.filterKeys { !it.contains("password", true) && !it.contains("token", true) && !it.contains("secret", true) }
        return "$tool · ${args["action"].orEmpty()}\n" + safe.entries.joinToString("\n") { (key, value) -> "$key: ${value.take(1000)}" }
    }
}
