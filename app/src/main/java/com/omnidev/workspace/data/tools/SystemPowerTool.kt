package com.omnidev.workspace.data.tools

import android.content.Context
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SystemPowerTool — Provides the agent with the ability to control device power
 * states, including rebooting, shutting down, and entering recovery/bootloader.
 * Requires Shizuku/Root access.
 */
object SystemPowerTool {

    suspend fun execute(action: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        val command = when (action.lowercase()) {
            "reboot" -> "reboot"
            "shutdown" -> "reboot -p"
            "recovery" -> "reboot recovery"
            "bootloader" -> "reboot bootloader"
            "soft_reboot" -> "setprop ctl.restart zygote"
            else -> return@withContext ToolExecutionResult("Unknown power action '$action'.", isError = true)
        }

        val result = PrivilegedExecutionManager.executeCommand(command)
        if (result.isSuccess) {
            ToolExecutionResult("✅ Power command '$action' issued successfully.")
        } else {
            ToolExecutionResult("Failed to execute power command: ${result.exceptionOrNull()?.message}", isError = true)
        }
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "system_power",
            description = "Control device power states. Actions: 'reboot', 'shutdown', 'recovery', 'bootloader', 'soft_reboot'. Requires Shizuku/Root.",
            parameters = listOf(
                ToolParameter("action", "string", "The power action to perform.", required = true)
            )
        )
    )
}
