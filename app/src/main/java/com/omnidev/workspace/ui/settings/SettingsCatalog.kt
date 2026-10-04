package com.omnidev.workspace.ui.settings

import java.util.Locale

internal enum class SettingsGroup(val title: String, val description: String) {
    AI("AI & models", "Providers, model selection and reasoning"),
    ASSISTANT("Assistant & device", "Voice, gestures and device permissions"),
    PERSONAL("Personalization & memory", "Your profile and what Omni remembers"),
    AUTOMATION("Connections & automation", "Linked accounts, tools and scheduled work"),
    INSIGHTS("Usage & diagnostics", "Costs, agent activity and troubleshooting")
}

internal enum class SettingsDestination { PROVIDERS, MODELS, REASONING, LOCAL_MODELS, ASSISTANT, VOICE, DEVICE_ACCESS, ACCESSIBILITY, FILE_ACCESS, PROFILE, MEMORY, INTEGRATIONS, MCP, SCHEDULE, SKILLS, ANALYTICS, BRAIN, DEBUG }

internal data class SettingsEntry(
    val destination: SettingsDestination,
    val group: SettingsGroup,
    val title: String,
    val description: String,
    val keywords: String = ""
)

internal object SettingsCatalog {
    val entries = listOf(
        SettingsEntry(SettingsDestination.PROVIDERS, SettingsGroup.AI, "Providers & API keys", "Connect and manage your AI providers", "credentials copilot openai anthropic gemini مفاتيح حسابات مزود"),
        SettingsEntry(SettingsDestination.MODELS, SettingsGroup.AI, "Model selection", "Choose models for chat, agents and teams", "routing swarm orchestrator worker نموذج نماذج شات وكيل فريق"),
        SettingsEntry(SettingsDestination.REASONING, SettingsGroup.AI, "Deep thinking", "Give supported models more time to reason", "reasoning behavior تفكير استدلال"),
        SettingsEntry(SettingsDestination.LOCAL_MODELS, SettingsGroup.AI, "On-device models", "Download and run models offline", "local edge byom gguf quantized محلي تحميل بدون انترنت"),
        SettingsEntry(SettingsDestination.ASSISTANT, SettingsGroup.ASSISTANT, "Default assistant", "Set up Omni for the Home button or assistant gesture", "overlay screen screenshot floating افتراضي مساعد شاشة عائم"),
        SettingsEntry(SettingsDestination.VOICE, SettingsGroup.ASSISTANT, "Voice activation", "Wake phrase, training and hands-free listening", "hi omni wake up microphone speech audio صوت تدريب تنبيه ميكروفون"),
        SettingsEntry(SettingsDestination.DEVICE_ACCESS, SettingsGroup.ASSISTANT, "Device access & permissions", "Review and grant the access Omni needs", "shizuku root lock screen صلاحيات اذونات أذونات شيزوكو روت قفل"),
        SettingsEntry(SettingsDestination.ACCESSIBILITY, SettingsGroup.ASSISTANT, "Accessibility service", "Let Omni read and interact with app screens", "semantic ui control الوصول تحكم"),
        SettingsEntry(SettingsDestination.FILE_ACCESS, SettingsGroup.ASSISTANT, "Extended file access", "Allow file tools outside the selected project", "god mode filesystem storage ملفات تخزين"),
        SettingsEntry(SettingsDestination.PROFILE, SettingsGroup.PERSONAL, "Your profile", "Tell Omni about yourself and your preferences", "user name identity personalization بروفايل ملف شخصي اسم"),
        SettingsEntry(SettingsDestination.MEMORY, SettingsGroup.PERSONAL, "Knowledge & memory", "Browse, search and edit saved memories", "knowledge base explorer context ذاكرة ذكريات معرفة سياق"),
        SettingsEntry(SettingsDestination.INTEGRATIONS, SettingsGroup.AUTOMATION, "Linked accounts", "Connect GitHub, Telegram and other platforms", "integrations whatsapp bridge github agent access ربط تكامل حسابات تيليجرام واتساب"),
        SettingsEntry(SettingsDestination.MCP, SettingsGroup.AUTOMATION, "MCP servers", "Connect external tools and services", "model context protocol json سيرفر خوادم ادوات أدوات"),
        SettingsEntry(SettingsDestination.SKILLS, SettingsGroup.AUTOMATION, "Agent skills & tools", "Manage reusable skills and available tools", "registry import learned tasks routines taskly مهارات ادوات أدوات مهام متعلمة"),
        SettingsEntry(SettingsDestination.SCHEDULE, SettingsGroup.AUTOMATION, "Scheduled tasks", "Create and manage background tasks", "scheduler autonomous reminders جدولة مهام تذكير"),
        SettingsEntry(SettingsDestination.ANALYTICS, SettingsGroup.INSIGHTS, "Usage & costs", "Tokens, spending and tool statistics", "analytics dashboard history توكينز تكلفة تكاليف استهلاك احصائيات إحصائيات"),
        SettingsEntry(SettingsDestination.BRAIN, SettingsGroup.INSIGHTS, "Agent brain", "Learned patterns, execution logs and status", "memory awareness learning logs مخ وكيل تعلم سجل"),
        SettingsEntry(SettingsDestination.DEBUG, SettingsGroup.INSIGHTS, "Debug console", "Crash reports, error logs and device diagnostics", "troubleshooting errors bugs كراش اخطاء أخطاء تشخيص مشاكل")
    )

    fun search(query: String, available: List<SettingsEntry> = entries): List<SettingsEntry> {
        val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return available
        return available.filter { entry ->
            val text = "${entry.title} ${entry.description} ${entry.group.title} ${entry.keywords}".lowercase(Locale.ROOT)
            terms.all { it in text }
        }
    }
}
