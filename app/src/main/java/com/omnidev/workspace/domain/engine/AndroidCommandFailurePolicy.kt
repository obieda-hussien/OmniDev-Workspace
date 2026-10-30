package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.tools.ToolExecutionResult

/** A missing Android shell binary is a command-level blocker, not a retryable backend outage. */
internal object AndroidCommandFailurePolicy {
    private val androidShellTools = setOf(
        "privileged_tool",
        "run_terminal",
        "shizuku_command",
        "direct_terminal",
        "advanced_terminal"
    )

    fun shouldStop(toolName: String, result: ToolExecutionResult): Boolean =
        toolName in androidShellTools && result.isError &&
            result.classification == "COMMAND_NOT_FOUND"
}
