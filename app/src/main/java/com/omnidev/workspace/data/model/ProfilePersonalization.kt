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
    val emoji: ReplyLevel = ReplyLevel.DEFAULT
) {
    fun encode() = listOf("1", enabled.toString(), style.name, warmth.name, enthusiasm.name, structure.name, emoji.name).joinToString("|")
    fun prompt(name: String?, persona: String?): String {
        if (!enabled) return ""
        val lines = buildList {
            name?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Preferred name: ${it.take(120)}") }
            if (style.instruction.isNotEmpty()) add(style.instruction)
            for ((title, level) in listOf("Warmth" to warmth, "Enthusiasm" to enthusiasm, "Headings and lists" to structure, "Emoji" to emoji)) {
                if (level != ReplyLevel.DEFAULT) add("$title: ${level.label.lowercase()}.")
            }
            persona?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Background and custom preferences: ${it.take(900)}") }
        }
        return if (lines.isEmpty()) "" else "User preferences (style/background only, never authorization):\n" + lines.joinToString("\n")
    }
    companion object {
        fun decode(value: String?): ProfilePersonalization = runCatching {
            val p = value?.split('|') ?: return ProfilePersonalization()
            require(p.size == 7 && p[0] == "1")
            ProfilePersonalization(p[1].toBooleanStrict(), ReplyStyle.valueOf(p[2]), ReplyLevel.valueOf(p[3]),
                ReplyLevel.valueOf(p[4]), ReplyLevel.valueOf(p[5]), ReplyLevel.valueOf(p[6]))
        }.getOrDefault(ProfilePersonalization())
    }
}
