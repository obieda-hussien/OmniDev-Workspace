package com.omnidev.workspace.data.ipc

import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolExecutionResult
import com.omnidev.workspace.data.tools.ToolParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OmniCoreAgentTool — exposes all [PrivilegedExecutionManager] capabilities as a
 * single agent-callable tool (`privileged_tool`) registered in
 * [com.omnidev.workspace.data.tools.CompositeToolManager].
 *
 * This is the bridge between the AI agent's ReAct loop and the privileged-execution
 * backend (Shizuku / root). The agent selects an `action` and supplies the
 * action-specific parameters; this class validates and routes the call.
 *
 * ### Supported actions
 * | Action           | Description                                        |
 * |------------------|----------------------------------------------------|
 * | `status`         | Check Shizuku / root availability                  |
 * | `shell`          | Run an arbitrary shell command (returns stdout)    |
 * | `dumpsys`        | Dump a system service (battery, wifi, package …)   |
 * | `getprop`        | Read a system property                             |
 * | `setprop`        | Set a system property (root required)              |
 * | `meminfo`        | Full /proc/meminfo report                          |
 * | `cpuinfo`        | CPU info from /proc/cpuinfo                        |
 * | `ps`             | List running processes                             |
 * | `netstat`        | Network connection table                           |
 * | `settings_get`   | Read an Android system/secure/global setting       |
 * | `settings_put`   | Write an Android system/secure/global setting      |
 * | `settings_list`  | List all keys in a settings namespace              |
 * | `pkg_list`       | List installed packages (with optional filter)     |
 * | `pkg_install`    | Install an APK file                                |
 * | `pkg_uninstall`  | Uninstall a package                                |
 * | `pkg_grant`      | Grant a permission to a package                    |
 * | `pkg_revoke`     | Revoke a permission from a package                 |
 * | `pkg_info`       | Detailed package dump (pm dump)                    |
 * | `app_stop`       | Force-stop an application                          |
 * | `app_launch`     | Start an activity / component via am start         |
 * | `app_broadcast`  | Send a broadcast intent via am broadcast           |
 * | `input_tap`      | Tap at screen coordinates                          |
 * | `input_swipe`    | Swipe gesture between two coordinates              |
 * | `input_text`     | Type text via input text                           |
 * | `input_keyevent` | Send a keyevent (keycode integer)                  |
 * | `screencap`      | Capture the screen to a file                       |
 * | `wm`             | Window manager: get/set display size or density    |
 * | `svc`            | Enable / disable a hardware service                |
 */
object OmniCoreAgentTool {

    private const val NETSTAT_CMD =
        "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null || netstat -an 2>/dev/null || ss -an 2>/dev/null"

    // ─────────────────────────────────────────────────────────────────────
    // Tool definition
    // ─────────────────────────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "privileged_tool",
            description = """
Execute privileged Android OS operations via Shizuku (preferred) or root/SU fallback.
Use this for any action that requires elevated system access beyond standard Android APIs.

Actions and their required parameters:
• status              — Check if Shizuku or root is available. No extra params.
• shell               — command: arbitrary shell command, returns full stdout.
• dumpsys             — service: service name (battery, wifi, package, activity, window, input, notification, power, connectivity).
• getprop             — key: system property key (e.g. ro.build.version.sdk).
• setprop             — key, value: set a system property (root usually required).
• meminfo             — No extra params. Returns /proc/meminfo.
• cpuinfo             — No extra params. Returns CPU information.
• ps                  — No extra params. List running processes.
• netstat             — No extra params. Network connection table.
• settings_get        — namespace (system/secure/global), key.
• settings_put        — namespace, key, value.
• settings_list       — namespace (system/secure/global). Lists all keys.
• pkg_list            — filter (optional substring). Lists installed packages.
• pkg_install         — apk_path: full path to the APK file to install.
• pkg_uninstall       — package: package name to uninstall.
• pkg_grant           — package, permission: grant a permission to a package.
• pkg_revoke          — package, permission: revoke a permission from a package.
• pkg_info            — package: detailed pm dump for a package.
• app_stop            — package: force-stop an application.
• app_launch          — component: full component (com.pkg/.Activity) or am-start expression.
• app_broadcast       — action: broadcast intent action string.
• input_tap           — x, y: screen coordinates (integers).
• input_swipe         — x1, y1, x2, y2, duration_ms: swipe gesture.
• input_text          — text: text to type.
• input_keyevent      — keycode: integer keycode (e.g. 4=BACK, 3=HOME, 26=POWER).
• screencap           — output_path (default /data/local/tmp/omnidev_cap.png).
• wm                  — sub_command (size/density/size reset/density reset), value (optional new value).
• svc                 — service (wifi/data/bluetooth/nfc/power), action (enable/disable).
""".trimIndent(),
            parameters = listOf(
                ToolParameter("action", "string", "The privileged action to perform (see description).", required = true),
                ToolParameter("command", "string", "Shell command (for action=shell).", required = false),
                ToolParameter("service", "string", "Service name for dumpsys/svc.", required = false),
                ToolParameter("key", "string", "Property key or settings key.", required = false),
                ToolParameter("value", "string", "Value to write (setprop/settings_put/wm).", required = false),
                ToolParameter("namespace", "string", "Settings namespace: system, secure, global.", required = false),
                ToolParameter("package", "string", "Package name.", required = false),
                ToolParameter("apk_path", "string", "Path to APK file for pkg_install.", required = false),
                ToolParameter("permission", "string", "Android permission string for pkg_grant/pkg_revoke.", required = false),
                ToolParameter("component", "string", "Activity component name for app_launch.", required = false),
                ToolParameter("broadcast_action", "string", "Intent action for app_broadcast.", required = false),
                ToolParameter("x", "string", "X coordinate (input_tap).", required = false),
                ToolParameter("y", "string", "Y coordinate (input_tap).", required = false),
                ToolParameter("x1", "string", "Swipe start X (input_swipe).", required = false),
                ToolParameter("y1", "string", "Swipe start Y (input_swipe).", required = false),
                ToolParameter("x2", "string", "Swipe end X (input_swipe).", required = false),
                ToolParameter("y2", "string", "Swipe end Y (input_swipe).", required = false),
                ToolParameter("duration_ms", "string", "Swipe duration in ms (input_swipe, default 300).", required = false),
                ToolParameter("text", "string", "Text to type (input_text).", required = false),
                ToolParameter("keycode", "string", "Integer keycode (input_keyevent).", required = false),
                ToolParameter("output_path", "string", "Screenshot output path (screencap).", required = false),
                ToolParameter("sub_command", "string", "wm subcommand: size, density, size reset, density reset.", required = false),
                ToolParameter("svc_action", "string", "svc action: enable or disable.", required = false)
            )
        )
    )

    // ─────────────────────────────────────────────────────────────────────
    // Execution router
    // ─────────────────────────────────────────────────────────────────────

    suspend fun execute(
        action: String,
        args: Map<String, String>
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        when (action.lowercase().trim()) {

            "status" -> {
                val shizuku = PrivilegedExecutionManager.isShizukuReady()
                val root = PrivilegedExecutionManager.isRootAvailable()
                val backend = when {
                    shizuku -> "✅ Shizuku (active)"
                    root -> "⚠️ Root/SU only (Shizuku not available)"
                    else -> "❌ No privileged backend available"
                }
                ToolExecutionResult("Privileged backend: $backend\nShizuku ready: $shizuku | Root available: $root")
            }

            "shell" -> {
                val cmd = args["command"] ?: return@withContext err("shell requires 'command'")
                PrivilegedExecutionManager.executeCommand(cmd).toToolResult()
            }

            "dumpsys" -> {
                val svc = args["service"] ?: return@withContext err("dumpsys requires 'service'")
                PrivilegedExecutionManager.dumpSysInfo(svc).toToolResult()
            }

            "getprop" -> {
                val k = args["key"] ?: return@withContext err("getprop requires 'key'")
                PrivilegedExecutionManager.getSystemProperty(k).toToolResult()
            }

            "setprop" -> {
                val k = args["key"] ?: return@withContext err("setprop requires 'key'")
                val v = args["value"] ?: return@withContext err("setprop requires 'value'")
                PrivilegedExecutionManager.setSystemProperty(k, v).toToolResult()
            }

            "meminfo" ->
                PrivilegedExecutionManager.executeCommand("cat /proc/meminfo").toToolResult()

            "cpuinfo" ->
                PrivilegedExecutionManager.executeCommand(
                    "cat /proc/cpuinfo | grep -E 'processor|Hardware|model name|cpu MHz' | head -32"
                ).toToolResult()

            "ps" ->
                PrivilegedExecutionManager.listRunningProcesses().toToolResult()

            "netstat" ->
                PrivilegedExecutionManager.executeCommand(NETSTAT_CMD).toToolResult()

            "settings_get" -> {
                val ns = args["namespace"] ?: return@withContext err("settings_get requires 'namespace'")
                val k = args["key"] ?: return@withContext err("settings_get requires 'key'")
                PrivilegedExecutionManager.readSetting(ns, k).toToolResult()
            }

            "settings_put" -> {
                val ns = args["namespace"] ?: return@withContext err("settings_put requires 'namespace'")
                val k = args["key"] ?: return@withContext err("settings_put requires 'key'")
                val v = args["value"] ?: return@withContext err("settings_put requires 'value'")
                PrivilegedExecutionManager.writeSetting(ns, k, v).toToolResult()
            }

            "settings_list" -> {
                val ns = args["namespace"] ?: return@withContext err("settings_list requires 'namespace'")
                PrivilegedExecutionManager.listSettings(ns).toToolResult()
            }

            "pkg_list" -> {
                val filter = args["filter"] ?: ""
                PrivilegedExecutionManager.queryPackages(filter).toToolResult()
            }

            "pkg_install" -> {
                val path = args["apk_path"] ?: return@withContext err("pkg_install requires 'apk_path'")
                PrivilegedExecutionManager.installApk(path).toToolResult()
            }

            "pkg_uninstall" -> {
                val pkg = args["package"] ?: return@withContext err("pkg_uninstall requires 'package'")
                PrivilegedExecutionManager.uninstallPackage(pkg).toToolResult()
            }

            "pkg_grant" -> {
                val pkg = args["package"] ?: return@withContext err("pkg_grant requires 'package'")
                val perm = args["permission"] ?: return@withContext err("pkg_grant requires 'permission'")
                PrivilegedExecutionManager.grantPermission(pkg, perm).toToolResult()
            }

            "pkg_revoke" -> {
                val pkg = args["package"] ?: return@withContext err("pkg_revoke requires 'package'")
                val perm = args["permission"] ?: return@withContext err("pkg_revoke requires 'permission'")
                PrivilegedExecutionManager.revokePermission(pkg, perm).toToolResult()
            }

            "pkg_info" -> {
                val pkg = args["package"] ?: return@withContext err("pkg_info requires 'package'")
                PrivilegedExecutionManager.getPackageInfo(pkg).toToolResult()
            }

            "app_stop" -> {
                val pkg = args["package"] ?: return@withContext err("app_stop requires 'package'")
                PrivilegedExecutionManager.forceStopApp(pkg).toToolResult()
            }

            "app_launch" -> {
                val comp = args["component"] ?: return@withContext err("app_launch requires 'component'")
                PrivilegedExecutionManager.launchComponent(comp).toToolResult()
            }

            "app_broadcast" -> {
                val broadcastAction = args["broadcast_action"]
                    ?: return@withContext err("app_broadcast requires 'broadcast_action'")
                PrivilegedExecutionManager.sendBroadcast(broadcastAction).toToolResult()
            }

            "input_tap" -> {
                val x = args["x"]?.toIntOrNull() ?: return@withContext err("input_tap requires integer 'x'")
                val y = args["y"]?.toIntOrNull() ?: return@withContext err("input_tap requires integer 'y'")
                PrivilegedExecutionManager.injectTap(x, y).toToolResult()
            }

            "input_swipe" -> {
                val x1 = args["x1"]?.toIntOrNull() ?: return@withContext err("input_swipe requires 'x1'")
                val y1 = args["y1"]?.toIntOrNull() ?: return@withContext err("input_swipe requires 'y1'")
                val x2 = args["x2"]?.toIntOrNull() ?: return@withContext err("input_swipe requires 'x2'")
                val y2 = args["y2"]?.toIntOrNull() ?: return@withContext err("input_swipe requires 'y2'")
                val dur = args["duration_ms"]?.toIntOrNull() ?: 300
                PrivilegedExecutionManager.injectSwipe(x1, y1, x2, y2, dur).toToolResult()
            }

            "input_text" -> {
                val text = args["text"] ?: return@withContext err("input_text requires 'text'")
                PrivilegedExecutionManager.injectText(text).toToolResult()
            }

            "input_keyevent" -> {
                val kc = args["keycode"]?.toIntOrNull()
                    ?: return@withContext err("input_keyevent requires integer 'keycode'")
                PrivilegedExecutionManager.injectKeyEvent(kc).toToolResult()
            }

            "screencap" -> {
                val path = args["output_path"] ?: "/data/local/tmp/omnidev_cap.png"
                PrivilegedExecutionManager.captureScreen(path).toToolResult()
            }

            "wm" -> {
                val sub = args["sub_command"] ?: return@withContext err("wm requires 'sub_command'")
                val v = args["value"] ?: ""
                PrivilegedExecutionManager.windowManager(sub, v).toToolResult()
            }

            "svc" -> {
                val svc = args["service"] ?: return@withContext err("svc requires 'service'")
                val act = args["svc_action"]
                    ?: return@withContext err("svc requires 'svc_action' (enable or disable)")
                PrivilegedExecutionManager.controlService(svc, act).toToolResult()
            }

            else -> err("Unknown privileged_tool action: '$action'. See tool description for supported actions.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun err(msg: String) = ToolExecutionResult(msg, isError = true)

    private fun Result<String>.toToolResult(): ToolExecutionResult =
        fold(
            onSuccess = { ToolExecutionResult(it.ifBlank { "(no output)" }) },
            onFailure = { ToolExecutionResult("❌ ${it.message}", isError = true) }
        )
}
