package com.omnidev.workspace.data.tools

import android.util.Log
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ═══════════════════════════════════════════════════════════════════════════════
// TERMUX EXECUTION FIX
//
// Root cause of "nothing shows" even with Shizuku enabled:
//
//   Shizuku runs commands as a privileged shell (uid=2000 or uid=0), but this
//   shell is NOT the Termux process. Termux binaries (python3, bash, node...) are
//   compiled against Termux's own libc patched via termux-exec. Without:
//
//     LD_PRELOAD=/data/data/com.termux/files/usr/lib/libtermux-exec.so
//
//   …the dynamic linker loads the system libc instead, which has different
//   syscall intercepts, and the binary either:
//     • silently exits with no output, OR
//     • crashes with "CANNOT LINK EXECUTABLE" / "library not found"
//
//   The old buildEnvPrefix() set PATH + LD_LIBRARY_PATH but MISSED LD_PRELOAD.
//   That single missing variable is why Termux packages appear broken from Shizuku.
//
// Secondary fix — proper env export:
//   Prefixing "KEY=VAL command" only applies env to that one command.
//   Using "export KEY=VAL && command" ensures subshells and child processes
//   also inherit the correct environment.
//
// Tertiary fix — standalone fallback:
//   If Termux is not installed, TermuxExecutionFix provides a standalone
//   execution path using:
//     1. System busybox (usually at /system/xbin/busybox or /data/adb/magisk/busybox)
//     2. Static binaries downloaded to /data/local/tmp/omni_bins/
//     3. Android's built-in toybox (ls, cat, cp, etc. — available on API 23+)
// ═══════════════════════════════════════════════════════════════════════════════

object TermuxExecutionFix {

    private const val TAG = "TermuxExecFix"

    // ─────────────────────────────────────────────────────────────────────────
    // Core paths
    // ─────────────────────────────────────────────────────────────────────────

    private const val T_PREFIX  = EnvironmentSetupManager.TERMUX_PREFIX
    private const val T_BIN     = EnvironmentSetupManager.TERMUX_BIN
    private const val T_LIB     = "$T_PREFIX/lib"
    private const val T_HOME    = EnvironmentSetupManager.TERMUX_HOME
    private const val T_TMP     = "$T_PREFIX/tmp"

    /**
     * Path to the critical missing piece — Termux's libc exec wrapper.
     * Without LD_PRELOAD pointing here, ALL Termux binaries fail from Shizuku.
     */
    private const val TERMUX_EXEC_PRELOAD = "$T_LIB/libtermux-exec.so"

    /** Standalone bin dir for static binaries (when Termux is absent). */
    private const val STANDALONE_BIN = "/data/local/tmp/omni_bins"

    // Toybox is built into Android ≥ API 23 at these locations
    private val TOYBOX_PATHS = listOf(
        "/system/bin/toybox",
        "/system/xbin/toybox"
    )
    // Busybox locations — Magisk, standalone installs, some ROMs
    private val BUSYBOX_PATHS = listOf(
        "/data/adb/magisk/busybox",
        "/data/adb/modules/busybox-ndk/bin/busybox",
        "/system/xbin/busybox",
        "/data/local/busybox",
        "/sbin/busybox"
    )

    // ─────────────────────────────────────────────────────────────────────────
    // THE FIX: corrected buildEnvPrefix()
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds a properly escaped `export` block for running Termux binaries
     * from a non-Termux shell (e.g. Shizuku / rish / root).
     *
     * Key differences from the old implementation:
     *
     *  OLD (broken):
     *    "PREFIX=... HOME=... PATH=... LD_LIBRARY_PATH=... command"
     *    • No LD_PRELOAD → Termux binaries crash/silently fail
     *    • Inline assignment only — subshells don't inherit it
     *
     *  NEW (fixed):
     *    Uses a proper export block with LD_PRELOAD → binaries actually run.
     *    Returns a script fragment ending with "\n" that must be prepended
     *    to the actual command inside the same sh session.
     *
     * Usage:
     *   val script = buildString {
     *       append(TermuxExecutionFix.buildTermuxEnvBlock())
     *       appendLine("python3 -c 'print(\"hello\")'")
     *   }
     *   EnvironmentSetupManager.executeShell(script, useBase64 = true)
     */
    fun buildTermuxEnvBlock(): String {
        if (!isTermuxInstalled()) return ""

        val preloadPart = if (File(TERMUX_EXEC_PRELOAD).exists())
            "export LD_PRELOAD=\"$TERMUX_EXEC_PRELOAD\"\n"
        else {
            Log.w(TAG, "libtermux-exec.so not found at $TERMUX_EXEC_PRELOAD — " +
                       "Termux binaries may fail. Try: pkg install termux-exec")
            ""
        }

        return buildString {
            appendLine("export PREFIX=\"$T_PREFIX\"")
            appendLine("export HOME=\"$T_HOME\"")
            appendLine("export TMPDIR=\"$T_TMP\"")
            appendLine("export PATH=\"$T_BIN:/usr/bin:/bin:/system/bin\"")
            appendLine("export LD_LIBRARY_PATH=\"$T_LIB\"")
            append(preloadPart)
            appendLine("export TERM=\"xterm-256color\"")
            appendLine("export LANG=\"en_US.UTF-8\"")
            appendLine("export DEBIAN_FRONTEND=\"noninteractive\"")
        }
    }

    /**
     * One-liner env prefix for simple inline commands (like the old approach).
     * Still includes LD_PRELOAD. Safe for single commands, but [buildTermuxEnvBlock]
     * is preferred for multi-line scripts.
     */
    fun buildInlineEnvPrefix(): String {
        if (!isTermuxInstalled()) return ""
        val preload = if (File(TERMUX_EXEC_PRELOAD).exists())
            "LD_PRELOAD=\"$TERMUX_EXEC_PRELOAD\" " else ""
        return "PREFIX=\"$T_PREFIX\" HOME=\"$T_HOME\" TMPDIR=\"$T_TMP\" " +
               "PATH=\"$T_BIN:/usr/bin:/bin\" LD_LIBRARY_PATH=\"$T_LIB\" " +
               "${preload}LANG=\"en_US.UTF-8\" "
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Verified execution — run a command and confirm Termux env works
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Smoke test: can we actually run a Termux binary from Shizuku?
     *
     * Returns a [TermuxExecStatus] describing what works and what doesn't.
     * Run this once on startup (or on first env_check) to diagnose the issue.
     */
    suspend fun diagnose(): TermuxExecStatus = withContext(Dispatchers.IO) {
        Log.i(TAG, "Running Termux execution diagnostics...")

        val installed    = isTermuxInstalled()
        val preloadFound = File(TERMUX_EXEC_PRELOAD).exists()
        val backendOk    = PrivilegedExecutionManager.isShizukuReady() ||
                           PrivilegedExecutionManager.isRishReady()   ||
                           PrivilegedExecutionManager.isRootAvailable()

        val pythonPath = if (installed) {
            listOf(
                EnvironmentSetupManager.TERMUX_PYTHON3,
                EnvironmentSetupManager.TERMUX_PYTHON
            ).firstOrNull { File(it).exists() }
        } else null

        val bashPath = if (installed && File(EnvironmentSetupManager.TERMUX_BASH).exists())
            EnvironmentSetupManager.TERMUX_BASH else null

        // Test 1: Can we run bash at all?
        val bashOk = if (bashPath != null && backendOk) {
            val script = buildString {
                append(buildTermuxEnvBlock())
                appendLine("$bashPath -c 'echo BASH_OK' 2>&1")
            }
            val r = PrivilegedExecutionManager.executeCommand(
                buildBase64Command(script)
            )
            r.getOrNull()?.contains("BASH_OK") == true
        } else false

        // Test 2: Can we run python?
        val pythonOk = if (pythonPath != null && backendOk) {
            val script = buildString {
                append(buildTermuxEnvBlock())
                appendLine("$pythonPath -c 'print(\"PYTHON_OK\")' 2>&1")
            }
            val r = PrivilegedExecutionManager.executeCommand(
                buildBase64Command(script)
            )
            r.getOrNull()?.contains("PYTHON_OK") == true
        } else false

        // Test 3: Can we run pkg/apt?
        val pkgOk = if (installed && backendOk && bashOk) {
            val pkgBin = when {
                File(EnvironmentSetupManager.TERMUX_PKG).exists() -> EnvironmentSetupManager.TERMUX_PKG
                File(EnvironmentSetupManager.TERMUX_APT).exists() -> EnvironmentSetupManager.TERMUX_APT
                else -> null
            }
            if (pkgBin != null) {
                val script = buildString {
                    append(buildTermuxEnvBlock())
                    appendLine("$pkgBin list 2>&1 | head -3")
                }
                val r = PrivilegedExecutionManager.executeCommand(
                    buildBase64Command(script)
                )
                !r.isFailure && r.getOrNull()?.isNotBlank() == true
            } else false
        } else false

        // Standalone tools available?
        val toybox  = TOYBOX_PATHS.firstOrNull  { File(it).exists() }
        val busybox = BUSYBOX_PATHS.firstOrNull { File(it).exists() }

        TermuxExecStatus(
            termuxInstalled  = installed,
            preloadFound     = preloadFound,
            backendAvailable = backendOk,
            bashWorks        = bashOk,
            pythonWorks      = pythonOk,
            pkgWorks         = pkgOk,
            toyboxPath       = toybox,
            busyboxPath      = busybox,
            issues           = buildIssueList(installed, preloadFound, backendOk, bashOk, pythonOk)
        )
    }

    /**
     * Execute a command properly within the Termux environment.
     * This is the replacement for all old `TermuxEnvironmentBridge.executeSingleShot()` calls.
     *
     * Automatically:
     *  - Prepends [buildTermuxEnvBlock()] to fix LD_PRELOAD
     *  - Uses Base64 injection for reliability
     *  - Falls back to standalone execution if Termux env fails
     */
    suspend fun execute(
        command:  String,
        cwd:      String? = null,
        fallback: Boolean = true
    ): ToolExecutionResult = withContext(Dispatchers.IO) {

        if (!isTermuxInstalled()) {
            return@withContext if (fallback) {
                executeStandalone(command, cwd)
            } else {
                ToolExecutionResult(
                    "❌ Termux is not installed. Install it from F-Droid to use this feature.\n" +
                    "https://f-droid.org/packages/com.termux/",
                    isError = true
                )
            }
        }

        val script = buildString {
            append(buildTermuxEnvBlock())
            if (!cwd.isNullOrBlank()) {
                appendLine("cd ${sq(cwd)} || { echo \"cd failed: $cwd\"; exit 1; }")
            }
            appendLine(command)
        }

        val result = PrivilegedExecutionManager.executeCommand(buildBase64Command(script))

        result.fold(
            onSuccess = { output ->
                ToolExecutionResult(output.trim().take(MAX_OUTPUT).ifBlank { "(no output)" })
            },
            onFailure = { e ->
                Log.w(TAG, "Termux exec failed: ${e.message}")
                if (fallback) executeStandalone(command, cwd)
                else ToolExecutionResult("❌ ${e.message?.take(1000)}", isError = true)
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Standalone execution (no Termux)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Execute a command using only Android built-in tools:
     *  • /system/bin/sh (always available)
     *  • toybox (available Android 6+)
     *  • busybox (if installed)
     *  • static binaries in [STANDALONE_BIN]
     *
     * This allows the agent to do basic file/network ops even without Termux.
     */
    private suspend fun executeStandalone(
        command: String,
        cwd:     String?
    ): ToolExecutionResult {
        val busybox = BUSYBOX_PATHS.firstOrNull { File(it).exists() }
        val toybox  = TOYBOX_PATHS.firstOrNull  { File(it).exists() }

        val standaloneEnv = buildString {
            appendLine("export PATH=\"$STANDALONE_BIN:/system/bin:/system/xbin:/sbin\"")
            if (busybox != null) {
                appendLine("# busybox available at $busybox")
                // Alias common commands to busybox if system doesn't have them
                appendLine("for cmd in wget curl python python3 bash; do")
                appendLine("  command -v \$cmd >/dev/null 2>&1 || alias \$cmd=\"$busybox \$cmd\" 2>/dev/null || true")
                appendLine("done")
            }
            if (toybox != null) appendLine("# toybox available at $toybox")
        }

        val script = buildString {
            append(standaloneEnv)
            if (!cwd.isNullOrBlank()) appendLine("cd ${sq(cwd)} || true")
            appendLine(command)
        }

        val result = PrivilegedExecutionManager.executeCommand(buildBase64Command(script))
        return result.fold(
            onSuccess = { output ->
                ToolExecutionResult(output.trim().take(MAX_OUTPUT).ifBlank { "(no output)" })
            },
            onFailure = { e ->
                ToolExecutionResult(
                    "❌ Standalone execution failed: ${e.message?.take(500)}\n\n" +
                    "Install Termux from F-Droid for full package support.",
                    isError = true
                )
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Package management via corrected env
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Install packages via Termux pkg/apt with the FIXED environment.
     * This replaces the old `pkgInstall` which worked intermittently.
     */
    suspend fun pkgInstall(packages: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (!isTermuxInstalled()) {
            return@withContext ToolExecutionResult(
                "❌ Termux is not installed.\n" +
                "Install from F-Droid: https://f-droid.org/packages/com.termux/",
                isError = true
            )
        }

        val safePkgs = packages.split(Regex("\\s+"))
            .map { it.replace(Regex("[^a-zA-Z0-9_.\\-+]"), "") }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

        if (safePkgs.isBlank()) {
            return@withContext ToolExecutionResult("❌ No valid package names provided.", isError = true)
        }

        val pkgMgr = when {
            File(EnvironmentSetupManager.TERMUX_PKG).exists() -> EnvironmentSetupManager.TERMUX_PKG
            File(EnvironmentSetupManager.TERMUX_APT).exists() -> EnvironmentSetupManager.TERMUX_APT
            else -> return@withContext ToolExecutionResult(
                "❌ Termux package manager not found. Termux may be corrupted.", isError = true
            )
        }

        val script = buildString {
            append(buildTermuxEnvBlock())
            // Ensure TERMUX_APP_PACKAGE_MANAGER is unset (it causes issues in non-Termux shells)
            appendLine("unset TERMUX_APP_PACKAGE_MANAGER 2>/dev/null || true")
            appendLine("$pkgMgr install -y $safePkgs 2>&1")
        }

        Log.i(TAG, "pkg install: $safePkgs")
        val r = PrivilegedExecutionManager.executeCommand(buildBase64Command(script))

        r.fold(
            onSuccess = { output ->
                val success = output.contains("installed") || output.contains("Processing") ||
                              output.contains("already installed")
                if (success || !r.isFailure) {
                    ToolExecutionResult("✅ pkg install $safePkgs:\n${output.take(MAX_OUTPUT)}")
                } else {
                    ToolExecutionResult("⚠️ pkg may have failed:\n${output.take(MAX_OUTPUT)}", isError = true)
                }
            },
            onFailure = { e ->
                ToolExecutionResult("❌ pkg install failed: ${e.message?.take(500)}", isError = true)
            }
        )
    }

    /**
     * Run Python via the fixed Termux env.
     * The critical difference: LD_PRELOAD is set, so python3 actually loads.
     */
    suspend fun runPython(
        code:      String,
        args:      String? = null,
        cwd:       String? = null,
        venvPath:  String? = null
    ): ToolExecutionResult = withContext(Dispatchers.IO) {

        val pyBin = resolvePythonBin(venvPath)
            ?: return@withContext ToolExecutionResult(
                "❌ Python not found.\n" +
                "Fix: action=pkg_install packages='python'\n" +
                "     (or action=bootstrap for full setup)",
                isError = true
            )

        val argStr = args?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            ?.joinToString(" ") { sq(it) }
            ?.let { " $it" } ?: ""

        val script = buildString {
            append(buildTermuxEnvBlock())
            if (venvPath != null) {
                // Activate venv
                appendLine(". ${sq("$venvPath/bin/activate")} 2>/dev/null || true")
            }
            if (!cwd.isNullOrBlank()) appendLine("cd ${sq(cwd)} || exit 1")
            appendLine("$pyBin -c ${sq(code)}$argStr 2>&1")
        }

        val r = PrivilegedExecutionManager.executeCommand(buildBase64Command(script))
        r.fold(
            onSuccess = { ToolExecutionResult(it.trim().take(MAX_OUTPUT).ifBlank { "(no output)" }) },
            onFailure = { e -> ToolExecutionResult("❌ Python error: ${e.message?.take(500)}", isError = true) }
        )
    }

    /**
     * Enhanced status report that specifically diagnoses the LD_PRELOAD issue.
     */
    suspend fun fullDiagnosticReport(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val status = diagnose()
        val sb = StringBuilder()

        sb.appendLine("╔══ Termux Execution Diagnostics ════════════════════════════╗")
        sb.appendLine("║")
        sb.appendLine("║  Termux installed  : ${yn(status.termuxInstalled)}")
        sb.appendLine("║  libtermux-exec.so : ${yn(status.preloadFound)}  ← KEY FIX")
        sb.appendLine("║  Privilege backend : ${yn(status.backendAvailable)}")
        sb.appendLine("║  bash works        : ${yn(status.bashWorks)}")
        sb.appendLine("║  python works      : ${yn(status.pythonWorks)}")
        sb.appendLine("║  pkg/apt works     : ${yn(status.pkgWorks)}")
        sb.appendLine("║  toybox            : ${status.toyboxPath ?: "❌ not found"}")
        sb.appendLine("║  busybox           : ${status.busyboxPath ?: "❌ not found"}")
        sb.appendLine("║")

        if (status.issues.isNotEmpty()) {
            sb.appendLine("║  ISSUES:")
            status.issues.forEach { sb.appendLine("║  ⚠️  $it") }
            sb.appendLine("║")
            sb.appendLine("║  FIXES:")
            buildFixSuggestions(status).forEach { sb.appendLine("║  → $it") }
        } else {
            sb.appendLine("║  ✅ All systems operational!")
        }

        sb.appendLine("╚════════════════════════════════════════════════════════════╝")
        ToolExecutionResult(sb.toString().trimEnd())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns the corrected env block for use in other components. */
    fun patchedEnvPrefix(): String = buildTermuxEnvBlock()

    fun isTermuxInstalled(): Boolean = File(EnvironmentSetupManager.TERMUX_BASH).exists()

    private fun resolvePythonBin(venvPath: String?): String? {
        if (venvPath != null) {
            listOf("$venvPath/bin/python3", "$venvPath/bin/python")
                .firstOrNull { File(it).exists() }?.let { return it }
        }
        return listOf(
            EnvironmentSetupManager.TERMUX_PYTHON3,
            EnvironmentSetupManager.TERMUX_PYTHON
        ).firstOrNull { File(it).exists() }
    }

    /**
     * Wraps [script] in a base64 decode + execute pipeline.
     * This is the ONLY reliable way to pass multi-line scripts with special
     * characters through the Shizuku/rish binder interface.
     */
    fun buildBase64Command(script: String): String {
        val b64 = android.util.Base64.encodeToString(
            script.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        val tmp = "/data/local/tmp/omni_fix_${System.currentTimeMillis()}.sh"
        return "echo '$b64' | base64 -d > $tmp && chmod 755 $tmp && " +
               "sh $tmp 2>&1; EC=\$?; rm -f $tmp; exit \$EC"
    }

    private fun buildIssueList(
        installed:  Boolean,
        preload:    Boolean,
        backend:    Boolean,
        bash:       Boolean,
        python:     Boolean
    ): List<String> = buildList {
        if (!backend)   add("No privilege backend (Shizuku/rish/root) — cannot execute shell commands")
        if (!installed) add("Termux is not installed")
        if (installed && !preload) add("libtermux-exec.so missing — Termux binaries will crash from Shizuku. Fix: pkg install termux-exec")
        if (installed && preload && !bash) add("bash not working despite preload — may need Termux update")
        if (installed && preload && bash && !python) add("python3 not installed in Termux. Fix: pkg install python")
    }

    private fun buildFixSuggestions(status: TermuxExecStatus): List<String> = buildList {
        if (!status.backendAvailable) {
            add("Install Shizuku from Play Store and start it")
            add("OR enable ADB wireless debugging and use rish")
        }
        if (!status.termuxInstalled) {
            add("Install Termux from F-Droid (NOT Play Store)")
            add("https://f-droid.org/packages/com.termux/")
        }
        if (status.termuxInstalled && !status.preloadFound) {
            add("Open Termux and run: pkg install termux-exec")
            add("This installs libtermux-exec.so which is REQUIRED for Shizuku execution")
        }
        if (status.termuxInstalled && !status.pythonWorks) {
            add("action=pkg_install packages='python'")
        }
    }

    private fun yn(b: Boolean) = if (b) "✅" else "❌"
    private fun sq(s: String) = "'${s.replace("'", "'\\''")}'"

    private const val MAX_OUTPUT = 12_000
}

// ─────────────────────────────────────────────────────────────────────────────
// Result type
// ─────────────────────────────────────────────────────────────────────────────

data class TermuxExecStatus(
    val termuxInstalled:  Boolean,
    val preloadFound:     Boolean,
    val backendAvailable: Boolean,
    val bashWorks:        Boolean,
    val pythonWorks:      Boolean,
    val pkgWorks:         Boolean,
    val toyboxPath:       String?,
    val busyboxPath:      String?,
    val issues:           List<String>
) {
    val isFullyOperational: Boolean get() =
        termuxInstalled && preloadFound && backendAvailable && bashWorks

    val canInstallPackages: Boolean get() = isFullyOperational && pkgWorks
}
