package com.omnidev.workspace.data.tools

import android.util.Log
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * OmniExecutionDiagnostics — Completely new file
 *
 * Automatically diagnoses and fixes execution issues.
 * Called by the agent upon any issue with Shizuku / Termux / Python / Git.
 *
 * Tool name: "execution_diagnostics"
 */
object OmniExecutionDiagnostics {

    private const val TAG = "OmniExecDiag"

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "execution_diagnostics",
            description = """
Automatic diagnosis and repair tool for the execution environment.
Use it when:
• "no output" from Shizuku
• Termux or Python fails
• git or pkg install fails
• Any command execution issue arises

Actions:
• full_check     — Comprehensive check of all components + suggested fixes
• fix_shizuku    — Fix Shizuku issues (test + re-initialize)
• fix_termux     — Setup Termux environment (PATH, libs, permissions)
• fix_python     — Find/install Python using the best available method
• fix_git        — Find/install git
• test_command   — Test a specific command and show full debugging
• repair_all     — Attempt to fix everything automatically
""".trimIndent(),
            parameters = listOf(
                ToolParameter("action", "string", "Action: full_check, fix_shizuku, fix_termux, fix_python, fix_git, test_command, repair_all", required = true),
                ToolParameter("command", "string", "Command to test (for test_command)", required = false)
            )
        )
    )

    suspend fun execute(action: String, args: Map<String, String>): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            when (action.lowercase().trim()) {
                "full_check"  -> fullCheck()
                "fix_shizuku" -> fixShizuku()
                "fix_termux"  -> fixTermux()
                "fix_python"  -> fixPython()
                "fix_git"     -> fixGit()
                "test_command" -> testCommand(args["command"]
                    ?: return@withContext ToolExecutionResult("Requires 'command'.", isError = true))
                "repair_all"  -> repairAll()
                else -> ToolExecutionResult("Unknown action: '$action'.", isError = true)
            }
        }

    // ──────────────────────────────────────────────────────────────
    // full_check
    // ──────────────────────────────────────────────────────────────
    private suspend fun fullCheck(): ToolExecutionResult {
        val sb = StringBuilder()
        sb.appendLine("╔══ Full Environment Diagnostics ═══════════════════════════════╗")

        // 1. Shizuku
        sb.appendLine("║")
        sb.appendLine("║ [1] SHIZUKU")
        val shizukuAvail = ShizukuCommandTool.isAvailable()
        val shizukuPerm  = if (shizukuAvail) ShizukuCommandTool.hasPermission() else false
        sb.appendLine("║   pingBinder : ${if (shizukuAvail) "✅ Works" else "❌ Not responding"}")
        sb.appendLine("║   Permission : ${if (shizukuPerm) "✅ Granted" else "❌ Not granted"}")
        if (shizukuAvail && shizukuPerm) {
            // Simple test
            val testResult = ShizukuCommandTool.execute("echo shizuku_ok")
            val works = testResult.outputOrNull()?.contains("shizuku_ok") == true
            sb.appendLine("║   echo test  : ${if (works) "✅ Works" else "❌ Failed (${testResult.toDisplayString().take(50)})"}")
            if (!works) {
                sb.appendLine("║   → Fix      : Try 'fix_shizuku'")
            }
        } else {
            if (!shizukuAvail) sb.appendLine("║   → Install Shizuku from Play Store and start it")
            else sb.appendLine("║   → Press 'Grant permission' in the Shizuku app")
        }

        // 2. rish
        sb.appendLine("║")
        sb.appendLine("║ [2] RISH")
        val rishReady = PrivilegedExecutionManager.isRishReady()
        sb.appendLine("║   Available  : ${if (rishReady) "✅" else "❌"}")
        if (!rishReady) sb.appendLine("║   → Run 'rish_setup' in privileged_tool")

        // 3. Termux
        sb.appendLine("║")
        sb.appendLine("║ [3] TERMUX")
        val termuxOk = TermuxEnvironmentBridge.isTermuxUsable()
        sb.appendLine("║   bash       : ${if (termuxOk) "✅ Found" else "❌ Not found"}")
        val ldExec = File(TermuxEnvironmentBridge.TERMUX_LIB + "/libtermux-exec.so").exists()
        sb.appendLine("║   libtermux-exec.so : ${if (ldExec) "✅" else "⚠️ Not found (Minor impact)"}")
        if (!termuxOk) sb.appendLine("║   → Install Termux from F-Droid")

        // 4. Python
        sb.appendLine("║")
        sb.appendLine("║ [4] PYTHON")
        val pyPath = TermuxEnvironmentBridge.findPythonInterpreter()
        if (pyPath != null) {
            val envPfx = if (pyPath.startsWith(TermuxEnvironmentBridge.TERMUX_BIN))
                TermuxEnvironmentBridge.buildEnvPrefix() else ""
            val version = PrivilegedExecutionManager.executeCommand(
                "${envPfx}${pyPath} --version 2>&1"
            ).getOrNull()?.trim() ?: "?"
            sb.appendLine("║   Path       : ✅ $pyPath")
            sb.appendLine("║   Version    : $version")
            // Actual test
            val testPy = PrivilegedExecutionManager.executeCommand(
                "${envPfx}${pyPath} -c \"print('py_ok')\" 2>&1"
            ).getOrNull()
            sb.appendLine("║   Test       : ${if (testPy?.contains("py_ok") == true) "✅ Works" else "❌ Failed: $testPy"}")
        } else {
            sb.appendLine("║   ❌ Python not found")
            sb.appendLine("║   → Run 'fix_python' or install Termux + python")
        }

        // 5. pip
        sb.appendLine("║")
        sb.appendLine("║ [5] PIP")
        val pipPath = findPip()
        sb.appendLine("║              : ${if (pipPath != null) "✅ $pipPath" else "❌ Not found"}")

        // 6. git
        sb.appendLine("║")
        sb.appendLine("║ [6] GIT")
        val gitPath = TermuxEnvironmentBridge.findBinary("git")
        sb.appendLine("║              : ${if (gitPath != null) "✅ $gitPath" else "❌ Not found"}")

        // 7. curl/wget
        sb.appendLine("║")
        sb.appendLine("║ [7] NETWORK TOOLS")
        val curlPath = TermuxEnvironmentBridge.findBinary("curl")
        val wgetPath = TermuxEnvironmentBridge.findBinary("wget")
        sb.appendLine("║   curl       : ${if (curlPath != null) "✅ $curlPath" else "❌"}")
        sb.appendLine("║   wget       : ${if (wgetPath != null) "✅ $wgetPath" else "❌"}")

        // Summary and recommendations
        sb.appendLine("║")
        sb.appendLine("║ ─── Repair Recommendations ───────────────────────────────────")
        val issues = mutableListOf<String>()
        if (!shizukuAvail) issues.add("• Run Shizuku app")
        if (shizukuAvail && !shizukuPerm) issues.add("• Grant Shizuku permission to the app")
        if (!termuxOk) issues.add("• Install Termux from F-Droid")
        if (pyPath == null) issues.add("• Run action=fix_python in execution_diagnostics")
        if (gitPath == null && termuxOk) issues.add("• Run action=pkg_install packages='git' in termux_bridge")

        if (issues.isEmpty()) {
            sb.appendLine("║   ✅ Everything looks good!")
        } else {
            issues.forEach { sb.appendLine("║   $it") }
        }

        sb.appendLine("╚═══════════════════════════════════════════════════════════════╝")
        return ToolExecutionResult(sb.toString().trimEnd())
    }

    // ──────────────────────────────────────────────────────────────
    // fix_shizuku
    // ──────────────────────────────────────────────────────────────
    private suspend fun fixShizuku(): ToolExecutionResult {
        val sb = StringBuilder("🔧 Fixing Shizuku\n\n")

        if (!ShizukuCommandTool.isAvailable()) {
            return ToolExecutionResult(
                sb.append("❌ Shizuku is not connected.\n" +
                "1. Open the Shizuku app\n" +
                "2. Press 'Start' (if rooted) or follow wireless ADB instructions\n" +
                "3. Re-run the command").toString()
            )
        }

        if (!ShizukuCommandTool.hasPermission()) {
            rikka.shizuku.Shizuku.requestPermission(1001)
            sb.appendLine("⏳ Requesting Shizuku permission — please wait for approval...")
            // Wait
            var waited = 0
            while (!ShizukuCommandTool.hasPermission() && waited < 15000) {
                kotlinx.coroutines.delay(500)
                waited += 500
            }
        }

        if (!ShizukuCommandTool.hasPermission()) {
            return ToolExecutionResult(
                sb.append("❌ Permission not granted after ${'$'}{waited}ms.\n" +
                "Open the Shizuku app and grant permission manually.").toString(),
                isError = true
            )
        }

        // Test
        val test = ShizukuCommandTool.execute("id && echo SHIZUKU_WORKS")
        return if (test.outputOrNull()?.contains("SHIZUKU_WORKS") == true) {
            ToolExecutionResult(
                sb.append("✅ Shizuku is working!\n").append(test.toDisplayString()).toString()
            )
        } else {
            ToolExecutionResult(
                sb.append("⚠️ Permission granted but test failed:\n${test.toDisplayString()}").toString(),
                isError = true
            )
        }
    }

    // ──────────────────────────────────────────────────────────────
    // fix_termux
    // ──────────────────────────────────────────────────────────────
    private suspend fun fixTermux(): ToolExecutionResult {
        val sb = StringBuilder("🔧 Fixing Termux Environment\n\n")

        if (!TermuxEnvironmentBridge.isTermuxUsable()) {
            return ToolExecutionResult(
                sb.append("❌ Termux bash not found in:\n${TermuxEnvironmentBridge.TERMUX_BASH}\n\n" +
                "Install Termux from F-Droid:\nhttps://f-droid.org/en/packages/com.termux/\n\n" +
                "After installation: Open Termux and run: pkg update && pkg upgrade -y").toString(),
                isError = true
            )
        }

        // Check /tmp
        val tmpDir = File(TermuxEnvironmentBridge.TERMUX_PREFIX + "/tmp")
        if (!tmpDir.exists()) {
            PrivilegedExecutionManager.executeCommand(
                "mkdir -p ${tmpDir.absolutePath} && chmod 755 ${tmpDir.absolutePath}"
            )
        }
        sb.appendLine("✅ Termux bash: Found")
        sb.appendLine("✅ TERMUX_PREFIX: ${TermuxEnvironmentBridge.TERMUX_PREFIX}")

        // Test env
        val envPfx = TermuxEnvironmentBridge.buildEnvPrefix()
        val envTest = PrivilegedExecutionManager.executeCommand(
            "${envPfx}${TermuxEnvironmentBridge.TERMUX_BASH} -c \"echo TERMUX_ENV_OK && python3 --version 2>&1 || echo no_python\" 2>&1"
        )
        val out = envTest.getOrNull() ?: "Failed"
        sb.appendLine("\nTermux environment test:\n$out")

        val ldSo = File(TermuxEnvironmentBridge.TERMUX_LIB + "/libtermux-exec.so")
        sb.appendLine("\nlibtermux-exec.so: ${if (ldSo.exists()) "✅ Found" else "⚠️ Not found (Not strictly necessary)"}")

        if (out.contains("TERMUX_ENV_OK")) {
            return ToolExecutionResult(sb.append("\n✅ Termux environment is working correctly.").toString())
        } else {
            return ToolExecutionResult(sb.append("\n⚠️ Issue with Termux environment. Try running:\npkg update\npkg upgrade -y").toString())
        }
    }

    // ──────────────────────────────────────────────────────────────
    // fix_python
    // ──────────────────────────────────────────────────────────────
    private suspend fun fixPython(): ToolExecutionResult {
        val sb = StringBuilder("🔧 Finding/Installing Python\n\n")

        // Search first
        val pyPath = TermuxEnvironmentBridge.findPythonInterpreter()
        if (pyPath != null) {
            val envPfx = if (pyPath.startsWith(TermuxEnvironmentBridge.TERMUX_BIN))
                TermuxEnvironmentBridge.buildEnvPrefix() else ""
            val ver = PrivilegedExecutionManager.executeCommand(
                "${envPfx}${pyPath} --version 2>&1"
            ).getOrNull() ?: "?"
            val test = PrivilegedExecutionManager.executeCommand(
                "${envPfx}${pyPath} -c \"import sys; print('py_ok', sys.version)\" 2>&1"
            ).getOrNull() ?: "?"
            sb.appendLine("✅ Python found: $pyPath")
            sb.appendLine("Version: $ver")
            sb.appendLine("Test: $test")
            return ToolExecutionResult(sb.toString())
        }

        sb.appendLine("❌ Python not found. Installing...")

        // Try via Termux
        if (TermuxEnvironmentBridge.isTermuxUsable()) {
            sb.appendLine("→ Trying pkg install python...")
            val installResult = TermuxEnvironmentBridge.pkgInstall("python")
            sb.appendLine(installResult.output)

            if (!installResult.isError) {
                val newPath = TermuxEnvironmentBridge.findPythonInterpreter()
                if (newPath != null) {
                    sb.appendLine("\n✅ Python installed: $newPath")
                    return ToolExecutionResult(sb.toString())
                }
            }
        }

        // Try via pip/pip3
        val pipInstall = PrivilegedExecutionManager.executeCommand(
            "pip3 install --upgrade pip 2>&1 || pip install --upgrade pip 2>&1"
        ).getOrNull()
        if (pipInstall != null) {
            sb.appendLine("pip: $pipInstall")
        }

        return ToolExecutionResult(
            sb.append("\n❌ Failed to install Python automatically.\n" +
            "Please:\n" +
            "1. Open Termux\n" +
            "2. Run: pkg install python\n" +
            "3. Try again").toString(),
            isError = true
        )
    }

    // ──────────────────────────────────────────────────────────────
    // fix_git
    // ──────────────────────────────────────────────────────────────
    private suspend fun fixGit(): ToolExecutionResult {
        val sb = StringBuilder("🔧 Finding/Installing git\n\n")

        val gitPath = TermuxEnvironmentBridge.findBinary("git")
        if (gitPath != null) {
            val version = PrivilegedExecutionManager.executeCommand("$gitPath --version 2>&1")
                .getOrNull() ?: "?"
            return ToolExecutionResult("✅ git found: $gitPath\n$version")
        }

        sb.appendLine("❌ git not found. Installing...")
        if (TermuxEnvironmentBridge.isTermuxUsable()) {
            val result = TermuxEnvironmentBridge.pkgInstall("git")
            sb.appendLine(result.output)
            val newPath = TermuxEnvironmentBridge.findBinary("git")
            return if (newPath != null) {
                ToolExecutionResult(sb.append("\n✅ git installed: $newPath").toString())
            } else {
                ToolExecutionResult(sb.append("\n❌ Installation failed.").toString(), isError = true)
            }
        }

        return ToolExecutionResult(
            sb.append("Install Termux first, then run: pkg install git").toString(),
            isError = true
        )
    }

    // ──────────────────────────────────────────────────────────────
    // test_command
    // ──────────────────────────────────────────────────────────────
    private suspend fun testCommand(command: String): ToolExecutionResult {
        val sb = StringBuilder("🧪 Testing command: ${command.take(100)}\n\n")

        // 1. Shizuku directly
        sb.appendLine("─── Shizuku directly ───")
        if (ShizukuCommandTool.isAvailable() && ShizukuCommandTool.hasPermission()) {
            val r = ShizukuCommandTool.execute(command)
            sb.appendLine("Result type: ${r::class.simpleName}")
            sb.appendLine("output: ${r.outputOrNull()?.take(300) ?: "(Empty)"}")
            if (r is ShizukuResult.PartialSuccess) sb.appendLine("exit code: ${r.exitCode}")
        } else {
            sb.appendLine("Shizuku is not available")
        }

        // 2. With Termux env
        sb.appendLine("\n─── With Termux env ───")
        if (TermuxEnvironmentBridge.isTermuxUsable()) {
            val envPfx = TermuxEnvironmentBridge.buildEnvPrefix()
            val r2 = PrivilegedExecutionManager.executeCommand("${envPfx}${command}")
            sb.appendLine("Result: ${r2.getOrNull()?.take(300) ?: r2.exceptionOrNull()?.message}")
        } else {
            sb.appendLine("Termux is not available")
        }

        // 3. PrivilegedExecutionManager
        sb.appendLine("\n─── PrivilegedExecutionManager ───")
        val r3 = PrivilegedExecutionManager.executeCommand(command)
        sb.appendLine("Result: ${r3.getOrNull()?.take(300) ?: r3.exceptionOrNull()?.message}")

        return ToolExecutionResult(sb.toString().trimEnd())
    }

    // ──────────────────────────────────────────────────────────────
    // repair_all
    // ──────────────────────────────────────────────────────────────
    private suspend fun repairAll(): ToolExecutionResult {
        val sb = StringBuilder("🔧 Automatic Full Repair\n\n")

        // 1. Shizuku
        sb.appendLine("=== [1/4] Shizuku ===")
        val shizukuFix = fixShizuku()
        sb.appendLine(shizukuFix.output)
        sb.appendLine()

        // 2. Termux
        sb.appendLine("=== [2/4] Termux ===")
        val termuxFix = fixTermux()
        sb.appendLine(termuxFix.output)
        sb.appendLine()

        // 3. Python
        sb.appendLine("=== [3/4] Python ===")
        val pyFix = fixPython()
        sb.appendLine(pyFix.output)
        sb.appendLine()

        // 4. Git
        sb.appendLine("=== [4/4] Git ===")
        val gitFix = fixGit()
        sb.appendLine(gitFix.output)
        sb.appendLine()

        val allOk = !shizukuFix.isError || !termuxFix.isError
        sb.appendLine(if (allOk) "✅ Repair completed. Try running commands again."
                     else "⚠️ Some issues require manual intervention (see details above).")

        return ToolExecutionResult(sb.toString().trimEnd(), isError = !allOk)
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────
    private suspend fun findPip(): String? {
        val candidates = listOf(
            "${TermuxEnvironmentBridge.TERMUX_BIN}/pip3",
            "${TermuxEnvironmentBridge.TERMUX_BIN}/pip",
            "/usr/bin/pip3", "/usr/bin/pip"
        )
        return candidates.firstOrNull { File(it).exists() }
            ?: PrivilegedExecutionManager.executeCommand("which pip3 2>/dev/null || which pip 2>/dev/null")
                .getOrNull()?.trim()?.takeIf { it.startsWith("/") }
    }
}
