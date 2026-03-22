package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.ipc.LauncherConnectionManager
import org.json.JSONObject

/**
 * Controls the active default launcher through OmniDev universal launcher IPC.
 */
object LauncherControlTool {

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "system_launcher_tool",
            description = "Controls the Android OS user interface via the default Launcher. " +
                "Use this to go home, open the app drawer, or launch apps natively without accessibility.",
            parameters = listOf(
                ToolParameter(
                    name = "action",
                    type = "string",
                    description = "One of: go_home, open_drawer, close_drawer, launch_app",
                    required = true
                ),
                ToolParameter(
                    name = "package_name",
                    type = "string",
                    description = "Package name when action=launch_app",
                    required = false
                )
            )
        )
    )

    fun execute(action: String, args: Map<String, String>): ToolExecutionResult {
        val launcher = LauncherConnectionManager.getLauncherInterface()
            ?: return ToolExecutionResult(
                output = errorJson("Current launcher does not support OmniDev protocol"),
                isError = true
            )

        val normalized = action.trim().lowercase()
        val succeeded = runCatching {
            when (normalized) {
                "go_home" -> launcher.goToHomeScreen()
                "open_drawer" -> launcher.openAppDrawer()
                "close_drawer" -> launcher.closeAppDrawer()
                "launch_app" -> {
                    val packageName = args["package_name"]?.trim().orEmpty()
                    if (packageName.isBlank()) {
                        return ToolExecutionResult(
                            output = errorJson("Missing required parameter: package_name"),
                            isError = true
                        )
                    }
                    launcher.launchPackage(packageName)
                }
                else -> {
                    return ToolExecutionResult(
                        output = errorJson("Unsupported action '$normalized'"),
                        isError = true
                    )
                }
            }
        }.getOrElse {
            return ToolExecutionResult(
                output = errorJson("Launcher IPC call failed: ${it.message ?: "unknown error"}"),
                isError = true
            )
        }

        return if (succeeded) {
            ToolExecutionResult(successJson(normalized))
        } else {
            ToolExecutionResult(
                output = errorJson("Launcher rejected action '$normalized'"),
                isError = true
            )
        }
    }

    private fun successJson(action: String): String = JSONObject()
        .put("ok", true)
        .put("action", action)
        .put("message", "Launcher action executed successfully")
        .toString()

    private fun errorJson(message: String): String = JSONObject()
        .put("ok", false)
        .put("error", message)
        .toString()
}
