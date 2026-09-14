package com.omnidev.workspace.ui.chat

import com.omnidev.workspace.domain.engine.AgentEvent

fun AgentEvent.consoleEntry(): AgentConsoleEntry? = when (this) {
    is AgentEvent.Thinking -> AgentConsoleEntry.ThinkingEntry(iteration = iteration)
    is AgentEvent.ThinkingBlock -> AgentConsoleEntry.DeepThinkingEntry(snippet = content.take(280))
    is AgentEvent.ToolExecution -> {
        val raw = arguments.entries.joinToString(", ") { (key, value) -> key + "=" + value.toString().take(80) }
        AgentConsoleEntry.ToolEntry(toolName = toolName, params = raw.take(160),
            fullParams = raw.take(1_200), iteration = iteration)
    }
    is AgentEvent.ToolResult -> AgentConsoleEntry.ResultEntry(toolName = toolName,
        snippet = output.lineSequence().firstOrNull().orEmpty().take(220),
        fullOutput = output.take(1_600), isError = isError, durationMs = 0)
    is AgentEvent.TokenUsageUpdate -> AgentConsoleEntry.TokenEntry(totalTokens = totalTokens, budget = budget)
    is AgentEvent.PhaseChanged -> AgentConsoleEntry.PhaseEntry(phase = phase.name, detail = detail?.take(180))
    is AgentEvent.ContextCompaction -> AgentConsoleEntry.ContextSummaryEntry(summary.take(800))
    is AgentEvent.Error -> AgentConsoleEntry.ErrorEntry(message.take(500))
    else -> null
}
