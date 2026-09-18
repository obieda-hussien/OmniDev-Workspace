package com.omnidev.workspace.data.tools

import org.json.JSONObject

/**
 * Legacy adapter retained for stored workflows.
 *
 * New agent prompts should use `agent_runtime`; TierToolGate hides this duplicate
 * definition. Execution is delegated to EnvironmentSetupManager so old calls get
 * the same real Termux RunCommandService backend instead of app-UID ProcessBuilder.
 */
object DirectTerminalTool {

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "direct_terminal",
            description = "Legacy terminal alias. New calls should use agent_runtime."
        )
    )

    suspend fun executeResult(params: JSONObject): ToolExecutionResult {
        val rawCommand = params.opt("command")
        val command = when (rawCommand) {
            is Map<*, *> -> rawCommand["command"]?.toString()
                ?: rawCommand["code"]?.toString()
                ?: rawCommand["script"]?.toString()
                ?: ""
            is JSONObject -> rawCommand.optString(
                "command",
                rawCommand.optString("code", rawCommand.optString("script", ""))
            )
            null, JSONObject.NULL -> params.optString("code", params.optString("script", ""))
            else -> rawCommand.toString()
        }.trim()

        if (command.isBlank()) {
            return ToolExecutionResult(
                output = "Error: terminal command is empty",
                isError = true,
                classification = "INVALID_ARGUMENT",
                backend = "legacy-terminal"
            )
        }

        val cwd = when (rawCommand) {
            is Map<*, *> -> rawCommand["cwd"]?.toString()
            is JSONObject -> rawCommand.optString("cwd").takeIf { it.isNotBlank() }
            else -> params.optString("cwd").takeIf { it.isNotBlank() }
        }

        AndroidPrivilegedCommandRouter.executeIfNeeded(command)?.let { routed ->
            return routed
        }

        return EnvironmentSetupManager.executeShell(command, cwd)
    }

    /** Source-compatible adapter for old persisted workflows that only stored text output. */
    suspend fun execute(params: JSONObject): String = executeResult(params).output
}
