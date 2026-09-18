package com.omnidev.workspace.domain.engine

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Shared intent classifier used by AUTO mode and AgentPipeline tool-schema routing.
 *
 * The old classifier was mostly a phrase counter. This version extracts a bounded feature vector
 * and scores Chat / Agent / Team independently. It stays deterministic, local and cheap enough
 * to run for every message on low-end Android devices.
 */
object IntentClassifier {

    data class Phrase(val text: String, val weight: Int)

    data class TaskSignals(
        val wordCount: Int,
        val executionIntent: Float,
        val conversationalIntent: Float,
        val mutationIntent: Float,
        val codeIntent: Float,
        val deviceIntent: Float,
        val researchIntent: Float,
        val verificationIntent: Float,
        val parallelism: Float,
        val breadth: Float,
        val structuralComplexity: Float,
        val domainCount: Int
    ) {
        val complexity: Float
            get() = (
                structuralComplexity * 0.35f +
                    breadth * 0.30f +
                    executionIntent * 0.20f +
                    verificationIntent * 0.15f
                ).coerceIn(0f, 1f)

        /** Stable coarse context key for future local outcome learning. */
        fun bucketKey(): String = buildString {
            append(if (executionIntent >= 0.55f) "exec" else "talk")
            append('_').append(if (parallelism >= 0.50f) "parallel" else "serial")
            append('_').append(if (breadth >= 0.55f) "broad" else "focused")
            append('_').append(if (mutationIntent >= 0.45f) "mutating" else "readonly")
            append('_').append(
                when {
                    deviceIntent >= 0.55f -> "device"
                    codeIntent >= 0.55f -> "code"
                    researchIntent >= 0.55f -> "research"
                    else -> "general"
                }
            )
        }
    }

    data class ModeScores(
        val chat: Float,
        val agent: Float,
        val swarm: Float
    ) {
        fun score(mode: OmniMode): Float = when (mode) {
            OmniMode.CHAT -> chat
            OmniMode.AGENT -> agent
            OmniMode.SWARM -> swarm
            OmniMode.AUTO -> max(chat, max(agent, swarm))
        }
    }

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
        CORE,
        CODE_TERMINAL,
        DEVICE_CONTROL,
        MESSAGING,
        ANALYTICS,
        WEB_SEARCH,
        GENERAL
    }

    fun analyze(input: String): TaskSignals {
        val lower = input.lowercase().trim()
        if (lower.isBlank()) {
            return TaskSignals(
                wordCount = 0,
                executionIntent = 0f,
                conversationalIntent = 1f,
                mutationIntent = 0f,
                codeIntent = 0f,
                deviceIntent = 0f,
                researchIntent = 0f,
                verificationIntent = 0f,
                parallelism = 0f,
                breadth = 0f,
                structuralComplexity = 0f,
                domainCount = 0
            )
        }

        val words = lower.split(Regex("\\s+")).filter { it.isNotBlank() }
        val wordCount = words.size
        val bulletCount = Regex("(?m)^\\s*(?:[-*•]|\\d+[.)])\\s+").findAll(input).count()
        val sentenceCount = max(1, Regex("[.!?؟\\n]+").findAll(input).count())

        val agentPhraseWeight = weightedMatches(lower, AGENT_PHRASES)
        val chatPhraseWeight = weightedMatches(lower, CHAT_PHRASES)
        val swarmPhraseWeight = weightedMatches(lower, SWARM_PHRASES)

        val mutationHits = countAny(
            lower,
            "write", "edit", "modify", "change", "delete", "create", "implement", "refactor",
            "patch", "install", "configure", "deploy", "fix", "صلح", "عدل", "احذف", "انشئ",
            "اكتب", "ضيف", "اضف", "نفذ"
        )
        val verificationHits = countAny(
            lower,
            "verify", "test", "lint", "compile", "build", "benchmark", "validate", "check",
            "اختبر", "اتأكد", "تاكد", "افحص", "راجع"
        )
        val splitHits = countAny(
            lower,
            " independently", "parallel", "in parallel", "multiple parts", "several parts",
            "frontend", "backend", "database", "tests", "ui", "api", "security", "performance",
            "بالتوازي", "كمان", "وكمان", "عدة", "أجزاء", "اجزاء", "كل المشاكل"
        )

        val codeHits = countAny(
            lower,
            "code", "kotlin", "java", "python", "gradle", "manifest", "repository", "repo",
            "project", "function", "class", "dependency", "terminal", "shell", "كود", "مشروع", "ملف"
        )
        val deviceHits = countAny(
            lower,
            "android system", "shizuku", "rish", " adb", "adb ", "root", "device", "phone",
            "dumpsys", "getprop", "logcat", "permission", "wifi", "bluetooth", "screen",
            "brightness", "settings", "apk", "package",
            "الموبايل", "الهاتف", "الجهاز", "شيزوكو", "روت", "صلاحيات",
            "الشاشة", "سطوع", "إعدادات", "اعدادات", "تطبيق"
        )
        val researchHits = countAny(
            lower,
            "research", "search online", "latest", "today", "news", "compare sources",
            "web", "internet", "browser", "github", "ابحث", "بحث", "احدث", "الويب", "الانترنت"
        )

        val domainFlags = listOf(
            codeHits > 0,
            deviceHits > 0,
            researchHits > 0,
            containsAny(lower, "database", "room", "sqlite", "قاعدة بيانات"),
            containsAny(lower, "ui", "compose", "واجهة", "تصميم"),
            containsAny(lower, "security", "vulnerability", "أمان", "ثغرة"),
            containsAny(lower, "performance", "latency", "memory", "أداء", "ذاكرة")
        )
        val domainCount = domainFlags.count { it }

        val executionIntent = normalizeEvidence(
            agentPhraseWeight * 0.10f + mutationHits * 0.16f + verificationHits * 0.08f +
                if (codeHits + deviceHits > 0) 0.12f else 0f
        )
        val conversationalIntent = normalizeEvidence(
            chatPhraseWeight * 0.12f +
                if (lower.endsWith("?") || '؟' in lower) 0.15f else 0f
        )
        val mutationIntent = normalizeEvidence(mutationHits * 0.24f)
        val codeIntent = normalizeEvidence(codeHits * 0.17f)
        val deviceIntent = normalizeEvidence(deviceHits * 0.20f)
        val researchIntent = normalizeEvidence(researchHits * 0.18f)
        val verificationIntent = normalizeEvidence(verificationHits * 0.22f)

        val breadth = (
            domainCount * 0.13f +
                min(4, bulletCount) * 0.08f +
                min(4, splitHits) * 0.09f +
                swarmPhraseWeight * 0.06f
            ).coerceIn(0f, 1f)

        val parallelism = (
            splitHits * 0.16f +
                max(0, domainCount - 1) * 0.14f +
                swarmPhraseWeight * 0.07f +
                if (bulletCount >= 3) 0.12f else 0f
            ).coerceIn(0f, 1f)

        // Logarithmic length contribution avoids making every long pasted prompt a Team task.
        val lengthComplexity = if (wordCount <= 8) 0f else {
            (ln(wordCount.toDouble()) / ln(120.0)).toFloat().coerceIn(0f, 1f)
        }
        val structuralComplexity = (
            lengthComplexity * 0.42f +
                min(6, bulletCount) * 0.07f +
                min(6, sentenceCount) * 0.025f +
                breadth * 0.25f
            ).coerceIn(0f, 1f)

        return TaskSignals(
            wordCount = wordCount,
            executionIntent = executionIntent,
            conversationalIntent = conversationalIntent,
            mutationIntent = mutationIntent,
            codeIntent = codeIntent,
            deviceIntent = deviceIntent,
            researchIntent = researchIntent,
            verificationIntent = verificationIntent,
            parallelism = parallelism,
            breadth = breadth,
            structuralComplexity = structuralComplexity,
            domainCount = domainCount
        )
    }

    fun scoreModes(input: String): ModeScores = scoreModes(analyze(input))

    fun scoreModes(signals: TaskSignals): ModeScores {
        val readOnlyResearch = signals.researchIntent >= 0.45f && signals.mutationIntent < 0.25f

        val chat = (
            0.18f +
                signals.conversationalIntent * 0.52f +
                (1f - signals.executionIntent) * 0.24f +
                (if (readOnlyResearch) 0.10f else 0f) -
                signals.mutationIntent * 0.30f -
                signals.parallelism * 0.16f
            ).coerceIn(0f, 1f)

        val agent = (
            0.18f +
                signals.executionIntent * 0.42f +
                signals.mutationIntent * 0.25f +
                max(signals.codeIntent, signals.deviceIntent) * 0.13f +
                signals.verificationIntent * 0.08f +
                signals.complexity * 0.08f -
                signals.parallelism * 0.08f
            ).coerceIn(0f, 1f)

        var swarm = (
            0.05f +
                signals.parallelism * 0.42f +
                signals.breadth * 0.24f +
                signals.complexity * 0.17f +
                signals.executionIntent * 0.10f +
                signals.verificationIntent * 0.05f
            ).coerceIn(0f, 1f)

        // Team has orchestration cost. Pure discussion/research should not enter Team solely
        // because the prompt is long or mentions many topics.
        if (signals.executionIntent < 0.35f) swarm = min(swarm, 0.52f)
        if (signals.parallelism < 0.30f) swarm = min(swarm, 0.58f)

        return ModeScores(chat = chat, agent = agent, swarm = swarm)
    }

    /**
     * Hysteresis margins prevent unstable Agent <-> Team flipping on borderline prompts.
     */
    fun classify(input: String): OmniMode {
        val signals = analyze(input)
        val scores = scoreModes(signals)
        return when {
            scores.swarm >= 0.62f &&
                scores.swarm >= scores.agent + 0.07f &&
                signals.parallelism >= 0.34f -> OmniMode.SWARM

            scores.agent >= 0.48f &&
                scores.agent >= scores.chat + 0.03f -> OmniMode.AGENT

            else -> OmniMode.CHAT
        }
    }

    fun getRelevantDomains(input: String): Set<ToolDomain> {
        val signals = analyze(input)
        val mode = classify(input)
        val lower = input.lowercase()

        val hasMessaging = containsAny(
            lower,
            "message", "messages", "sms", "text message", "inbox",
            "whatsapp", "telegram", "discord", "email", "slack", "send",
            "wallet", "orange cash", "vodafone cash",
            "رسالة", "رسائل", "رسايل", "رساله", "اس ام اس",
            "واتساب", "تليجرام", "ابعت", "ارسل", "محفظة",
            "اورنج كاش", "أورنج كاش", "اورنچ كاش", "أورنچ كاش"
        )
        val hasAnalytics = containsAny(
            lower,
            "analytics", "metrics", "cost", "tokens", "usage", "stats", "performance",
            "token", "latency", "benchmark", "احصائيات", "تكلفة", "توكن"
        )
        val hasWeb = signals.researchIntent >= 0.35f || containsAny(
            lower,
            "http://", "https://", "www.", "website", "url", "google"
        )
        val hasDeviceControl = signals.deviceIntent >= 0.15f
        val hasCode = signals.codeIntent >= 0.14f ||
            ((mode == OmniMode.AGENT || mode == OmniMode.SWARM) && !hasMessaging && !hasDeviceControl)
        val hasGeneralUtility = containsAny(
            lower,
            "reminder", "schedule", "task", "calendar", "clipboard", "contact", "location",
            "time", "date", "automation", "تذكير", "مهمة", "موعد", "الحافظة", "الموقع"
        )

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

    private fun weightedMatches(text: String, phrases: List<Phrase>): Int =
        phrases.sumOf { if (text.contains(it.text)) it.weight else 0 }

    private fun normalizeEvidence(value: Float): Float = (1f - 1f / (1f + value)).coerceIn(0f, 1f)

    private fun countAny(haystack: String, vararg needles: String): Int = needles.count { it in haystack }

    private fun containsAny(haystack: String, vararg needles: String): Boolean = needles.any { it in haystack }

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
}
