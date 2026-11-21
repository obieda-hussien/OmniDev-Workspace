package com.omnidev.workspace.ui.chat

import com.omnidev.workspace.data.model.MessageRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A snapshot of persisted rows, not the visible/possibly virtualized chat list. */
data class SessionTranscriptSnapshot(
    val title: String,
    val createdAt: Long,
    val turns: List<SessionTranscriptTurn>
)

data class SessionTranscriptTurn(
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    val attachmentNames: List<String> = emptyList(),
    val consoleEntries: List<AgentConsoleEntry> = emptyList(),
    val consoleUnavailable: Boolean = false
)

enum class SessionExportFormat(val extension: String) {
    TEXT("txt"), MARKDOWN("md")
}

/**
 * Ordered, lossless-with-respect-to-the-saved-archive transcript rendering.
 * Console events precede their assistant's answer, and no UI list-size limit is applied.
 * Existing storage limits (message/console field caps) cannot be undone by exporting.
 */
object SessionTranscriptExporter {
    fun render(
        snapshot: SessionTranscriptSnapshot,
        includeConsole: Boolean = true,
        format: SessionExportFormat = SessionExportFormat.TEXT
    ): String = buildString {
        val markdown = format == SessionExportFormat.MARKDOWN
        if (markdown) {
            appendLine("# ${snapshot.title.replace("\n", " ")}")
            appendLine()
            appendLine("OmniDev Workspace · Conversation archive · ${snapshot.turns.size} messages")
        } else {
            appendLine("OMNIDEV WORKSPACE — CONVERSATION")
            appendLine("Title: ${snapshot.title.replace("\n", " ")}")
            appendLine("Messages: ${snapshot.turns.size}")
        }
        appendLine("Created: ${date(snapshot.createdAt)}")
        appendLine()
        snapshot.turns.forEachIndexed { index, turn ->
            val role = when (turn.role) {
                MessageRole.USER -> "User"
                MessageRole.ASSISTANT -> "Omni AI"
                MessageRole.SYSTEM -> "System"
                MessageRole.TOOL -> "Tool"
            }
            val heading = "${index + 1}. $role · ${date(turn.timestamp)}"
            if (markdown) appendLine("## $heading")
            else appendLine("========== $heading ==========")
            appendLine()

            if (includeConsole && (turn.consoleEntries.isNotEmpty() || turn.consoleUnavailable)) {
                if (markdown) appendLine("**Agent Console**")
                else appendLine("AGENT CONSOLE")
                appendLine()
                val log = if (turn.consoleUnavailable && turn.consoleEntries.isEmpty()) {
                    "[The saved console data could not be decoded]"
                } else renderConsole(turn.consoleEntries)
                if (markdown) {
                    val longest = Regex("`+").findAll(log).maxOfOrNull { it.value.length } ?: 0
                    val fence = "`".repeat(maxOf(3, longest + 1))
                    appendLine("${fence}text")
                    appendLine(log)
                    appendLine(fence)
                } else appendLine(log)
                appendLine()
            }
            if (markdown) appendLine(if (turn.role == MessageRole.ASSISTANT) "**AI response**" else "**Message**")
            else appendLine(if (turn.role == MessageRole.ASSISTANT) "AI RESPONSE" else "MESSAGE")
            appendLine()
            appendLine(turn.content)
            if (turn.attachmentNames.isNotEmpty()) {
                appendLine()
                appendLine(if (markdown) "**Attachments**" else "ATTACHMENTS")
                turn.attachmentNames.forEach { appendLine("- $it") }
            }
            appendLine()
        }
    }

    internal fun renderConsole(entries: List<AgentConsoleEntry>): String = buildString {
        val start = entries.firstOrNull()?.timestamp ?: 0L
        entries.forEach { raw ->
            val entry = ConsoleRedactor.entry(raw)
            val offset = (entry.timestamp - start).coerceAtLeast(0)
            val body = when (entry) {
                is AgentConsoleEntry.ThinkingEntry -> "THINK  Reasoning... (iteration ${entry.iteration})"
                is AgentConsoleEntry.DeepThinkingEntry -> "DEEP   ${entry.snippet}"
                is AgentConsoleEntry.ToolEntry -> "RUN    ${entry.toolName}(${entry.fullParams})"
                is AgentConsoleEntry.ResultEntry -> "${if (entry.isError) "ERR" else "OK"}     ${entry.toolName}: ${entry.fullOutput}"
                is AgentConsoleEntry.TokenEntry -> "TOKENS ${entry.totalTokens} used${entry.budget?.let { " / $it budget" } ?: ""}"
                is AgentConsoleEntry.PhaseEntry -> "PHASE  ${entry.phase}${entry.detail?.let { " — $it" } ?: ""}"
                is AgentConsoleEntry.ReplyEntry -> "REPLY  Generating final response..."
                is AgentConsoleEntry.ErrorEntry -> "ERROR  ${entry.message}"
                is AgentConsoleEntry.ContextSummaryEntry -> "COMPR  ${entry.summary}"
            }
            appendLine("[+${offset}ms] $body")
        }
    }.trimEnd()

    private fun date(timestamp: Long) =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))
}
