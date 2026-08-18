package com.omnidev.workspace.data.tools

import android.util.Log
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * OmniExecutionDiagnostics — Context note Context note Context note Context note Context note.
 *
 * Tool name: "execution_diagnostics"
 *
 * ### Context note Context note Context note Context note
 * 1. **fix_shizuku** — Context note Context note Context note (reflection vs API mismatch) Context note
 *    `Shizuku.newProcess()` Context note Context note echo test Context note.
 * 2. **install_termux** — Context note Context note Context note Context note Termux Context note Shizuku.
 * 3. **repair_all** — Context note Context note install_termux Context note Termux Context note Context note.
 * 4. Context note Context note Context note Context note Context note Context note.
 */
object OmniExecutionDiagnostics {

    private const val TAG = "OmniExecDiag"

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "execution_diagnostics",
            description = """
Automatic diagnosis and repair tool for the execution environment.
Use it when:
• "no output" from Shizuku/rish
• Termux or Python is not found
• git, pkg install, or npm fails
• Any command execution issue

Actions:
• full_check      — Comprehensive check of all components + suggested fixes
• fix_shizuku     — Fix Shizuku: test Shizuku.newProcess() directly, diagnose reflection errors
• fix_termux      — Setup/verify Termux environment
• fix_python      — Find/install Python using the best available method
• fix_git         — Find/install git
• install_termux  — Download and install Termux APK via Shizuku (if Termux is missing)
• test_command    — Test a specific command across all backends and show full diagnostics
• repair_all      — Attempt to fix everything automatically
""".trimIndent(),
            parameters = listOf(
                ToolParameter("action", "string", "Action: full_check, fix_shizuku, fix_termux, fix_python, fix_git, install_termux, test_command, repair_all", required = true),
                ToolParameter("command", "string", "Command to test (for test_command)", required = false)
            )
        )
    )

    suspend fun execute(action: String, args: Map<String, String>): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            when (action.lowercase().trim()) {
                "full_check"     -> fullCheck()
                "fix_shizuku"    -> fixShizuku()
                "fix_termux"     -> fixTermux()
                "fix_python"     -> fixPython()
                "fix_git"        -> fixGit()
                "install_termux" -> installTermux()
                "test_command"   -> testCommand(args["command"]
                    ?: return@withContext ToolExecutionResult("Requires 'command'.", isError = true))
                "repair_all"     -> repairAll()
                else             -> ToolExecutionResult("Unknown action: '$action'.", isError = true)
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
            // Context note Context note Context note Shizuku.newProcess()
            val testResult = ShizukuCommandTool.execute("echo shizuku_ok")
            val works = testResult.outputOrNull()?.contains("shizuku_ok") == true
            sb.appendLine("║   newProcess : ${if (works) "✅ Direct call works" else "❌ Failed (${testResult.toDisplayString().take(80)})"}")
            if (!works) {
                sb.appendLine("║   → Fix : Run 'fix_shizuku'")
            }
        } else {
            when {
                !shizukuAvail -> sb.appendLine("║   → Install Shizuku from Play Store and start it")
                else          -> sb.appendLine("║   → Press 'Grant permission' in the Shizuku app")
            }
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
        if (!termuxOk) {
            sb.appendLine("║   → Install via: action=install_termux (auto)")
            sb.appendLine("║   → Or manually from F-Droid")
        }

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
            val testPy = PrivilegedExecutionManager.executeCommand(
                "${envPfx}${pyPath} -c \"print('py_ok')\" 2>&1"
            ).getOrNull()
            sb.appendLine("║   Test       : ${if (testPy?.contains("py_ok") == true) "✅ Works" else "❌ Failed: $testPy"}")
        } else {
            sb.appendLine("║   ❌ Python not found")
            sb.appendLine("║   → Run action=fix_python")
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

        // Recommendations
        sb.appendLine("║")
        sb.appendLine("║ ─── Repair Recommendations ───────────────────────────────────")
        val issues = mutableListOf<String>()
        if (!shizukuAvail) issues.add("• Run Shizuku app, then run 'fix_shizuku'")
        if (shizukuAvail && !shizukuPerm) issues.add("• Grant Shizuku permission to the app")
        if (!termuxOk) issues.add("• Run action=install_termux  OR  install Termux from F-Droid")
        if (pyPath == null) issues.add("• Run action=fix_python")
        if (gitPath == null && termuxOk) issues.add("• Run: termux_bridge action=pkg_install packages='git'")

        if (issues.isEmpty()) {
            sb.appendLine("║   ✅ Everything looks good!")
        } else {
            issues.forEach { sb.appendLine("║   $it") }
        }

        sb.appendLine("╚═══════════════════════════════════════════════════════════════╝")
        return ToolExecutionResult(sb.toString().trimEnd())
    }

    // ──────────────────────────────────────────────────────────────
    // fix_shizuku — Context note Context note
    // ──────────────────────────────────────────────────────────────
    private suspend fun fixShizuku(): ToolExecutionResult {
        val sb = StringBuilder("🔧 Diagnosing and Fixing Shizuku\n\n")

        // 1. Context note Context note Context note
        if (!ShizukuCommandTool.isAvailable()) {
            return ToolExecutionResult(
                sb.append(
                    "❌ Shizuku is NOT connected (pingBinder failed).\n\n" +
                    "Info:\n" +
                    "1. Info Info Shizuku\n" +
                    "2. Info 'Start' (Info Info root) Info Info Info wireless ADB\n" +
                    "3. Info Info Info Shizuku Info 'Running'\n" +
                    "4. Info Info Info Info"
                ).toString(),
                isError = true
            )
        }
        sb.appendLine("✅ Shizuku pingBinder: OK")

        // 2. Context note Context note Context note Context note
        if (!ShizukuCommandTool.hasPermission()) {
            sb.appendLine("⏳ Requesting Shizuku permission...")
            runCatching { rikka.shizuku.Shizuku.requestPermission(1001) }
            var waited = 0
            while (!ShizukuCommandTool.hasPermission() && waited < 15_000) {
                kotlinx.coroutines.delay(500)
                waited += 500
            }
        }

        if (!ShizukuCommandTool.hasPermission()) {
            return ToolExecutionResult(
                sb.append(
                    "❌ Permission not granted after 15s.\n\n" +
                    "Info:\n" +
                    "1. Info Info Shizuku\n" +
                    "2. Info Info Info Info\n" +
                    "3. Info Info OmniDev Workspace Info 'Info Info'"
                ).toString(),
                isError = true
            )
        }
        sb.appendLine("✅ Permission: Granted")

        // 3. Context note Shizuku.newProcess() Context note
        sb.appendLine("\n📋 Testing Shizuku.newProcess() directly...")

        val tests = listOf(
            "echo shizuku_works"          to "shizuku_works",
            "id"                          to "uid=",
            "getprop ro.build.version.sdk" to ""
        )

        var allPassed = true
        for ((cmd, expectedKeyword) in tests) {
            val result = ShizukuCommandTool.execute(cmd)
            val output = result.outputOrNull()
            val passed = if (expectedKeyword.isEmpty()) {
                output != null && output != "(no output)"
            } else {
                output?.contains(expectedKeyword, ignoreCase = true) == true
            }

            sb.appendLine("  $ $cmd")
            sb.appendLine("    ${if (passed) "✅" else "❌"} ${output?.take(80) ?: result.toDisplayString().take(80)}")
            if (!passed) allPassed = false
        }

        return if (allPassed) {
            sb.appendLine("\n✅ Shizuku.newProcess() is working correctly!")
            sb.appendLine("Info Info Info Info Info Info:")
            sb.appendLine("  privileged_tool action=shell command=getprop ro.build.version.release")
            sb.appendLine("  privileged_tool action=getprop key=ro.build.version.sdk")
            ToolExecutionResult(sb.toString())
        } else {
            sb.appendLine("\n⚠️ Info Info Info.")
            sb.appendLine("Info:")
            sb.appendLine("  1. Shizuku service Info — Info Info")
            sb.appendLine("  2. Info Shizuku Info — Info Info")
            sb.appendLine("  3. Info Info Info Info — Info Info Shizuku")
            ToolExecutionResult(sb.toString(), isError = true)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // fix_termux
    // ──────────────────────────────────────────────────────────────
    private suspend fun fixTermux(): ToolExecutionResult {
        val sb = StringBuilder("🔧 Fixing Termux Environment\n\n")

        if (!TermuxEnvironmentBridge.isTermuxUsable()) {
            return ToolExecutionResult(
                sb.append(
                    "❌ Termux bash not found: ${TermuxEnvironmentBridge.TERMUX_BASH}\n\n" +
                    "Info:\n" +
                    "1. Info Info: action=install_termux\n" +
                    "2. Context note Context note: https://f-droid.org/en/packages/com.termux/\n" +
                    "   Info Info: pkg update && pkg upgrade -y"
                ).toString(),
                isError = true
            )
        }

        // Context note Context note /tmp
        val tmpDir = File(TermuxEnvironmentBridge.TERMUX_PREFIX + "/tmp")
        if (!tmpDir.exists()) {
            PrivilegedExecutionManager.executeCommand(
                "mkdir -p ${tmpDir.absolutePath} && chmod 755 ${tmpDir.absolutePath}"
            )
        }
        sb.appendLine("✅ Termux bash: Found")
        sb.appendLine("✅ TERMUX_PREFIX: ${TermuxEnvironmentBridge.TERMUX_PREFIX}")

        // Context note Context note
        val envPfx = TermuxEnvironmentBridge.buildEnvPrefix()
        val envTest = PrivilegedExecutionManager.executeCommand(
            "${envPfx}${TermuxEnvironmentBridge.TERMUX_BASH} -c \"echo TERMUX_ENV_OK && python3 --version 2>&1 || echo no_python\" 2>&1"
        )
        val out = envTest.getOrNull() ?: "Failed: ${envTest.exceptionOrNull()?.message}"
        sb.appendLine("\nTermux environment test:\n$out")

        val ldSo = File(TermuxEnvironmentBridge.TERMUX_LIB + "/libtermux-exec.so")
        sb.appendLine("\nlibtermux-exec.so: ${if (ldSo.exists()) "✅ Found" else "⚠️ Not found (Not strictly required)"}")

        return if (out.contains("TERMUX_ENV_OK")) {
            ToolExecutionResult(sb.append("\n✅ Termux environment is working correctly.").toString())
        } else {
            ToolExecutionResult(sb.append("\n⚠️ Issue with Termux environment. Try:\npkg update\npkg upgrade -y").toString())
        }
    }

    // ──────────────────────────────────────────────────────────────
    // fix_python
    // ──────────────────────────────────────────────────────────────
    private suspend fun fixPython(): ToolExecutionResult {
        val sb = StringBuilder("🔧 Finding/Installing Python\n\n")

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

        sb.appendLine("❌ Python not found. Trying to install...")

        // Context note Termux
        if (TermuxEnvironmentBridge.isTermuxUsable()) {
            sb.appendLine("→ pkg install python via Termux...")
            val installResult = TermuxEnvironmentBridge.pkgInstall("python")
            sb.appendLine(installResult.output)

            if (!installResult.isError) {
                val newPath = TermuxEnvironmentBridge.findPythonInterpreter()
                if (newPath != null) {
                    sb.appendLine("\n✅ Python installed: $newPath")
                    return ToolExecutionResult(sb.toString())
                }
            }
        } else {
            sb.appendLine("⚠️ Termux not available. Trying install_termux first...")
            val termuxInstall = installTermux()
            sb.appendLine(termuxInstall.output)
            if (!termuxInstall.isError) {
                // Context note Context note Context note TermuxContext note Context note Context note Python
                if (TermuxEnvironmentBridge.isTermuxUsable()) {
                    TermuxEnvironmentBridge.pkgInstall("python")
                    val newPath = TermuxEnvironmentBridge.findPythonInterpreter()
                    if (newPath != null) {
                        sb.appendLine("✅ Python installed: $newPath")
                        return ToolExecutionResult(sb.toString())
                    }
                }
            }
        }

        return ToolExecutionResult(
            sb.append(
                "\n❌ Cannot install Python automatically.\n" +
                "Info Info:\n" +
                "1. Info Termux\n" +
                "2. pkg install python\n" +
                "3. Info Info Info"
            ).toString(),
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
            sb.append(
                "Termux not available. Run action=install_termux first,\n" +
                "then: pkg install git"
            ).toString(),
            isError = true
        )
    }

    // ──────────────────────────────────────────────────────────────
    // install_termux — Context note Context note
    // ──────────────────────────────────────────────────────────────
    private suspend fun installTermux(): ToolExecutionResult {
        val sb = StringBuilder("📦 Installing Termux via Shizuku\n\n")

        if (TermuxEnvironmentBridge.isTermuxUsable()) {
            return ToolExecutionResult("✅ Termux is already installed: ${TermuxEnvironmentBridge.TERMUX_BASH}")
        }

        if (!PrivilegedExecutionManager.isShizukuReady()) {
            return ToolExecutionResult(
                sb.append(
                    "❌ Shizuku Info Info. Info Info Info Termux Info.\n\n" +
                    "Info:\n" +
                    "1. Info Shizuku Info: action=fix_shizuku\n" +
                    "2. Info Termux Info Info F-Droid:\n" +
                    "   https://f-droid.org/en/packages/com.termux/"
                ).toString(),
                isError = true
            )
        }

        sb.appendLine("Shizuku Info. Info Info Termux APK...")
        sb.appendLine(PrivilegedExecutionManager.getTermuxBootstrapHints())
        sb.appendLine()

        val result = PrivilegedExecutionManager.installTermuxViaShizuku()
        sb.appendLine(result.getOrElse { "❌ Info: ${it.message}" })

        return if (result.isSuccess) {
            ToolExecutionResult(sb.toString())
        } else {
            ToolExecutionResult(
                sb.append(
                    "\n\nInfo — Info Info Info Shizuku shell:\n" +
                    "  privileged_tool action=shell command=\"wget -O /data/local/tmp/termux.apk https://f-droid.org/repo/com.termux_118.apk && pm install -r -g /data/local/tmp/termux.apk\""
                ).toString(),
                isError = true
            )
        }
    }

    // ──────────────────────────────────────────────────────────────
    // test_command
    // ──────────────────────────────────────────────────────────────
    private suspend fun testCommand(command: String): ToolExecutionResult {
        val sb = StringBuilder("🧪 Testing command: ${command.take(100)}\n\n")

        // 1. Shizuku Context note
        sb.appendLine("─── [1] Shizuku.newProcess() Info ───")
        if (ShizukuCommandTool.isAvailable() && ShizukuCommandTool.hasPermission()) {
            val r = ShizukuCommandTool.execute(command)
            sb.appendLine("Type: ${r::class.simpleName}")
            sb.appendLine("Output: ${r.outputOrNull()?.take(300) ?: "(Info)"}")
            if (r is ShizukuResult.PartialSuccess) sb.appendLine("Exit: ${r.exitCode}")
        } else {
            sb.appendLine("⚠️ Shizuku Info Info/Info")
        }

        // 2. Context note Context note Termux
        sb.appendLine("\n─── [2] Info Termux env ───")
        if (TermuxEnvironmentBridge.isTermuxUsable()) {
            val envPfx = TermuxEnvironmentBridge.buildEnvPrefix()
            val r2 = PrivilegedExecutionManager.executeCommand("${envPfx}${command}")
            sb.appendLine("Result: ${r2.getOrNull()?.take(300) ?: r2.exceptionOrNull()?.message}")
        } else {
            sb.appendLine("⚠️ Termux Info Info")
        }

        // 3. PrivilegedExecutionManager
        sb.appendLine("\n─── [3] PrivilegedExecutionManager ───")
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
        sb.appendLine("=== [1/5] Shizuku ===")
        val shizukuFix = fixShizuku()
        sb.appendLine(shizukuFix.output)
        sb.appendLine()

        // 2. Termux (Context note Context note Context note Context note)
        if (!TermuxEnvironmentBridge.isTermuxUsable()) {
            sb.appendLine("=== [2/5] Termux (Info) ===")
            val termuxInstall = installTermux()
            sb.appendLine(termuxInstall.output)
            sb.appendLine()
        } else {
            sb.appendLine("=== [2/5] Termux ===")
            val termuxFix = fixTermux()
            sb.appendLine(termuxFix.output)
            sb.appendLine()
        }

        // 3. rish setup
        sb.appendLine("=== [3/5] rish ===")
        if (!PrivilegedExecutionManager.isRishReady()) {
            sb.appendLine("⚠️ rish Info Info — Info: privileged_tool action=rish_setup")
        } else {
            sb.appendLine("✅ rish Info")
        }
        sb.appendLine()

        // 4. Python
        sb.appendLine("=== [4/5] Python ===")
        val pyFix = fixPython()
        sb.appendLine(pyFix.output)
        sb.appendLine()

        // 5. Git
        sb.appendLine("=== [5/5] Git ===")
        val gitFix = fixGit()
        sb.appendLine(gitFix.output)
        sb.appendLine()

        val hasShizuku = !shizukuFix.isError
        val hasTermux  = TermuxEnvironmentBridge.isTermuxUsable()

        sb.appendLine(
            if (hasShizuku) "✅ Shizuku Info — Info Info Info."
            else "⚠️ Info Info Info Info Info (Info Info Info)."
        )

        if (!hasTermux) {
            sb.appendLine("⚠️ Termux Info Info — Info Info Info Info Shizuku shell Info.")
        }

        return ToolExecutionResult(sb.toString().trimEnd(), isError = !hasShizuku && !hasTermux)
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────
    private suspend fun findPip(): String? {
        val candidates = listOf(
            "${TermuxEnvironmentBridge.TERMUX_BIN}/pip3",
            "${TermuxEnvironmentBridge.TERMUX_BIN}/pip",
            "/usr/bin/pip3",
            "/usr/bin/pip"
        )
        return candidates.firstOrNull { File(it).exists() }
            ?: PrivilegedExecutionManager.executeCommand(
                "which pip3 2>/dev/null || which pip 2>/dev/null"
            ).getOrNull()?.trim()?.takeIf { it.startsWith("/") }
    }
}
