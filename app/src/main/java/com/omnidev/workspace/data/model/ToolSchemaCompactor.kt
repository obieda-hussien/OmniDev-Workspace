package com.omnidev.workspace.data.model

import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolParameter

/**
 * Bounds native function-schema cost without removing the information needed for
 * function calling. Domain selection should do the coarse filtering first; this is
 * the final request-boundary safety net for duplicate/very verbose tool schemas.
 */
object ToolSchemaCompactor {

    private const val MAX_TOOLS = 80
    private const val DEFAULT_DESCRIPTION_LIMIT = 650
    private const val PARAM_DESCRIPTION_LIMIT = 180

    private val CRITICAL_DESCRIPTION_LIMITS = mapOf(
        "privileged_tool" to 1_800,
        "agent_runtime" to 1_400,
        "github_manager" to 1_100,
        "execution_diagnostics" to 1_000,
        "search_knowledge" to 900
    )

    /** Tools that remain available when a pathological catalog still exceeds [MAX_TOOLS]. */
    private val MUST_KEEP = setOf(
        "agent_runtime", "privileged_tool", "execution_diagnostics",
        "read_file", "read_file_lines", "search_codebase", "patch_file", "patch_file_content",
        "create_file", "git_manager", "search_knowledge", "remember_fact", "planner",
        "web_search", "github_manager"
    )

    fun compact(
        tools: List<ToolDefinition>?,
        messages: List<ChatMessage>
    ): List<ToolDefinition>? {
        if (tools.isNullOrEmpty()) return tools

        // Preserve first definition on accidental duplicate names; duplicate schemas waste
        // tokens and can also confuse providers that require unique function names.
        val unique = LinkedHashMap<String, IndexedValue<ToolDefinition>>()
        tools.forEachIndexed { index, tool -> unique.putIfAbsent(tool.name, IndexedValue(index, tool)) }

        val latestUser = messages.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty().lowercase()
        val queryTerms = WORD.findAll(latestUser)
            .map { it.value.lowercase() }
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()

        val selected = if (unique.size <= MAX_TOOLS) {
            unique.values.toList()
        } else {
            val matchedSpecializedNames = SPECIALIZED_INTENT_HINTS
                .filterValues { hints -> hints.any(latestUser::contains) }
                .keys

            val pinned = unique.values.filter { indexed ->
                indexed.value.name in matchedSpecializedNames
            }
            val pinnedNames = pinned.mapTo(mutableSetOf()) { it.value.name }

            val remaining = unique.values
                .asSequence()
                .filterNot { it.value.name in pinnedNames }
                .sortedWith(
                    compareByDescending<IndexedValue<ToolDefinition>> {
                        relevanceScore(it.value, queryTerms, latestUser)
                    }.thenBy { it.index }
                )
                .take((MAX_TOOLS - pinned.size).coerceAtLeast(0))
                .toList()

            (pinned + remaining)
                .distinctBy { it.value.name }
                .take(MAX_TOOLS)
                .sortedBy { it.index }
        }

        return selected.map { indexed -> compactDefinition(indexed.value) }
    }

    private fun compactDefinition(tool: ToolDefinition): ToolDefinition {
        val descriptionLimit = CRITICAL_DESCRIPTION_LIMITS[tool.name] ?: DEFAULT_DESCRIPTION_LIMIT
        return tool.copy(
            description = compactText(tool.description, descriptionLimit),
            parameters = tool.parameters.map { parameter ->
                parameter.copy(
                    description = compactText(parameter.description, PARAM_DESCRIPTION_LIMIT)
                )
            }
        )
    }

    private fun relevanceScore(
        tool: ToolDefinition,
        queryTerms: Set<String>,
        rawQuery: String
    ): Int {
        var score = if (tool.name in MUST_KEEP) 1_000 else 0
        val nameTerms = tool.name.lowercase().split('_', '-', '.')
        score += nameTerms.count { it in queryTerms } * 80

        if (queryTerms.isNotEmpty()) {
            val haystack = (tool.name + " " + tool.description.take(1_000)).lowercase()
            score += queryTerms.count { haystack.contains(it) } * 8
        }

        SPECIALIZED_INTENT_HINTS[tool.name]?.let { hints ->
            if (hints.any(rawQuery::contains)) score += 650
        }
        return score
    }

    private fun compactText(raw: String, limit: Int): String {
        val cleaned = raw
            .lineSequence()
            .map(String::trim)
            .filterNot { line ->
                line.isBlank() || line.all { ch -> ch in DECORATION_CHARS }
            }
            .joinToString(" ")
            .replace(WHITESPACE, " ")
            .trim()

        if (cleaned.length <= limit) return cleaned
        val tailSize = (limit / 5).coerceAtLeast(80)
        val headSize = (limit - tailSize - 16).coerceAtLeast(100)
        return cleaned.take(headSize).trimEnd() + " … [trimmed] … " + cleaned.takeLast(tailSize).trimStart()
    }

    private val SPECIALIZED_INTENT_HINTS = mapOf(
        "sms_reader_tool" to listOf(
            "sms", "text message", "inbox", "رسالة", "رسائل", "رسايل", "اس ام اس",
            "orange cash", "اورنج كاش", "أورنج كاش", "اورنچ كاش", "أورنچ كاش"
        ),
        "call_log_tool" to listOf(
            "call log", "calls", "phone calls", "سجل المكالمات", "مكالمات"
        ),
        "system_contacts" to listOf(
            "contact", "contacts", "رقم", "جهات الاتصال", "كونتاكت"
        ),
        "system_settings_tool" to listOf(
            "settings", "brightness", "timeout", "اعدادات", "إعدادات", "سطوع"
        ),
        "planner_tool" to listOf(
            "alarm", "calendar", "reminder", "منبه", "تقويم", "تذكير", "موعد"
        ),
        "visual_inspector" to listOf(
            "screen", "screenshot", "look at", "شاشة", "سكرين", "صورة الشاشة"
        )
    )

    // Unicode-aware tokenization keeps Arabic/non-Latin requests relevant at schema-selection time.
    private val WORD = Regex("[\\p{L}\\p{N}_\\-]+")
    private val WHITESPACE = Regex("\\s+")
    private val DECORATION_CHARS = setOf('━', '═', '─', '—', '-', '=', ' ', '│', '┈')
    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "this", "that", "from", "into", "use", "using",
        "fix", "make", "please", "عايز", "خلي", "اعمل", "حل", "في", "من", "على", "ده"
    )
}
