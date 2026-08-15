package com.omnidev.workspace.data.tools

import com.omnidev.workspace.core.policy.ConfirmationGate
import com.omnidev.workspace.core.policy.ConfirmationKind
import com.omnidev.workspace.data.ipc.RishShellManager

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

    suspend fun execute(context: android.content.Context, args: Map<String, String>, confirmationGate: com.omnidev.workspace.core.policy.ConfirmationGate): ToolExecutionResult = withContext(Dispatchers.IO) {
        val command = args["command"] ?: return@withContext ToolExecutionResult("command is required.", isError = true)
        val timeoutMs = args["timeout"]?.toLongOrNull() ?: 30_000L

        // Route through ConfirmationGate to ensure user authorization and proper audit logging for raw shell execution
        val gateResult = confirmationGate.request(
            ConfirmationKind.SHIZUKU_COMMAND,
            preview = command,
            diffContent = null
        )
        if (!gateResult) {
            return@withContext ToolExecutionResult("Terminal execution denied by user.", isError = true)
        }

        // If Rish/Shizuku is available, run it. Otherwise, default runtime exec.
        val result = withTimeoutOrNull(timeoutMs) {
            try {
                if (com.omnidev.workspace.data.tools.ShizukuCommandTool.isAvailable()) {
                    val rishResult = RishShellManager(context).executeScript(command)
                    if (rishResult.isSuccess) {
                        ToolExecutionResult(rishResult.getOrNull() ?: "(no output)")
                    } else {
                        ToolExecutionResult("Error: ${rishResult.exceptionOrNull()?.message}", isError = true)
                    }
                } else {
                    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))

                    val stdoutBuf = StringBuilder()
                    val stderrBuf = StringBuilder()

                    val stdoutThread = Thread {
                        try {
                            process.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                                val buf = CharArray(4096)
                                var n: Int
                                while (r.read(buf).also { n = it } != -1) stdoutBuf.append(buf, 0, n)
                            }
                        } catch (_: Exception) {}
                    }.apply { isDaemon = true; start() }

                    val stderrThread = Thread {
                        try {
                            process.errorStream.bufferedReader(Charsets.UTF_8).use { r ->
                                val buf = CharArray(4096)
                                var n: Int
                                while (r.read(buf).also { n = it } != -1) stderrBuf.append(buf, 0, n)
                            }
                        } catch (_: Exception) {}
                    }.apply { isDaemon = true; start() }

                    process.waitFor()
                    stdoutThread.join(3_000L)
                    stderrThread.join(3_000L)

                    val output = stdoutBuf.toString().trim()
                    val error = stderrBuf.toString().trim()

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