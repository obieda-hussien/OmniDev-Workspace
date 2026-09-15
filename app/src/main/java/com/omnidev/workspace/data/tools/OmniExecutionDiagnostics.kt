package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Diagnostics and repair entry point for OmniDev's three execution domains.
 *
 * The previous implementation still diagnosed `Shizuku.newProcess`, injected a
 * fake Termux environment into Shizuku and suggested the hidden `termux_bridge`
 * tool. Keeping diagnostics on that architecture made the agent "repair" the
 * system back into the broken state. This implementation tests the exact same
 * backends used by real execution.
 */
object OmniExecutionDiagnostics {

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "execution_diagnostics",
            description = """
Diagnose and repair OmniDev command execution.
Execution domains are explicit:
• developer shell/packages: Termux RunCommandService (agent_runtime)
• privileged Android shell: Shizuku UserService
• ADB-equivalent shell: rish

Actions:
• full_check      — Probe all backends/runtimes and show exact setup problems
• fix_shizuku     — Verify Shizuku binder, permission, UserService UID and shell command
• fix_termux      — Verify Termux + RUN_COMMAND and bootstrap common packages when possible
• fix_python      — Install/repair Python in Termux
• fix_git         — Install/repair Git in Termux
• install_termux  — Show safe Termux installation/setup requirements
• test_command    — Run a command through developer and privileged domains separately
• repair_all      — Repair package state and bootstrap common developer runtimes
""".trimIndent(),
            parameters = listOf(
                ToolParameter(
                    "action",
                    "string",
                    "full_check, fix_shizuku, fix_termux, fix_python, fix_git, install_termux, test_command, repair_all",
                    required = true
                ),
                ToolParameter("command", "string", "Command for test_command", required = false)
            )
        )
    )

    suspend fun execute(action: String, args: Map<String, String>): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            when (action.trim().lowercase()) {
                "full_check" -> fullCheck()
                "fix_shizuku" -> fixShizuku()
                "fix_termux" -> fixTermux()
                "fix_python" -> installRuntime("python", listOf("python3", "python"))
                "fix_git" -> installRuntime("git", listOf("git"))
                "install_termux" -> installTermuxGuidance()
                "test_command" -> testCommand(
                    args["command"]?.takeIf { it.isNotBlank() }
                        ?: return@withContext ToolExecutionResult("Requires 'command'.", isError = true)
                )
                "repair_all" -> repairAll()
                else -> ToolExecutionResult("Unknown action '$action'.", isError = true)
            }
        }

    private suspend fun fullCheck(): ToolExecutionResult {
        val runtime = EnvironmentSetupManager.probe(force = true)
        val shizukuAvailable = ShizukuCommandTool.isAvailable()
        val shizukuGranted = shizukuAvailable && ShizukuCommandTool.hasPermission()
        val shizukuUid = if (shizukuGranted) ShizukuCommandTool.privilegedUidOrNull() else null
        val rish = PrivilegedExecutionManager.getRishManager()
        val rishReady = PrivilegedExecutionManager.isRishReady()

        val termuxHealth = if (EnvironmentSetupManager.isTermuxUsable()) {
            EnvironmentSetupManager.executeShell("printf 'ok\\n'; id; printf 'prefix=%s\\n' \"\$PREFIX\"")
        } else null

        val shizukuHealth = if (shizukuGranted) {
            ShizukuCommandTool.execute("id; getprop ro.build.version.sdk")
        } else null

        val rishHealth = if (rishReady && rish != null) {
            rish.execute("id")
        } else null

        val problems = buildList {
            if (!ShizukuCommandTool.isAvailable()) add("Shizuku binder is not running.")
            else if (!ShizukuCommandTool.hasPermission()) add("Shizuku permission is not granted to OmniDev.")
            if (!EnvironmentSetupManager.isTermuxUsable()) {
                add("Termux RunCommand transport is not ready: install official Termux, grant RUN_COMMAND, set allow-external-apps=true.")
            }
            if (runtime.runtime("python3").available.not() && runtime.runtime("python").available.not()) {
                add("Python is not installed in Termux.")
            }
            if (!runtime.runtime("git").available) add("Git is not installed in Termux.")
            if (shizukuGranted && shizukuHealth !is ShizukuResult.Success) {
                add("Shizuku UserService command smoke-test failed: ${shizukuHealth?.toDisplayString()?.take(180)}")
            }
            if (rishReady && rishHealth?.isFailure == true) {
                add("rish smoke-test failed: ${rishHealth.exceptionOrNull()?.message?.take(180)}")
            }
        }

        return ToolExecutionResult(
            buildString {
                appendLine("╔══ OmniDev Execution Diagnostics ═══════════════════════════╗")
                appendLine("║ Runtime phase : ${runtime.phase}")
                appendLine("║ Privilege     : ${runtime.privilegeBackend}")
                appendLine("║")
                appendLine("║ TERMUX / DEVELOPER SHELL")
                appendLine("║ Installed     : ${yesNo(TermuxRunCommandBridge.isTermuxInstalled())}")
                appendLine("║ RUN_COMMAND   : ${yesNo(TermuxRunCommandBridge.hasRunCommandPermission())}")
                appendLine("║ Transport     : ${yesNo(termuxHealth?.isError == false)}")
                appendLine("║ Python        : ${runtime.runtime("python3").path ?: runtime.runtime("python").path ?: "❌ missing"}")
                appendLine("║ Node          : ${runtime.runtime("node").path ?: "❌ missing"}")
                appendLine("║ Git           : ${runtime.runtime("git").path ?: "❌ missing"}")
                appendLine("║ pkg           : ${runtime.runtime("pkg").path ?: "❌ missing"}")
                appendLine("║")
                appendLine("║ SHIZUKU / ANDROID PRIVILEGED SHELL")
                appendLine("║ Binder        : ${yesNo(shizukuAvailable)}")
                appendLine("║ Permission    : ${yesNo(shizukuGranted)}")
                appendLine("║ UserService UID: ${shizukuUid ?: "unknown"}")
                appendLine("║ Smoke test    : ${shizukuHealth?.toDisplayString()?.take(180) ?: "not run"}")
                appendLine("║")
                appendLine("║ RISH / ADB-EQUIVALENT SHELL")
                appendLine("║ Ready         : ${yesNo(rishReady)}")
                appendLine("║ Smoke test    : ${rishHealth?.fold({ it.take(180) }, { "❌ ${it.message}" }) ?: "not run"}")
                appendLine("║")
                if (problems.isEmpty()) {
                    appendLine("║ ✅ No execution-layer problems detected.")
                } else {
                    appendLine("║ RECOMMENDED FIXES")
                    problems.forEachIndexed { index, issue -> appendLine("║ ${index + 1}. $issue") }
                }
                appendLine("╚════════════════════════════════════════════════════════════╝")
            }.trimEnd(),
            isError = problems.isNotEmpty()
        )
    }

    private suspend fun fixShizuku(): ToolExecutionResult {
        if (!ShizukuCommandTool.isAvailable()) {
            return ToolExecutionResult(
                "❌ Shizuku binder is unavailable. Start Shizuku (ADB/wireless debugging or root) and retry.",
                isError = true
            )
        }

        val command = ShizukuCommandTool.execute(
            "printf 'user_service_ok\\n'; id; getprop ro.build.version.sdk",
            timeoutMs = 20_000L
        )
        val uid = ShizukuCommandTool.privilegedUidOrNull()
        val rishStatus = PrivilegedExecutionManager.getRishManager()?.statusReport()

        return when (command) {
            is ShizukuResult.Success -> ToolExecutionResult(
                buildString {
                    appendLine("✅ Shizuku UserService is healthy.")
                    appendLine("Privileged UID: ${uid ?: "unknown"} (expected 2000 for ADB Shizuku or 0 for root-backed Shizuku)")
                    appendLine("Command output:")
                    appendLine(command.output)
                    if (!rishStatus.isNullOrBlank()) {
                        appendLine()
                        appendLine(rishStatus)
                    }
                }.trimEnd()
            )
            is ShizukuResult.PermissionRequired -> ToolExecutionResult(command.toDisplayString(), isError = true)
            is ShizukuResult.Unavailable -> ToolExecutionResult(command.toDisplayString(), isError = true)
            is ShizukuResult.Failure -> ToolExecutionResult(command.toDisplayString(), isError = true)
            is ShizukuResult.PartialSuccess -> ToolExecutionResult(
                "❌ Shizuku shell returned exit ${command.exitCode}: ${command.output}",
                isError = true
            )
        }
    }

    private suspend fun fixTermux(): ToolExecutionResult {
        val status = EnvironmentSetupManager.statusReport()
        if (!EnvironmentSetupManager.isTermuxUsable()) {
            return ToolExecutionResult(
                buildString {
                    appendLine(status.output)
                    appendLine()
                    appendLine("Required setup:")
                    appendLine("1. Install the official Termux app (F-Droid/GitHub release).")
                    appendLine("2. Grant OmniDev the additional permission: com.termux.permission.RUN_COMMAND.")
                    appendLine("3. In Termux, set allow-external-apps=true in ~/.termux/termux.properties.")
                    appendLine("4. Restart Termux, then rerun execution_diagnostics action=fix_termux.")
                }.trimEnd(),
                isError = true
            )
        }

        val update = EnvironmentSetupManager.executeTool("advanced_terminal", mapOf("action" to "pkg_update"))
        val health = EnvironmentSetupManager.executeShell("id; printf 'prefix=%s\\n' \"\$PREFIX\"; command -v pkg bash")
        return ToolExecutionResult(
            buildString {
                appendLine("Termux transport: ${if (!health.isError) "✅ healthy" else "❌ unhealthy"}")
                appendLine(health.output.take(3000))
                appendLine()
                appendLine("Package index refresh: ${if (!update.isError) "✅" else "❌"}")
                appendLine(update.output.take(3000))
            }.trimEnd(),
            isError = health.isError || update.isError
        )
    }

    private suspend fun installRuntime(packageName: String, binaries: List<String>): ToolExecutionResult {
        if (!EnvironmentSetupManager.isTermuxUsable()) return fixTermux()

        val existing = binaries.firstNotNullOfOrNull { binary ->
            EnvironmentSetupManager.resolveBinary(binary)?.let { binary to it }
        }
        if (existing != null) {
            return ToolExecutionResult("✅ ${existing.first} is already available at ${existing.second}")
        }

        val install = EnvironmentSetupManager.pkgInstall(packageName)
        if (install.isError) return install

        val found = binaries.firstNotNullOfOrNull { binary ->
            EnvironmentSetupManager.resolveBinary(binary)?.let { binary to it }
        }
        return if (found != null) {
            ToolExecutionResult("✅ Installed $packageName; ${found.first} → ${found.second}\n${install.output.take(2000)}")
        } else {
            ToolExecutionResult(
                "Package manager reported success but expected runtime was not found after install.\n${install.output.take(3000)}",
                isError = true
            )
        }
    }

    private fun installTermuxGuidance(): ToolExecutionResult = ToolExecutionResult(
        """
OmniDev intentionally does not sideload an arbitrary Termux APK from diagnostics.

Install the official Termux app from F-Droid or the Termux GitHub releases, then:
1. Open Termux once and let it initialize.
2. Grant OmniDev `com.termux.permission.RUN_COMMAND` in Android app permissions.
3. Run in Termux:
   mkdir -p ~/.termux
   printf '%s\n' 'allow-external-apps=true' >> ~/.termux/termux.properties
   termux-reload-settings
4. Return to OmniDev and run `execution_diagnostics action=fix_termux`.

After that, `agent_runtime` can use pkg/apt/python/node/npm/pip/git in the real Termux environment.
""".trimIndent(),
        isError = !EnvironmentSetupManager.isTermuxUsable()
    )

    private suspend fun testCommand(command: String): ToolExecutionResult {
        val developer = if (EnvironmentSetupManager.isTermuxUsable()) {
            EnvironmentSetupManager.executeShell(command)
        } else {
            ToolExecutionResult("Termux RunCommand transport is not ready.", isError = true)
        }

        val shizuku = if (ShizukuCommandTool.isAvailable()) {
            ShizukuCommandTool.execute(command, timeoutMs = 30_000L).toDisplayString()
        } else {
            "Shizuku binder unavailable"
        }

        val rish = PrivilegedExecutionManager.getRishManager()
            ?.takeIf { it.isAvailable() }
            ?.execute(command)
            ?.fold({ it }, { "Error: ${it.message}" })
            ?: "rish unavailable"

        return ToolExecutionResult(
            buildString {
                appendLine("=== Command-domain comparison ===")
                appendLine("Command: $command")
                appendLine()
                appendLine("[Termux / developer shell]")
                appendLine(developer.output.take(6000))
                appendLine()
                appendLine("[Shizuku UserService / Android privileged shell]")
                appendLine(shizuku.take(6000))
                appendLine()
                appendLine("[rish / ADB-equivalent shell]")
                appendLine(rish.take(6000))
            }.trimEnd(),
            isError = developer.isError && !ShizukuCommandTool.isAvailable() && !PrivilegedExecutionManager.isRishReady()
        )
    }

    private suspend fun repairAll(): ToolExecutionResult {
        if (!EnvironmentSetupManager.isTermuxUsable()) return fixTermux()

        val steps = EnvironmentSetupManager.runBootstrap(EnvironmentSetupManager.buildStandardBootstrapPlan())
        val shizuku = fixShizuku()
        val failed = steps.any { it.second is BootstrapResult.Failed }

        return ToolExecutionResult(
            buildString {
                appendLine("=== Runtime repair ===")
                steps.forEach { (id, result) ->
                    appendLine(
                        when (result) {
                            is BootstrapResult.Success -> "✅ $id: ${result.message}"
                            is BootstrapResult.AlreadyDone -> "✔️ $id: ${result.message}"
                            is BootstrapResult.Skipped -> "⏭️ $id: ${result.reason}"
                            is BootstrapResult.Failed -> "❌ $id: ${result.reason}${result.hint.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()}"
                        }
                    )
                }
                appendLine()
                appendLine("=== Shizuku ===")
                appendLine(shizuku.output.take(4000))
            }.trimEnd(),
            isError = failed
        )
    }

    private fun yesNo(value: Boolean): String = if (value) "✅" else "❌"
}
