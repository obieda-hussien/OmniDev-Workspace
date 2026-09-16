package com.omnidev.workspace.data.ipc

import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolExecutionResult
import com.omnidev.workspace.data.tools.ToolParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Agent-facing privileged Android tool.
 *
 * Programmatic Android privilege is provided by the Shizuku UserService backend.
 * rish remains a separate Termux-side terminal integration and is never treated as
 * healthy unless its functional shell/root smoke-test succeeds.
 */
object OmniCoreAgentTool {

    private const val NETSTAT_CMD =
        "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null || netstat -an 2>/dev/null || ss -an 2>/dev/null"

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "privileged_tool",
            description = """
Execute Android privileged operations through the supported Shizuku UserService backend, with root fallback where available.

Core actions:
• status — functionally probe Shizuku/rish/root and report independent states.
• shell — command: privileged Android shell command.
• dumpsys — service.
• getprop / setprop — key, optional value.
• meminfo / cpuinfo / ps / netstat.
• settings_get / settings_put / settings_list — namespace, key/value as needed.
• pkg_list / pkg_install / pkg_uninstall / pkg_grant / pkg_revoke / pkg_info.
• app_stop / app_launch / app_broadcast.
• input_tap / input_swipe / input_text / input_keyevent.
• screencap / wm / svc.
• termux_hints / termux_install.

rish terminal integration:
• rish_setup — install/repair official-form rish inside Termux private storage and run `rish -c id`.
• rish_exec — command: execute through the verified Termux rish launcher.
• rish_script — script: execute a multi-line shell script through verified rish.
• rish_session — commands: newline-separated commands; stops on persistent infrastructure failure.

RISH GUARDRAILS:
- rish files belong under Termux `${'$'}PREFIX`, never `/data/user/0/com.omnidev.workspace`.
- READY requires a real shell/root smoke test; DEX/file/binder presence is not readiness.
- If output contains `UnsatisfiedLinkError` or `couldn't find "librish.so"`, STOP rish repair retries.
- NEVER copy/extract librish.so, set LD_LIBRARY_PATH, or add -Djava.library.path.
- A broken rish does not mean Shizuku UserService is broken; use `shell` for programmatic privileged work.
""".trimIndent(),
            parameters = listOf(
                ToolParameter("action", "string", "Privileged action to perform.", required = true),
                ToolParameter("command", "string", "Shell command for shell/rish_exec.", required = false),
                ToolParameter("script", "string", "Multi-line content for rish_script.", required = false),
                ToolParameter("commands", "string", "Newline-separated commands for rish_session.", required = false),
                ToolParameter("service", "string", "Service for dumpsys/svc.", required = false),
                ToolParameter("key", "string", "Property/settings key.", required = false),
                ToolParameter("value", "string", "Value for write actions.", required = false),
                ToolParameter("namespace", "string", "system, secure, or global.", required = false),
                ToolParameter("package", "string", "Android package name.", required = false),
                ToolParameter("apk_path", "string", "APK path for pkg_install.", required = false),
                ToolParameter("permission", "string", "Android permission.", required = false),
                ToolParameter("component", "string", "Activity component.", required = false),
                ToolParameter("broadcast_action", "string", "Broadcast intent action.", required = false),
                ToolParameter("filter", "string", "Optional package filter.", required = false),
                ToolParameter("x", "string", "Tap X.", required = false),
                ToolParameter("y", "string", "Tap Y.", required = false),
                ToolParameter("x1", "string", "Swipe start X.", required = false),
                ToolParameter("y1", "string", "Swipe start Y.", required = false),
                ToolParameter("x2", "string", "Swipe end X.", required = false),
                ToolParameter("y2", "string", "Swipe end Y.", required = false),
                ToolParameter("duration_ms", "string", "Swipe duration.", required = false),
                ToolParameter("text", "string", "Text for input_text.", required = false),
                ToolParameter("keycode", "string", "Android keycode.", required = false),
                ToolParameter("output_path", "string", "Path for screencap.", required = false),
                ToolParameter("sub_command", "string", "wm subcommand.", required = false),
                ToolParameter("svc_action", "string", "enable or disable.", required = false)
            )
        )
    )

    suspend fun execute(
        action: String,
        args: Map<String, String>
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        when (action.lowercase().trim()) {
            "status" -> status()

            "shell" -> {
                val command = args["command"] ?: return@withContext err("shell requires 'command'")
                PrivilegedExecutionManager.executeCommand(command).toToolResult()
            }

            "dumpsys" -> {
                val service = args["service"] ?: return@withContext err("dumpsys requires 'service'")
                PrivilegedExecutionManager.dumpSysInfo(service).toToolResult()
            }

            "getprop" -> {
                val key = args["key"] ?: return@withContext err("getprop requires 'key'")
                PrivilegedExecutionManager.getSystemProperty(key).toToolResult()
            }

            "setprop" -> {
                val key = args["key"] ?: return@withContext err("setprop requires 'key'")
                val value = args["value"] ?: return@withContext err("setprop requires 'value'")
                PrivilegedExecutionManager.setSystemProperty(key, value).toToolResult()
            }

            "meminfo" -> PrivilegedExecutionManager.executeCommand("cat /proc/meminfo").toToolResult()
            "cpuinfo" -> PrivilegedExecutionManager.executeCommand(
                "cat /proc/cpuinfo | grep -E 'processor|Hardware|model name|cpu MHz' | head -32"
            ).toToolResult()
            "ps" -> PrivilegedExecutionManager.listRunningProcesses().toToolResult()
            "netstat" -> PrivilegedExecutionManager.executeCommand(NETSTAT_CMD).toToolResult()

            "settings_get" -> {
                val namespace = args["namespace"] ?: return@withContext err("settings_get requires 'namespace'")
                val key = args["key"] ?: return@withContext err("settings_get requires 'key'")
                PrivilegedExecutionManager.readSetting(namespace, key).toToolResult()
            }

            "settings_put" -> {
                val namespace = args["namespace"] ?: return@withContext err("settings_put requires 'namespace'")
                val key = args["key"] ?: return@withContext err("settings_put requires 'key'")
                val value = args["value"] ?: return@withContext err("settings_put requires 'value'")
                PrivilegedExecutionManager.writeSetting(namespace, key, value).toToolResult()
            }

            "settings_list" -> {
                val namespace = args["namespace"] ?: return@withContext err("settings_list requires 'namespace'")
                PrivilegedExecutionManager.listSettings(namespace).toToolResult()
            }

            "pkg_list" -> PrivilegedExecutionManager.queryPackages(args["filter"].orEmpty()).toToolResult()

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
                val permission = args["permission"] ?: return@withContext err("pkg_grant requires 'permission'")
                PrivilegedExecutionManager.grantPermission(pkg, permission).toToolResult()
            }

            "pkg_revoke" -> {
                val pkg = args["package"] ?: return@withContext err("pkg_revoke requires 'package'")
                val permission = args["permission"] ?: return@withContext err("pkg_revoke requires 'permission'")
                PrivilegedExecutionManager.revokePermission(pkg, permission).toToolResult()
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
                val component = args["component"] ?: return@withContext err("app_launch requires 'component'")
                PrivilegedExecutionManager.launchComponent(component).toToolResult()
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
                val x1 = args["x1"]?.toIntOrNull() ?: return@withContext err("input_swipe requires integer 'x1'")
                val y1 = args["y1"]?.toIntOrNull() ?: return@withContext err("input_swipe requires integer 'y1'")
                val x2 = args["x2"]?.toIntOrNull() ?: return@withContext err("input_swipe requires integer 'x2'")
                val y2 = args["y2"]?.toIntOrNull() ?: return@withContext err("input_swipe requires integer 'y2'")
                val duration = args["duration_ms"]?.toIntOrNull() ?: 300
                PrivilegedExecutionManager.injectSwipe(x1, y1, x2, y2, duration).toToolResult()
            }

            "input_text" -> {
                val text = args["text"] ?: return@withContext err("input_text requires 'text'")
                PrivilegedExecutionManager.injectText(text).toToolResult()
            }

            "input_keyevent" -> {
                val keycode = args["keycode"]?.toIntOrNull()
                    ?: return@withContext err("input_keyevent requires integer 'keycode'")
                PrivilegedExecutionManager.injectKeyEvent(keycode).toToolResult()
            }

            "screencap" -> PrivilegedExecutionManager.captureScreen(
                args["output_path"] ?: "/data/local/tmp/omnidev_cap.png"
            ).toToolResult()

            "wm" -> {
                val sub = args["sub_command"] ?: return@withContext err("wm requires 'sub_command'")
                PrivilegedExecutionManager.windowManager(sub, args["value"].orEmpty()).toToolResult()
            }

            "svc" -> {
                val service = args["service"] ?: return@withContext err("svc requires 'service'")
                val svcAction = args["svc_action"]
                    ?: return@withContext err("svc requires 'svc_action' (enable or disable)")
                PrivilegedExecutionManager.controlService(service, svcAction).toToolResult()
            }

            "termux_hints" -> ToolExecutionResult(PrivilegedExecutionManager.getTermuxBootstrapHints())
            "termux_install" -> PrivilegedExecutionManager.installTermuxViaShizuku().toToolResult()

            "rish_setup" -> setupRish()

            "rish_exec" -> {
                val command = args["command"] ?: return@withContext err("rish_exec requires 'command'")
                val rish = PrivilegedExecutionManager.getRishManager()
                    ?: return@withContext err("RishShellManager is not initialized.")
                rish.execute(command).toToolResult()
            }

            "rish_script" -> {
                val script = args["script"] ?: return@withContext err("rish_script requires 'script'")
                val rish = PrivilegedExecutionManager.getRishManager()
                    ?: return@withContext err("RishShellManager is not initialized.")
                rish.executeScript(script).toToolResult()
            }

            "rish_session" -> {
                val commands = args["commands"] ?: return@withContext err("rish_session requires 'commands'")
                runRishSession(commands)
            }

            else -> err("Unknown privileged_tool action: '$action'. See tool description for supported actions.")
        }
    }

    private suspend fun status(): ToolExecutionResult {
        val shizuku = PrivilegedExecutionManager.isShizukuReady()
        val root = PrivilegedExecutionManager.isRootAvailable()
        val rishManager = PrivilegedExecutionManager.getRishManager()
        val rishHealth = rishManager?.refreshHealth()
            ?: RishRuntimeHealth(RishRuntimeHealth.State.UNKNOWN, "RishShellManager is not initialized")

        val activeBackend = when {
            shizuku -> "✅ Shizuku UserService (active)"
            rishHealth.ready -> "✅ rish terminal shell (active)"
            root -> "⚠️ Root/SU only"
            else -> "❌ No privileged backend available"
        }

        return ToolExecutionResult(
            buildString {
                appendLine("Privileged backend: $activeBackend")
                appendLine("Shizuku UserService: ${if (shizuku) "✅ READY" else "❌ NOT READY"}")
                appendLine("rish terminal: ${if (rishHealth.ready) "✅ READY" else "❌ ${rishHealth.state}"}")
                if (!rishHealth.ready) appendLine("rish detail: ${rishHealth.details.ifBlank { rishHealth.summary }}")
                append("Root/SU: ${if (root) "✅" else "❌"}")
            }.trimEnd(),
            isError = !shizuku && !rishHealth.ready && !root
        )
    }

    private suspend fun setupRish(): ToolExecutionResult {
        val rish = PrivilegedExecutionManager.getRishManager()
            ?: return err("RishShellManager is not initialized (call PrivilegedExecutionManager.init first).")
        val health = rish.installIntoTermux()
        val status = rish.statusReport()
        return ToolExecutionResult(
            buildString {
                appendLine(if (health.ready) "✅ rish setup verified." else "❌ rish setup did not pass verification.")
                appendLine("State: ${health.state}")
                if (health.details.isNotBlank()) appendLine("Action: ${health.details}")
                appendLine()
                append(status)
            }.trimEnd(),
            isError = !health.ready
        )
    }

    private suspend fun runRishSession(commands: String): ToolExecutionResult {
        val rish = PrivilegedExecutionManager.getRishManager()
            ?: return err("RishShellManager is not initialized.")
        val lines = commands.lines().map(String::trim).filter(String::isNotBlank)
        if (lines.isEmpty()) return err("rish_session has no non-empty commands.")

        val out = StringBuilder()
        var failed = false
        for (command in lines) {
            out.appendLine("\$ $command")
            val result = rish.execute(command)
            result.fold(
                onSuccess = { out.appendLine(it) },
                onFailure = {
                    failed = true
                    out.appendLine("❌ ${it.message}")
                }
            )

            val state = rish.cachedHealth().state
            if (failed && state in PERSISTENT_RISH_FAILURES) {
                out.appendLine()
                out.appendLine("Circuit breaker: stopping rish session after persistent infrastructure failure $state.")
                out.appendLine(RishFailureClassifier.remediation(state))
                break
            }
        }
        return ToolExecutionResult(out.toString().trimEnd(), isError = failed)
    }

    private val PERSISTENT_RISH_FAILURES = setOf(
        RishRuntimeHealth.State.NATIVE_LIBRARY_LOAD_FAILURE,
        RishRuntimeHealth.State.CROSS_SANDBOX_PERMISSION_FAILURE,
        RishRuntimeHealth.State.DEX_PERMISSION_FAILURE,
        RishRuntimeHealth.State.TERMUX_LAYOUT_BROKEN,
        RishRuntimeHealth.State.TERMUX_UNAVAILABLE,
        RishRuntimeHealth.State.SHIZUKU_UNAVAILABLE,
        RishRuntimeHealth.State.SHIZUKU_PERMISSION_REQUIRED
    )

    private fun err(message: String) = ToolExecutionResult(message, isError = true)

    private fun Result<String>.toToolResult(): ToolExecutionResult = fold(
        onSuccess = { ToolExecutionResult(it.ifBlank { "(no output)" }) },
        onFailure = { ToolExecutionResult("❌ ${it.message}", isError = true) }
    )
}
