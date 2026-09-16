package com.omnidev.workspace.domain.engine

/**
 * Shared intent classifier used by AUTO mode and by AgentPipeline tool-schema routing.
 *
 * Tool-domain selection is deliberately conservative: CORE tools are always visible,
 * while expensive capability families are injected only when the request actually
 * needs them. This avoids the historical failure mode where every unknown tool was
 * GENERAL and GENERAL was always enabled, effectively injecting most of the tool
 * catalog on every ReAct iteration.
 */
object IntentClassifier {

    data class Phrase(val text: String, val weight: Int)

    val SWARM_PHRASES = listOf(
        Phrase("from scratch", 4), Phrase("من الصفر", 4),
        Phrase("entire codebase", 5), Phrase("full project", 4), Phrase("مشروع كامل", 4),
        Phrase("create the entire", 5), Phrase("implement the full", 4),
        Phrase("end to end", 4), Phrase("end-to-end", 4),
        Phrase("complete implementation", 4), Phrase("implement all", 3),
        Phrase("write all", 3), Phrase("create all", 3),
        Phrase("refactor the whole", 4), Phrase("migrate the whole", 4),
        Phrase("اعمل التطبيق", 5), Phrase("ابني التطبيق", 5),
        Phrase("افعل كل", 4), Phrase("كل الكود", 4), Phrase("ابني", 3)
    )

    val AGENT_PHRASES = listOf(
        Phrase("fix", 3), Phrase("bug", 3), Phrase("صلح", 3), Phrase("خطأ", 2),
        Phrase("refactor", 3), Phrase("edit", 3), Phrase("عدل", 3),
        Phrase("implement", 3), Phrase("write the", 2), Phrase("اكتب", 2),
        Phrase("add", 2), Phrase("اضف", 2), Phrase("remove", 2), Phrase("احذف", 2),
        Phrase("update", 2), Phrase("create", 2), Phrase("انشئ", 2),
        Phrase("run", 2), Phrase("شغل", 2), Phrase("execute", 2),
        Phrase("debug", 3), Phrase("compile", 3), Phrase("build", 2),
        Phrase("test", 2), Phrase("اختبر", 2),
        Phrase("search codebase", 3), Phrase("ابحث", 2),
        Phrase("read file", 3), Phrase("ملف", 2), Phrase("file", 2),
        Phrase("kotlin", 3), Phrase("java", 3), Phrase("android", 2),
        Phrase("gradle", 3), Phrase("manifest", 3), Phrase("dependency", 2),
        Phrase("function", 2), Phrase("class", 2), Phrase("api", 2),
        Phrase("patch", 3), Phrase("deploy", 3), Phrase("install", 2),
        Phrase("configure", 2), Phrase("setup", 2), Phrase("code", 2), Phrase("كود", 2),
        Phrase("lint", 2), Phrase("analyze", 2), Phrase("افحص", 2)
    )

    val CHAT_PHRASES = listOf(
        Phrase("what is", 3), Phrase("what are", 3), Phrase("ايه هو", 3), Phrase("ايه هي", 3),
        Phrase("how does", 3), Phrase("how do", 3), Phrase("ازاي", 3),
        Phrase("why", 2), Phrase("ليه", 2),
        Phrase("explain", 3), Phrase("اشرحلي", 3), Phrase("شرح", 3),
        Phrase("difference between", 4), Phrase("فرق بين", 4),
        Phrase("what's the", 3), Phrase("can you tell", 2),
        Phrase("هل", 2), Phrase("is it", 2), Phrase("should i", 2),
        Phrase("example of", 3), Phrase("مثال", 3),
        Phrase("define", 3), Phrase("meaning", 3), Phrase("معنى", 3),
        Phrase("recommend", 2), Phrase("suggest", 2), Phrase("opinion", 2)
    )

    enum class ToolDomain {
        /** Small always-on planning/memory/control plane. */
        CORE,
        /** Files, repo, build, terminal and language runtimes. */
        CODE_TERMINAL,
        /** Android device/system/privileged execution. */
        DEVICE_CONTROL,
        MESSAGING,
        ANALYTICS,
        WEB_SEARCH,
        /** Miscellaneous utilities only when generic utility intent is present. */
        GENERAL
    }

    /**
     * Select the minimum useful domain set for this request.
     * Native function schemas remain callable only for selected domains, so keeping
     * this set tight directly reduces prompt tokens on every ReAct iteration.
     */
    fun getRelevantDomains(input: String): Set<ToolDomain> {
        val mode = classify(input)
        val lower = input.lowercase()

        val hasMessaging = containsAny(lower,
            "message", "whatsapp", "telegram", "discord", "email", "slack", "send",
            "رسالة", "واتساب", "تليجرام", "ابعت", "ارسل")

        val hasAnalytics = containsAny(lower,
            "analytics", "metrics", "cost", "tokens", "usage", "stats", "performance",
            "token", "latency", "benchmark", "احصائيات", "تكلفة", "توكن")

        val hasWeb = containsAny(lower,
            "http://", "https://", "www.", "web", "internet", "browser", "search online",
            "google", "github", "gitlab", "latest", "today", "news", "price", "weather",
            "research", "website", "url", "موقع", "الويب", "الانترنت", "ابحث على", "جيت هاب")

        val hasDeviceControl = containsAny(lower,
            "shizuku", "rish", " adb", "adb ", "root", "android system", "device", "phone",
            "settings put", "settings get", "dumpsys", "getprop", "setprop", "logcat",
            "battery", "brightness", "screen", "wifi", "bluetooth", "package manager",
            "permission", "notification", "launcher", "keyevent", "screencap", "system uid",
            "جهاز", "الموبايل", "الهاتف", "شيزوكو", "روت", "بطارية", "سطوع", "صلاحيات")

        val hasCode = mode == OmniMode.AGENT || mode == OmniMode.SWARM || containsAny(lower,
            "code", "كود", "file", "ملف", "repo", "repository", "project", "مشروع",
            "kotlin", "java", "python", "node", "gradle", "compile", "build", "test", "lint",
            "terminal", "shell", "package", "dependency", "git ", "branch", "commit", "pull request")

        val hasGeneralUtility = containsAny(lower,
            "reminder", "schedule", "task", "calendar", "clipboard", "contact", "location",
            "time", "date", "automation", "تذكير", "مهمة", "موعد", "الحافظة", "الموقع")

        return buildSet {
            add(ToolDomain.CORE)
            if (hasCode) add(ToolDomain.CODE_TERMINAL)
            if (hasDeviceControl) add(ToolDomain.DEVICE_CONTROL)
            if (hasMessaging) add(ToolDomain.MESSAGING)
            if (hasAnalytics) add(ToolDomain.ANALYTICS)
            if (hasWeb) add(ToolDomain.WEB_SEARCH)
            if (hasGeneralUtility || (mode == OmniMode.CHAT && !hasWeb)) add(ToolDomain.GENERAL)
        }
    }

    /** Maps a tool to a stable capability family. Unknown tools are not always-on. */
    fun getToolDomain(toolName: String): ToolDomain {
        val n = toolName.lowercase()
        if (n.startsWith("mcp_")) return ToolDomain.CORE

        if (n in CORE_TOOLS ||
            n.startsWith("brain_") || n.startsWith("vector_") ||
            n.startsWith("causal_plan_") || n.contains("memory") ||
            n.contains("knowledge") || n.contains("trust_profile") ||
            n.contains("earned_capabilit")
        ) return ToolDomain.CORE

        if (n in CODE_TOOLS ||
            n.startsWith("git_") || n.startsWith("repo_") || n.startsWith("build_") ||
            n.startsWith("rollback_") || n.startsWith("python_") || n.startsWith("file_") ||
            n.contains("terminal") || n.contains("codebase") || n.contains("manifest") ||
            n.contains("apktool") || n.contains("jadx") || n.contains("dependency") ||
            n.contains("script_runner") || n.contains("tool_downloader")
        ) return ToolDomain.CODE_TERMINAL

        if (n in DEVICE_TOOLS ||
            n.startsWith("device_") || n.startsWith("system_") || n.startsWith("app_") ||
            n.startsWith("input_") || n.startsWith("launcher_") || n.startsWith("widget_") ||
            n.contains("shizuku") || n.contains("root_shell") || n.contains("permission") ||
            n.contains("logcat") || n.contains("screenshot") || n.contains("hardware") ||
            n.contains("vpn") || n.contains("power") || n.contains("ui_automation") ||
            n.contains("semantic_ui")
        ) return ToolDomain.DEVICE_CONTROL

        if (n in MESSAGING_TOOLS ||
            containsAny(n, "whatsapp", "telegram", "discord", "slack", "sendgrid", "email", "sms", "contact", "call_log")
        ) return ToolDomain.MESSAGING

        if (n in ANALYTICS_TOOLS ||
            containsAny(n, "analytics", "metrics", "profiler", "anr", "memory_snapshot", "network_log", "db_schema")
        ) return ToolDomain.ANALYTICS

        if (n in WEB_TOOLS ||
            n.startsWith("web_") || n.startsWith("browser_") || n.startsWith("fetch_") ||
            n.contains("scraper") || n.contains("research") || n.contains("github_manager") ||
            n.contains("social_media") || n.contains("network_request")
        ) return ToolDomain.WEB_SEARCH

        return ToolDomain.GENERAL
    }

    private val CORE_TOOLS = setOf(
        "remember_fact", "search_knowledge", "update_memory", "delete_memory",
        "planner", "eval_expression", "request_execution_mode",
        "get_trust_profile", "list_earned_capabilities"
    )

    private val CODE_TOOLS = setOf(
        "read_file", "read_file_lines", "write_file", "create_file", "patch_file", "patch_file_content",
        "list_files", "delete_file", "mkdir", "search_codebase", "grep_code",
        "execute_terminal_command", "agent_runtime", "direct_terminal", "termux_bridge",
        "advanced_terminal", "setup_build_environment", "python_runtime", "git_manager",
        "package_installer", "quality_security", "execution_diagnostics", "skill_manager"
    )

    private val DEVICE_TOOLS = setOf(
        "privileged_tool", "shizuku_command", "advanced_root_shell", "root_shell_tool",
        "app_manager", "hardware_toggle", "device_info", "visual_inspector", "ui_replica_pipeline",
        "screenshot", "system_settings", "media_control", "device_admin", "clipboard",
        "notification_capture", "vpn_control", "system_power", "permission_manager"
    )

    private val MESSAGING_TOOLS = setOf(
        "whatsapp", "telegram_bot", "telegram_publish", "discord_bot", "publish_to_discord",
        "slack", "sendgrid_email", "sms_reader", "system_contacts", "call_log", "communicate"
    )

    private val ANALYTICS_TOOLS = setOf(
        "read_network_log", "read_db_schema", "analyze_anr_trace", "memory_snapshot",
        "god_eye_profiler", "predictive_analytics"
    )

    private val WEB_TOOLS = setOf(
        "web_search", "web_search_deep", "fetch_page", "fetch_url", "headless_browser",
        "github_manager", "web_scraper", "scrape_multiple"
    )

    fun classify(input: String): OmniMode {
        val lower = input.lowercase()
        val wordCount = lower.split(Regex("\\s+")).size

        var swarmScore = SWARM_PHRASES.sumOf { if (lower.contains(it.text)) it.weight else 0 }
        val agentScore = AGENT_PHRASES.sumOf { if (lower.contains(it.text)) it.weight else 0 }
        val chatScore = CHAT_PHRASES.sumOf { if (lower.contains(it.text)) it.weight else 0 }

        if (wordCount > 25) swarmScore += 2
        if (wordCount > 40) swarmScore += 3

        return when {
            swarmScore > agentScore && swarmScore > chatScore && swarmScore >= 4 -> OmniMode.SWARM
            agentScore > chatScore -> OmniMode.AGENT
            chatScore > agentScore -> OmniMode.CHAT
            wordCount <= 12 -> OmniMode.CHAT
            else -> OmniMode.AGENT
        }
    }

    private fun containsAny(haystack: String, vararg needles: String): Boolean =
        needles.any { it in haystack }
}
