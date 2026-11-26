package com.omnidev.workspace.data.integration

import java.util.Locale

internal object TelegramConversationPolicy {
    data class Command(val name: String, val argument: String = "", val target: String? = null)

    private val command = Regex("^/([a-zA-Z][a-zA-Z0-9_]*)(?:@([a-zA-Z0-9_]+))?(?=\\s|$)")
    private val sessionAlias = Regex("^session(?:\\s+|$)", RegexOption.IGNORE_CASE)
    private val reasoning = Regex("(?is)(?:<|&lt;)(think|thinking)(?:>|&gt;).*?(?:(?:<|&lt;)/\\1(?:>|&gt;)|\\z)")
    private val toolClaim = Regex("(?im)^[ \\t]*(?:Selected capability|Tool Action|Verification Result):[ \\t]*(?!None[ \\t]*\\r?$)\\S")
    private val reportField = Regex("(?im)^[ \\t]*(?:Objective|Selected capability|Tool Action|Verification Result|Blockers):[^\\r\\n]*")
    private val deviceQuery = Regex(
        """(?i)^(?:(?:which|what) app (?:is open(?=\s*(?:[?.!]|$)|\s+(?:on my (?:phone|device)|right now|now|currently)\b)|""" +
            """is using the most storage\b)|list (?:my )?installed apps\b|""" +
            """(?:طب\s+)?(?:انا\s+فاتح\s+[اإ]ي(?:ه)?\s+على\s+تليفوني|انا\s+فاتح\s+[اإ]ي(?:ه)?\s+علي\s+تلفوني|""" +
            """عندي\s+تطبيقات\s+[اإ]ي(?:ه)?|[اأ]عل[ىي]\s+تطبيق\s+مساحة))"""
    )

    fun needsDeviceEvidence(text: String): Boolean = deviceQuery.containsMatchIn(text.trimStart())

    fun parse(text: String): Command? {
        val input = text.trim()
        val match = command.find(input) ?: return null
        val name = match.groupValues[1].lowercase(Locale.ROOT)
        val target = match.groupValues[2].ifEmpty { null }
        val argument = input.substring(match.range.last + 1).trim()
        if (name == "new") {
            val alias = sessionAlias.find(argument)
            if (alias != null) return Command("new_session", argument.substring(alias.range.last + 1).trim(), target)
        }
        if (name == "mode" && argument.isNotEmpty()) {
            val mode = argument.lowercase(Locale.ROOT)
            if (mode in setOf("chat", "agent", "swarm", "team")) {
                return Command("mode_" + if (mode == "team") "swarm" else mode, target = target)
            }
        }
        return Command(name, argument, target)
    }

    fun visibleReply(text: String, chatHasNoTools: Boolean = false): String {
        val visible = reasoning.replace(text, "")
        if (chatHasNoTools && toolClaim.containsMatchIn(visible)) {
            return "Chat mode did not run any tools. Use /mode to check the active mode."
        }
        return reportField.replace(visible, "").trim()
    }
}
