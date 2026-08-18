package com.omnidev.workspace.domain.engine

/**
 * Shared intent classifier used by both the main [ChatViewModel] (AUTO mode) and the
 * floating overlay to decide which execution mode best matches
 * the user's intent without an extra LLM call.
 *
 * ### Algorithm
 * Each phrase list has a [Phrase.weight] (1–5). All matching phrases contribute their
 * weight to the category score. The category with the highest total score wins.
 * Longer messages get a SWARM boost since coordinated project descriptions tend to be verbose.
 * Tie-break: AGENT > CHAT (an Agent can still answer conversationally when no tools fire).
 */
object IntentClassifier {

    /** A phrase that contributes [weight] points to a category score when found in the input. */
    data class Phrase(val text: String, val weight: Int)

    // ── SWARM phrases — multi-agent coordinated project work ──
    // Arabic: "Context note Context note"=make the app, "Context note"=build (imperative),
    //         "Context note Context note"=do everything, "Context note Context note"=all the code,
    //         "Context note Context note"=from scratch, "Context note Context note"=full project
    val SWARM_PHRASES = listOf(
        Phrase("from scratch", 4), Phrase("Info Info", 4),
        Phrase("entire codebase", 5), Phrase("full project", 4), Phrase("Info Info", 4),
        Phrase("create the entire", 5), Phrase("implement the full", 4),
        Phrase("end to end", 4), Phrase("end-to-end", 4),
        Phrase("complete implementation", 4), Phrase("implement all", 3),
        Phrase("write all", 3), Phrase("create all", 3),
        Phrase("refactor the whole", 4), Phrase("migrate the whole", 4),
        Phrase("Info Info", 5), Phrase("Info Info", 5),
        Phrase("Info Info", 4), Phrase("Info Info", 4), Phrase("Info", 3)
    )

    // ── AGENT phrases — execution / tool-use / file operations ──
    // Arabic: "Context note"=create, "Context note"=write, "Context note"=search, "Context note"=check,
    //         "Context note"=edit, "Context note"=delete, "Context note"=add, "Context note"=run,
    //         "Context note"=do/make, "Context note"=file, "Context note"=code, "Context note"=fix
    val AGENT_PHRASES = listOf(
        Phrase("fix", 3), Phrase("bug", 3), Phrase("Info", 3), Phrase("Info", 2),
        Phrase("refactor", 3), Phrase("edit", 3), Phrase("Info", 3),
        Phrase("implement", 3), Phrase("write the", 2), Phrase("Info", 2),
        Phrase("add", 2), Phrase("Info", 2), Phrase("remove", 2), Phrase("Info", 2),
        Phrase("update", 2), Phrase("create", 2), Phrase("Info", 2),
        Phrase("run", 2), Phrase("Info", 2), Phrase("execute", 2),
        Phrase("debug", 3), Phrase("compile", 3), Phrase("build", 2),
        Phrase("test", 2), Phrase("Info", 2),
        Phrase("search codebase", 3), Phrase("Info", 2),
        Phrase("read file", 3), Phrase("Info", 2), Phrase("file", 2),
        Phrase("kotlin", 3), Phrase("java", 3), Phrase("android", 2),
        Phrase("gradle", 3), Phrase("manifest", 3), Phrase("dependency", 2),
        Phrase("function", 2), Phrase("class", 2), Phrase("api", 2),
        Phrase("patch", 3), Phrase("deploy", 3), Phrase("install", 2),
        Phrase("configure", 2), Phrase("setup", 2), Phrase("code", 2), Phrase("Info", 2),
        Phrase("lint", 2), Phrase("analyze", 2), Phrase("Info", 2)
    )

    // ── CHAT phrases — conversational / informational / question ──
    // Arabic: "Context note"=what, "Context note"=how, "Context note"=why, "Context note"=when,
    //         "Context note"=explain, "Context note"=explain to me, "Context note"=difference
    val CHAT_PHRASES = listOf(
        Phrase("what is", 3), Phrase("what are", 3), Phrase("Info Info", 3), Phrase("Info Info", 3),
        Phrase("how does", 3), Phrase("how do", 3), Phrase("Info", 3),
        Phrase("why", 2), Phrase("Info", 2),
        Phrase("explain", 3), Phrase("Info", 3), Phrase("Info", 3),
        Phrase("difference between", 4), Phrase("Info Info", 4),
        Phrase("what's the", 3), Phrase("can you tell", 2),
        Phrase("Info", 2), Phrase("is it", 2), Phrase("should i", 2),
        Phrase("example of", 3), Phrase("Info", 3),
        Phrase("define", 3), Phrase("meaning", 3), Phrase("Info", 3),
        Phrase("recommend", 2), Phrase("suggest", 2), Phrase("opinion", 2)
    )

    /**
     * Classifies [input] and returns the best matching [OmniMode]:
     * [OmniMode.CHAT], [OmniMode.AGENT], or [OmniMode.SWARM].
     *
     * Never returns [OmniMode.AUTO] — this function IS the AUTO resolver.
     */

    // ── Tool Domain Categories ──
    enum class ToolDomain {
        CODE_TERMINAL, MESSAGING, ANALYTICS, WEB_SEARCH, GENERAL
    }

    /** Maps the classified intent to the relevant tool domains to reduce the system prompt payload. */
    fun getRelevantDomains(input: String): Set<ToolDomain> {
        val mode = classify(input)

        // Advanced heuristic: check if specific keywords exist to unlock specific domains
        val lower = input.lowercase()
        val hasMessaging = listOf("message", "whatsapp", "telegram", "discord", "email", "slack", "send").any { it in lower }
        val hasAnalytics = listOf("analytics", "metrics", "cost", "tokens", "usage", "stats", "performance").any { it in lower }

        val domains = mutableSetOf<ToolDomain>()
        domains.add(ToolDomain.GENERAL) // Basic utilities are always allowed

        if (mode == OmniMode.SWARM || mode == OmniMode.AGENT) {
            domains.add(ToolDomain.CODE_TERMINAL)
            domains.add(ToolDomain.WEB_SEARCH)
        }

        if (hasMessaging) domains.add(ToolDomain.MESSAGING)
        if (hasAnalytics) domains.add(ToolDomain.ANALYTICS)

        if (mode == OmniMode.CHAT) {
            domains.add(ToolDomain.WEB_SEARCH)
        }

        return domains
    }

    /** Maps a specific tool name to its domain. */
    fun getToolDomain(toolName: String): ToolDomain {
        if (toolName.startsWith("mcp_")) return ToolDomain.GENERAL // MCP tools are always injected for dynamic capabilities
        return when (toolName) {
            // Code & Terminal
            "read_file", "write_file", "list_files", "delete_file", "patch_file", "mkdir",
            "search_codebase", "grep_code", "execute_terminal_command", "package_installer",
            "run_python", "run_nodejs", "advanced_root_shell", "shizuku_command",
            "app_manifest_analyzer", "intent_resolver", "batch_manifest_analyzer",
            "enhanced_manifest_analyzer", "enhanced_intent_resolver", "enhanced_network_security",
            "enhanced_attack_surface", "check_permission" -> ToolDomain.CODE_TERMINAL

            // Messaging
            "whatsapp", "telegram_bot", "telegram_publish", "discord_bot", "publish_to_discord",
            "slack", "sendgrid_email", "sms_reader", "system_contacts", "call_log" -> ToolDomain.MESSAGING

            // Analytics & Profiling
            "read_network_log", "read_db_schema", "analyze_anr_trace", "memory_snapshot" -> ToolDomain.ANALYTICS

            // Web & Search
            "web_search", "fetch_url", "github_manager", "headless_browser", "semantic_ui" -> ToolDomain.WEB_SEARCH

            // General
            "get_current_datetime", "task_manager", "n8n_automation", "ui_automation",
            "screenshot", "system_settings", "clipboard" -> ToolDomain.GENERAL

            else -> ToolDomain.GENERAL
        }
    }

    fun classify(input: String): OmniMode {
        val lower = input.lowercase()
        val wordCount = lower.split(Regex("\\s+")).size

        var swarmScore = SWARM_PHRASES.sumOf { if (lower.contains(it.text)) it.weight else 0 }
        val agentScore = AGENT_PHRASES.sumOf { if (lower.contains(it.text)) it.weight else 0 }
        val chatScore = CHAT_PHRASES.sumOf { if (lower.contains(it.text)) it.weight else 0 }

        // Length boost: long messages are more likely to describe a coordinated project
        if (wordCount > 25) swarmScore += 2
        if (wordCount > 40) swarmScore += 3

        return when {
            swarmScore > agentScore && swarmScore > chatScore && swarmScore >= 4 -> OmniMode.SWARM
            agentScore > chatScore -> OmniMode.AGENT
            chatScore > agentScore -> OmniMode.CHAT
            wordCount <= 12 -> OmniMode.CHAT   // Short with no signal → conversational
            else -> OmniMode.AGENT             // Long with no signal → safer to use Agent
        }
    }
}
