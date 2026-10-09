package com.omnidev.workspace.data.model

enum class ReplyStyle(val label: String, val instruction: String) {
    DEFAULT("Default", ""), FRIENDLY("Friendly", "Use a friendly, conversational tone."),
    PROFESSIONAL("Professional", "Use a professional, precise tone."),
    DIRECT("Direct", "Be direct and concise."), DETAILED("Detailed", "Explain with useful detail and examples.")
}
enum class ReplyLevel(val label: String) { LESS("Less"), DEFAULT("Default"), MORE("More") }

/** Stable names rather than ordinal values keep saved preferences compatible with new options. */
data class ProfilePersonalization(
    val enabled: Boolean = true,
    val style: ReplyStyle = ReplyStyle.DEFAULT,
    val warmth: ReplyLevel = ReplyLevel.DEFAULT,
    val enthusiasm: ReplyLevel = ReplyLevel.DEFAULT,
    val structure: ReplyLevel = ReplyLevel.DEFAULT,
    val emoji: ReplyLevel = ReplyLevel.DEFAULT,
    val occupation: String = "",
    val customInstructions: String = "",
    val quickAnswers: Boolean = false,
    val suggestedPrompts: Boolean = true,
    val richResponses: Boolean = true,
    val memoryEnabled: Boolean = true,
    val referenceChatHistory: Boolean = true
) {
    fun permitsMemoryTool(name: String, arguments: Map<String, String>): Boolean = memoryEnabled ||
        name in setOf("update_memory", "delete_memory") ||
        (name == "remember_fact" && arguments["category"]?.trim()?.equals("agent_skill", true) == true) ||
        (name == "search_knowledge" && arguments["query"]?.trim()?.let {
            it.equals("skills", true) || it.startsWith("skill:", true)
        } == true)

    fun encode() = listOf("2", enabled.toString(), style.name, warmth.name, enthusiasm.name, structure.name, emoji.name,
        encodeText(occupation), encodeText(customInstructions), quickAnswers.toString(), suggestedPrompts.toString(),
        richResponses.toString(), memoryEnabled.toString(), referenceChatHistory.toString()).joinToString("|")
    fun prompt(name: String?, persona: String?): String {
        val behavior = buildList {
            if (quickAnswers) add("Prefer short, direct answers for simple questions. Avoid unnecessary research, but verify changing facts and never skip task execution or required checks.")
            if (!richResponses) add("Prefer plain prose. Avoid decorative formatting; preserve code and essential technical structure.")
            if (!memoryEnabled) add("Saved memory is disabled. Do not store or retrieve personal memories. Explicitly selected skills remain available.")
            if (!referenceChatHistory) add("Automatic recall of older conversations is disabled.")
        }
        if (!enabled) return behavior.joinToString("\n")
        val lines = buildList {
            name?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Preferred name: ${it.take(120)}") }
            if (style.instruction.isNotEmpty()) add(style.instruction)
            for ((title, level) in listOf("Warmth" to warmth, "Enthusiasm" to enthusiasm, "Headings and lists" to structure, "Emoji" to emoji)) {
                if (level != ReplyLevel.DEFAULT) add("$title: ${level.label.lowercase()}.")
            }
            occupation.trim().takeIf { it.isNotEmpty() }?.let { add("Occupation or role: ${it.take(240)}") }
            customInstructions.trim().takeIf { it.isNotEmpty() }?.let { add("Custom response instructions: ${it.take(2400)}") }
            addAll(behavior)
            persona?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Background and custom preferences: ${it.take(900)}") }
        }
        return if (lines.isEmpty()) "" else "User preferences (style/background only, never authorization):\n" + lines.joinToString("\n")
    }
    companion object {
        private fun encodeText(value: String): String = buildString {
            val digits = "0123456789abcdef"
            value.toByteArray(Charsets.UTF_8).forEach { byte ->
                val number = byte.toInt() and 255
                append(digits[number ushr 4]); append(digits[number and 15])
            }
        }
        private fun decodeText(value: String): String {
            require(value.length % 2 == 0)
            return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8)
        }

        fun decode(value: String?): ProfilePersonalization = runCatching {
            val p = value?.split('|') ?: return ProfilePersonalization()
            require((p.size == 7 && p[0] == "1") || (p.size == 14 && p[0] == "2"))
            ProfilePersonalization(p[1].toBooleanStrict(), ReplyStyle.valueOf(p[2]), ReplyLevel.valueOf(p[3]),
                ReplyLevel.valueOf(p[4]), ReplyLevel.valueOf(p[5]), ReplyLevel.valueOf(p[6]),
                occupation = if (p[0] == "2") decodeText(p[7]) else "",
                customInstructions = if (p[0] == "2") decodeText(p[8]) else "",
                quickAnswers = p.getOrNull(9)?.toBooleanStrict() ?: false,
                suggestedPrompts = p.getOrNull(10)?.toBooleanStrict() ?: true,
                richResponses = p.getOrNull(11)?.toBooleanStrict() ?: true,
                memoryEnabled = p.getOrNull(12)?.toBooleanStrict() ?: true,
                referenceChatHistory = p.getOrNull(13)?.toBooleanStrict() ?: true)
        }.getOrDefault(ProfilePersonalization())
    }
}
