package com.omnidev.workspace.ui.chat

import com.omnidev.workspace.domain.engine.AgentEvent

fun AgentEvent.consoleEntry(): AgentConsoleEntry? = when (this) {
    is AgentEvent.Thinking -> AgentConsoleEntry.ThinkingEntry(iteration = iteration)
    is AgentEvent.ThinkingBlock -> AgentConsoleEntry.DeepThinkingEntry(snippet = content)
    is AgentEvent.ToolExecution -> AgentConsoleEntry.ToolEntry(toolName = toolName,
        params = arguments.toString(), fullParams = arguments.toString(), iteration = iteration)
    is AgentEvent.ToolResult -> AgentConsoleEntry.ResultEntry(toolName = toolName,
        snippet = output.take(500), fullOutput = output, isError = isError, durationMs = 0)
    is AgentEvent.TokenUsageUpdate -> AgentConsoleEntry.TokenEntry(totalTokens = totalTokens, budget = budget)
    is AgentEvent.PhaseChanged -> AgentConsoleEntry.PhaseEntry(phase = phase.name, detail = detail)
    is AgentEvent.ContextCompaction -> AgentConsoleEntry.ContextSummaryEntry(summary)
    is AgentEvent.Error -> AgentConsoleEntry.ErrorEntry(message)
    else -> null
}
