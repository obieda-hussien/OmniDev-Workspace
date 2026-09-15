package com.omnidev.workspace.ui.chat

/** Redacts common credential fields before console rendering or persistence. */
object ConsoleRedactor {
    private val assignment = Regex("""(?i)(["']?\b(?:password|passwd|api[_-]?key|access[_-]?token|refresh[_-]?token|secret|authorization|cookie|set-cookie)\b["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^\r\n,;&]+)""")
    private val bearer = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")
    fun text(value: String): String = value.replace(assignment) { it.groupValues[1] + "[REDACTED]" }
        .replace(bearer, "Bearer [REDACTED]")

    fun entry(entry: AgentConsoleEntry): AgentConsoleEntry = when (entry) {
        is AgentConsoleEntry.ToolEntry -> entry.copy(params = text(entry.params), fullParams = text(entry.fullParams).take(16_000))
        is AgentConsoleEntry.ResultEntry -> entry.copy(snippet = text(entry.snippet), fullOutput = text(entry.fullOutput).take(32_000))
        is AgentConsoleEntry.DeepThinkingEntry -> entry.copy(snippet = text(entry.snippet).take(32_000))
        is AgentConsoleEntry.ErrorEntry -> entry.copy(message = text(entry.message))
        is AgentConsoleEntry.PhaseEntry -> entry.copy(detail = entry.detail?.let(::text))
        is AgentConsoleEntry.ContextSummaryEntry -> entry.copy(summary = text(entry.summary))
        else -> entry
    }
}
