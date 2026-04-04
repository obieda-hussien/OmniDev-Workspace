package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.ipc.LauncherConnectionManager
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import org.json.JSONObject

/**
 * Controls the active default launcher through OmniDev universal launcher IPC.
 *
 * Supported actions:
 *  - go_home        — Navigate to the launcher home screen.
 *  - open_drawer    — Open the full-screen app drawer.
 *  - close_drawer   — Dismiss the app drawer.
 *  - launch_app     — Launch a specific package by name (requires `package_name`).
 *  - open_widgets   — Open the system/launcher Widget Picker overlay so the user
 *                     (or the agent on their behalf) can browse and pin widgets.
 *                     Delegates to [IOmniLauncherInterface.openWidgetPicker] — added
 *                     in Launcher IPC contract v2.
 *
 * All calls are routed through [LauncherConnectionManager.getLauncherInterface] which
 * maintains a live Binder connection to whichever launcher is currently the system
 * default. If the default launcher does not implement the OmniDev IPC service, an
 * informative error is returned instead of crashing.
 */
object LauncherControlTool {
    private const val KEYEVENT_HOME = 3

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "system_launcher_tool",
            description = "Controls the Android OS user interface via the default Launcher. " +
                "Use this to go home, open the app drawer, launch apps, or open the Widget " +
                "Picker natively without requiring Accessibility Service.",
            parameters = listOf(
                ToolParameter(
                    name = "action",
                    type = "string",
                    description = "One of: go_home, open_drawer, close_drawer, launch_app, open_widgets",
                    required = true
                ),
                ToolParameter(
                    name = "package_name",
                    type = "string",
                    description = "Target package name — required only when action=launch_app",
                    required = false
                )
            )
        )
    )

    suspend fun execute(action: String, args: Map<String, String>): ToolExecutionResult {
        val launcher = LauncherConnectionManager.getLauncherInterface()
            ?: return fallbackWhenLauncherUnavailable(action)

        val normalized = action.trim().lowercase()
        val succeeded = runCatching {
            when (normalized) {
                "go_home"       -> launcher.goToHomeScreen()
                "open_drawer"   -> launcher.openAppDrawer()
                "close_drawer"  -> launcher.closeAppDrawer()

                "launch_app" -> {
                    val packageName = args["package_name"]?.trim().orEmpty()
                    if (packageName.isBlank()) {
                        return ToolExecutionResult(
                            output = errorJson("Missing required parameter: package_name for action=launch_app"),
                            isError = true
                        )
                    }
                    launcher.launchPackage(packageName)
                }

                // ── Widget Picker (v2 IPC contract) ─────────────────────────────────
                "open_widgets" -> {
                    // openWidgetPicker() was added in IOmniLauncherInterface v2.
                    // If the bound launcher is still on v1 (missing the method), the Binder
                    // will throw a DeadObjectException or return false depending on the
                    // IPC stub implementation — both are handled gracefully by the
                    // runCatching block below and surfaced as a clean error message.
                    launcher.openWidgetPicker()
                }

                else -> {
                    return ToolExecutionResult(
                        output = errorJson(
                            "Unsupported action '$normalized'. " +
                            "Valid actions: go_home, open_drawer, close_drawer, launch_app, open_widgets"
                        ),
                        isError = true
                    )
                }
            }
        }.getOrElse { ex ->
            // Surface IPC-level failures (DeadObjectException, SecurityException, etc.)
            // with enough detail for the agent to take corrective action.
            val hint = when {
                normalized == "open_widgets" &&
                ex.message?.contains("UNKNOWN_TRANSACTION", ignoreCase = true) == true ->
                    " The connected launcher may not yet implement IOmniLauncherInterface v2 " +
                    "(openWidgetPicker). Upgrade the launcher to a version that supports OmniDev IPC v2."
                else -> " (${ex::class.simpleName}: ${ex.message ?: "unknown error"})"
            }
            return ToolExecutionResult(
                output = errorJson("Launcher IPC call failed for action '$normalized'.$hint"),
                isError = true
            )
        }

        return if (succeeded) {
            ToolExecutionResult(successJson(normalized))
        } else {
            ToolExecutionResult(
                output = errorJson("Launcher rejected action '$normalized'. " +
                    "The launcher returned false — it may not support this command."),
                isError = true
            )
        }
    }

    // ──────────────────────────────────────────────
    //  JSON helpers
    // ──────────────────────────────────────────────

    private fun successJson(action: String): String = JSONObject()
        .put("ok", true)
        .put("action", action)
        .put("message", "Launcher action executed successfully")
        .toString()

    private fun errorJson(message: String): String = JSONObject()
        .put("ok", false)
        .put("error", message)
        .toString()

    private suspend fun fallbackWhenLauncherUnavailable(action: String): ToolExecutionResult {
        val normalized = action.trim().lowercase()
        if (normalized == "go_home" && PrivilegedExecutionManager.isShizukuReady()) {
            val result = ShizukuCommandTool.execute("input keyevent $KEYEVENT_HOME")
            if (result is ShizukuResult.Success || result is ShizukuResult.PartialSuccess) {
                return ToolExecutionResult(successJson(normalized))
            }
            return ToolExecutionResult(
                output = errorJson("Failed to execute home action via Shizuku fallback."),
                isError = true
            )
        }

        return ToolExecutionResult(
            output = errorJson("Current launcher does not support OmniDev protocol. " +
                "Ensure the default launcher has the OmniDev IPC service enabled."),
            isError = true
        )
    }
}
