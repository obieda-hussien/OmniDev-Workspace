package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.ipc.RishShellManager
import com.omnidev.workspace.data.tools.ShizukuCommandTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object DirectTerminalTool {
    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "execute_terminal_command",
            description = "Executes an OS-level terminal command directly using the local bash/sh environment. " +
                "If a required CLI tool or Python package is missing, install it directly using `pkg install` or `pip install` before executing your command. " +
                "Has elevated privileges (uid 2000) if Shizuku is running.",
            parameters = listOf(
                ToolParameter("command", "string", "The shell command to execute.", required = true),
                ToolParameter("timeout", "string", "Optional timeout in milliseconds. Default 30000.", required = false)
            )
        )
    )

    suspend fun execute(context: android.content.Context, args: Map<String, String>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val command = args["command"] ?: return@withContext ToolExecutionResult("command is required.", isError = true)
        val timeoutMs = args["timeout"]?.toLongOrNull() ?: 30_000L

        // If Rish/Shizuku is available, run it. Otherwise, default runtime exec.
        val result = withTimeoutOrNull(timeoutMs) {
            try {
                if (ShizukuCommandTool.isAvailable()) {
                    val rishResult = RishShellManager(context).executeScript(command)
                    if (rishResult.isSuccess) {
                        ToolExecutionResult(rishResult.getOrNull() ?: "(no output)")
                    } else {
                        ToolExecutionResult("Error: ${rishResult.exceptionOrNull()?.message}", isError = true)
                    }
                } else {
                    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                    process.waitFor()
                    val output = process.inputStream.bufferedReader().readText().trim()
                    val error = process.errorStream.bufferedReader().readText().trim()

                    if (process.exitValue() == 0) {
                        ToolExecutionResult(output.ifBlank { "(no output)" })
                    } else {
                        ToolExecutionResult("Exit code ${process.exitValue()}: $error", isError = true)
                    }
                }
            } catch (e: Exception) {
                ToolExecutionResult("Execution failed: ${e.message}", isError = true)
            }
        }

        result ?: ToolExecutionResult("Command timed out after ${timeoutMs}ms.", isError = true)
    }
}
