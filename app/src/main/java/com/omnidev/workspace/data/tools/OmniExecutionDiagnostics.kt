package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import com.omnidev.workspace.data.ipc.RishFailureClassifier
import com.omnidev.workspace.data.ipc.RishRuntimeHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Diagnostics for OmniDev's independent execution domains.
 *
 * No diagnostic is allowed to infer readiness from files/package visibility alone.
 * Termux, Shizuku UserService, and rish are probed through the same transports used
 * by real commands.
 */
object OmniExecutionDiagnostics {

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "execution_diagnostics",
            description = """
Diagnose and repair OmniDev execution domains without crossing app sandboxes.

Actions:
• full_check — functionally probe Termux, Shizuku UserService and rish.
• fix_shizuku — verify binder, permission, UserService UID and real shell command.
• fix_rish — install/repair rish inside Termux and require `rish -c id` success.
• fix_termux — verify official RunCommandService and refresh package state.
• fix_python — install/repair Python in Termux.
• fix_git — install/repair Git in Termux.
• install_termux — explain official Termux setup requirements.
• test_command — DIAGNOSTIC ONLY: compare one command across developer, privileged and rish domains. Do not use this as the normal command executor; run_terminal auto-routes Android commands.
• repair_all — bootstrap Termux runtimes then probe Shizuku/rish.

Never repair rish by copying `librish.so`, changing LD_LIBRARY_PATH, or adding
-Djava.library.path. A native-loader failure is classified and stops that strategy.
""".trimIndent(),
            parameters = listOf(
                ToolParameter(
                    "action",
                    "string",
                    "full_check, fix_shizuku, fix_rish, fix_termux, fix_python, fix_git, install_termux, test_command, repair_all",
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
                "fix_rish" -> fixRish()
                "fix_termux" -> fixTermux()
                "fix_python" -> installRuntime("python", listOf("python3", "python"))
                "fix_git" -> installRuntime("git", listOf("git"))
                "install_termux" -> installTermuxGuidance()
                "test_command" -> testCommand(
                    args["command"]?.takeIf(String::isNotBlank)
                        ?: return@withContext ToolExecutionResult("Requires 'command'.", isError = true)
                )
                "repair_all" -> repairAll()
                else -> ToolExecutionResult("Unknown action '$action'.", isError = true)
            }
        }

    private suspend fun fullCheck(): ToolExecutionResult {
        val runtime = EnvironmentSetupManager.probe(force = true)

        val termuxHealth = if (TermuxRunCommandBridge.isTermuxInstalled()) {
            EnvironmentSetupManager.executeShell(
                "printf 'termux_ok\\n'; id; printf 'prefix=%s\\n' \"\$PREFIX\""
            )
        } else null
        val termuxFunctional = termuxHealth?.isError == false

        val shizukuAvailable = ShizukuCommandTool.isAvailable()
        val shizukuGranted = shizukuAvailable && ShizukuCommandTool.hasPermission()
        val shizukuHealth = if (shizukuGranted) {
            ShizukuCommandTool.execute("id; getprop ro.build.version.sdk", timeoutMs = 20_000L)
        } else null
        val shizukuFunctional = shizukuHealth is ShizukuResult.Success
        val shizukuUid = if (shizukuGranted) ShizukuCommandTool.privilegedUidOrNull() else null

        val rishManager = PrivilegedExecutionManager.getRishManager()
        val rishHealth = rishManager?.refreshHealth()
            ?: RishRuntimeHealth(RishRuntimeHealth.State.UNKNOWN, "RishShellManager is not initialized")
        val rishFunctional = rishHealth.ready
        val anyFunctionalDomain = termuxFunctional || shizukuFunctional || rishFunctional

        val problems = buildList {
            if (!termuxFunctional) {
                add("Termux developer-shell transport is not healthy.")
            }
            if (!runtime.runtime("python3").available && !runtime.runtime("python").available) {
                add("Python is not installed in Termux.")
            }
            if (!runtime.runtime("git").available) add("Git is not installed in Termux.")

            when {
                !shizukuAvailable -> add("Shizuku binder is not running.")
                !shizukuGranted -> add("Shizuku permission is not granted to OmniDev.")
                !shizukuFunctional ->
                    add("Shizuku UserService smoke-test failed: ${shizukuHealth?.toDisplayString()?.take(240)}")
            }

            if (!rishFunctional) {
                add("rish is ${rishHealth.state}: ${rishHealth.details.ifBlank { rishHealth.summary }}")
            }
        }

        val stateLabel = when {
            problems.isEmpty() -> "HEALTHY"
            anyFunctionalDomain -> "DEGRADED_BUT_USABLE"
            else -> "UNAVAILABLE"
        }
        val preferredBackend = when {
            shizukuFunctional -> "shizuku-user-service"
            termuxFunctional -> "termux"
            rishFunctional -> "rish"
            else -> "none"
        }

        return ToolExecutionResult(
            output = buildString {
                appendLine("╔══ OmniDev Execution Diagnostics ═══════════════════════════╗")
                appendLine("║ Runtime phase : ${runtime.phase}")
                appendLine("║ Overall       : $stateLabel")
                appendLine("║ Preferred now : $preferredBackend")
                appendLine("║")
                appendLine("║ TERMUX / DEVELOPER SHELL")
                appendLine("║ Installed     : ${yesNo(TermuxRunCommandBridge.isTermuxInstalled())}")
                appendLine("║ RUN_COMMAND   : ${yesNo(TermuxRunCommandBridge.hasRunCommandPermission())}")
                appendLine("║ Functional    : ${yesNo(termuxFunctional)}")
                appendLine("║ Python        : ${runtime.runtime("python3").path ?: runtime.runtime("python").path ?: "❌ missing"}")
                appendLine("║ Node          : ${runtime.runtime("node").path ?: "❌ missing"}")
                appendLine("║ Git           : ${runtime.runtime("git").path ?: "❌ missing"}")
                appendLine("║ pkg           : ${runtime.runtime("pkg").path ?: "❌ missing"}")
                appendLine("║")
                appendLine("║ SHIZUKU / PROGRAMMATIC PRIVILEGED SHELL")
                appendLine("║ Binder        : ${yesNo(shizukuAvailable)}")
                appendLine("║ Permission    : ${yesNo(shizukuGranted)}")
                appendLine("║ Functional    : ${yesNo(shizukuFunctional)}")
                appendLine("║ UserService UID: ${shizukuUid ?: "unknown"}")
                appendLine("║ Smoke test    : ${shizukuHealth?.toDisplayString()?.take(240) ?: "not run"}")
                appendLine("║")
                appendLine("║ RISH / TERMUX ADB-EQUIVALENT SHELL")
                appendLine("║ State         : ${rishHealth.state}")
                appendLine("║ Ready         : ${yesNo(rishFunctional)}")
                appendLine("║ Smoke test    : ${rishHealth.smokeOutput.take(240).ifBlank { "not run" }}")
                if (!rishFunctional) {
                    appendLine("║ Remediation   : ${rishHealth.details.ifBlank { RishFailureClassifier.remediation(rishHealth.state) }}")
                }
                appendLine("║")
                if (problems.isEmpty()) {
                    appendLine("║ ✅ All execution domains passed functional probes.")
                } else {
                    appendLine("║ WARNINGS")
                    problems.forEachIndexed { index, issue -> appendLine("║ ${index + 1}. $issue") }
                    if (anyFunctionalDomain) {
                        appendLine("║ ✅ At least one functional backend is available; do not treat warnings as total execution failure.")
                    }
                }
                appendLine("╚════════════════════════════════════════════════════════════╝")
            }.trimEnd(),
            isError = !anyFunctionalDomain,
            classification = stateLabel,
            backend = preferredBackend.takeUnless { it == "none" },
            verification = if (anyFunctionalDomain) "functional backend=$preferredBackend" else null,
            persistentFailure = !anyFunctionalDomain
        )
    }

    private suspend fun fixShizuku(): ToolExecutionResult {
        if (!ShizukuCommandTool.isAvailable()) {
            return ToolExecutionResult(
                "❌ Shizuku binder is unavailable. Start Shizuku and retry.",
                isError = true
            )
        }

        val command = ShizukuCommandTool.execute(
            "printf 'user_service_ok\\n'; id; getprop ro.build.version.sdk",
            timeoutMs = 20_000L
        )
        val uid = ShizukuCommandTool.privilegedUidOrNull()

        return when (command) {
            is ShizukuResult.Success -> {
                val rish = PrivilegedExecutionManager.getRishManager()?.refreshHealth()
                ToolExecutionResult(
                    buildString {
                        appendLine("✅ Shizuku UserService is healthy.")
                        appendLine("Privileged UID: ${uid ?: "unknown"} (expected 2000 for ADB-backed Shizuku or 0 for root-backed Shizuku)")
                        appendLine(command.output)
                        if (rish != null && !rish.ready) {
                            appendLine()
                            appendLine("ℹ️ Terminal rish is independently degraded: ${rish.state}")
                            appendLine(rish.details)
                            appendLine("This does not invalidate the healthy UserService backend.")
                        }
                    }.trimEnd()
                )
            }
            is ShizukuResult.PermissionRequired -> ToolExecutionResult(command.toDisplayString(), isError = true)
            is ShizukuResult.Unavailable -> ToolExecutionResult(command.toDisplayString(), isError = true)
            is ShizukuResult.Failure -> ToolExecutionResult(command.toDisplayString(), isError = true)
            is ShizukuResult.PartialSuccess -> ToolExecutionResult(
                "❌ Shizuku UserService returned exit ${command.exitCode}: ${command.output}",
                isError = true
            )
        }
    }

    private suspend fun fixRish(): ToolExecutionResult {
        val manager = PrivilegedExecutionManager.getRishManager()
            ?: return ToolExecutionResult("RishShellManager is not initialized.", isError = true)
        val health = manager.installIntoTermux()
        val status = manager.statusReport()
        return ToolExecutionResult(
            buildString {
                appendLine(if (health.ready) "✅ rish install + smoke-test passed." else "❌ rish is not functional after setup.")
                appendLine("State: ${health.state}")
                if (!health.ready) appendLine("Remediation: ${health.details}")
                appendLine()
                append(status)
            }.trimEnd(),
            isError = !health.ready
        )
    }

    private suspend fun fixTermux(): ToolExecutionResult {
        val status = EnvironmentSetupManager.statusReport()
        if (!EnvironmentSetupManager.isTermuxUsable()) {
            return ToolExecutionResult(
                buildString {
                    appendLine(status.output)
                    appendLine()
                    appendLine("Required setup:")
                    appendLine("1. Install/open official Termux.")
                    appendLine("2. Grant OmniDev com.termux.permission.RUN_COMMAND when requested.")
                    appendLine("3. In Termux set allow-external-apps=true in ~/.termux/termux.properties.")
                    appendLine("4. Retry execution_diagnostics action=fix_termux.")
                }.trimEnd(),
                isError = true
            )
        }

        val update = EnvironmentSetupManager.executeTool("advanced_terminal", mapOf("action" to "pkg_update"))
        val health = EnvironmentSetupManager.executeShell(
            "id; printf 'prefix=%s\\n' \"\$PREFIX\"; command -v pkg bash"
        )
        return ToolExecutionResult(
            buildString {
                appendLine("Termux transport: ${if (!health.isError) "✅ healthy" else "❌ unhealthy"}")
                appendLine(health.output.take(3_000))
                appendLine()
                appendLine("Package index refresh: ${if (!update.isError) "✅" else "❌"}")
                append(update.output.take(3_000))
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
            ToolExecutionResult("✅ Installed $packageName; ${found.first} → ${found.second}\n${install.output.take(2_000)}")
        } else {
            ToolExecutionResult(
                "Package manager reported success but expected runtime was not found after install.\n${install.output.take(3_000)}",
                isError = true
            )
        }
    }

    private fun installTermuxGuidance(): ToolExecutionResult = ToolExecutionResult(
        """
Install the official Termux app, open it once, and allow OmniDev's RUN_COMMAND permission.
Termux also requires its one-time external-app opt-in:

mkdir -p ~/.termux
touch ~/.termux/termux.properties
if grep -q '^allow-external-apps=' ~/.termux/termux.properties; then
  sed -i 's/^allow-external-apps=.*/allow-external-apps=true/' ~/.termux/termux.properties
else
  printf '\nallow-external-apps=true\n' >> ~/.termux/termux.properties
fi
termux-reload-settings

After that, agent_runtime executes inside the real Termux process.
""".trimIndent(),
        isError = !EnvironmentSetupManager.isTermuxUsable()
    )

    private suspend fun testCommand(command: String): ToolExecutionResult {
        val developer = if (EnvironmentSetupManager.isTermuxUsable()) {
            EnvironmentSetupManager.executeShell(command)
        } else {
            ToolExecutionResult(
                "Termux RunCommand transport is not ready.",
                isError = true,
                classification = "TERMUX_RUN_COMMAND_UNAVAILABLE",
                backend = "termux",
                persistentFailure = true
            )
        }

        val shizukuResult = if (ShizukuCommandTool.isAvailable()) {
            ShizukuCommandTool.execute(command, timeoutMs = 30_000L)
        } else null
        val shizukuSuccess = shizukuResult is ShizukuResult.Success
        val shizukuText = shizukuResult?.toDisplayString() ?: "Shizuku binder unavailable"

        val rishManager = PrivilegedExecutionManager.getRishManager()
        val rishExecution = if (rishManager != null) rishManager.execute(command) else null
        val rishSuccess = rishExecution?.isSuccess == true
        val rishText = when {
            rishExecution == null -> "rish manager unavailable"
            rishExecution.isSuccess -> rishExecution.getOrThrow()
            else -> "Error: ${rishExecution.exceptionOrNull()?.message}"
        }

        val anySuccess = !developer.isError || shizukuSuccess || rishSuccess
        val successfulBackend = when {
            shizukuSuccess -> "shizuku-user-service"
            !developer.isError -> "termux"
            rishSuccess -> "rish"
            else -> null
        }

        return ToolExecutionResult(
            output = buildString {
                appendLine("=== Command-domain comparison (diagnostic only) ===")
                appendLine("Command: $command")
                appendLine()
                appendLine("[Termux / developer shell] ${if (developer.isError) "FAIL" else "PASS"}")
                appendLine(developer.output.take(4_000))
                appendLine()
                appendLine("[Shizuku UserService / Android privileged shell] ${if (shizukuSuccess) "PASS" else "FAIL"}")
                appendLine(shizukuText.take(4_000))
                appendLine()
                appendLine("[rish / Termux ADB-equivalent shell] ${if (rishSuccess) "PASS" else "FAIL"}")
                append(rishText.take(4_000))
            }.trimEnd(),
            isError = !anySuccess,
            classification = if (anySuccess) {
                if ((!developer.isError).compareTo(true) == 0 && shizukuSuccess && rishSuccess) {
                    "SUCCESS"
                } else {
                    "DEGRADED_COMMAND_ROUTE"
                }
            } else {
                "ALL_EXECUTION_DOMAINS_FAILED"
            },
            backend = successfulBackend,
            verification = successfulBackend?.let { "command succeeded via $it" },
            persistentFailure = !anySuccess
        )
    }

    private suspend fun repairAll(): ToolExecutionResult {
        if (!EnvironmentSetupManager.isTermuxUsable()) return fixTermux()

        val steps = EnvironmentSetupManager.runBootstrap(EnvironmentSetupManager.buildStandardBootstrapPlan())
        val shizuku = fixShizuku()
        val rish = fixRish()
        val bootstrapFailed = steps.any { it.second is BootstrapResult.Failed }

        return ToolExecutionResult(
            buildString {
                appendLine("=== Runtime repair ===")
                steps.forEach { (id, result) ->
                    appendLine(
                        when (result) {
                            is BootstrapResult.Success -> "✅ $id: ${result.message}"
                            is BootstrapResult.AlreadyDone -> "✔️ $id: ${result.message}"
                            is BootstrapResult.Skipped -> "⏭️ $id: ${result.reason}"
                            is BootstrapResult.Failed ->
                                "❌ $id: ${result.reason}${result.hint.takeIf(String::isNotBlank)?.let { " — $it" }.orEmpty()}"
                        }
                    )
                }
                appendLine()
                appendLine("=== Shizuku UserService ===")
                appendLine(shizuku.output.take(4_000))
                appendLine()
                appendLine("=== rish ===")
                append(rish.output.take(4_000))
            }.trimEnd(),
            isError = bootstrapFailed || shizuku.isError || rish.isError
        )
    }

    private fun yesNo(value: Boolean): String = if (value) "✅" else "❌"
}
