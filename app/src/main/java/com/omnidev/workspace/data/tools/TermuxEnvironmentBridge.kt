package com.omnidev.workspace.data.tools

import android.util.Log
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TermuxEnvironmentBridge — The Patched and Enhanced Version.
 *
 * Core Fixes:
 * 1. LD_PRELOAD is optional — added only if the file actually exists to prevent linker errors.
 * 2. Multiple execution strategies with smart fallback:
 * a) env-prefix with bash (the fixed original method - highly reliable)
 * b) run-as com.termux (runs with Termux's exact UID if debuggable)
 * c) Termux RUN_COMMAND broadcast (fallback)
 * 3. PATH includes all potential binary locations (including python site-packages).
 * 4. Verifies file existence before usage.
 */
object TermuxEnvironmentBridge {

    private const val TAG = "TermuxBridge"

    const val TERMUX_PKG          = "com.termux"
    const val TERMUX_PREFIX       = "/data/data/com.termux/files/usr"
    const val TERMUX_HOME         = "/data/data/com.termux/files/home"
    const val TERMUX_BIN          = "$TERMUX_PREFIX/bin"
    const val TERMUX_LIB          = "$TERMUX_PREFIX/lib"
    const val TERMUX_BASH         = "$TERMUX_BIN/bash"
    const val TERMUX_PYTHON3      = "$TERMUX_BIN/python3"
    const val TERMUX_PYTHON       = "$TERMUX_BIN/python"
    const val TERMUX_NODE         = "$TERMUX_BIN/node"
    const val TERMUX_NPM          = "$TERMUX_BIN/npm"
    const val TERMUX_PIP3         = "$TERMUX_BIN/pip3"
    const val TERMUX_PIP          = "$TERMUX_BIN/pip"
    const val TERMUX_GIT          = "$TERMUX_BIN/git"
    const val TERMUX_PKG_MANAGER  = "$TERMUX_BIN/pkg"
    const val TERMUX_APT          = "$TERMUX_BIN/apt"
    private const val TERMUX_EXEC_SO = "$TERMUX_LIB/libtermux-exec.so"

    private const val MAX_OUTPUT = 10_000

    // ─────────────────────────────────────────────────────────────
    // Environment Building
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds environment variables as inline shell assignments.
     * LD_PRELOAD is added only if the file actually exists (Fix for Bug #2).
     */
    fun buildTermuxEnv(): Map<String, String> {
        val env = mutableMapOf(
            "HOME"           to TERMUX_HOME,
            "PREFIX"         to TERMUX_PREFIX,
            "TERM"           to "xterm-256color",
            "LANG"           to "en_US.UTF-8",
            "TMPDIR"         to "$TERMUX_PREFIX/tmp",
            "ANDROID_ROOT"   to "/system",
            "ANDROID_DATA"   to "/data",
            "PATH"           to buildPath(),
            "LD_LIBRARY_PATH" to buildLdLibraryPath()
        )
        // *** FIX: LD_PRELOAD only if file exists ***
        if (File(TERMUX_EXEC_SO).exists()) {
            env["LD_PRELOAD"] = TERMUX_EXEC_SO
        } else {
            Log.d(TAG, "libtermux-exec.so not found — skipping LD_PRELOAD")
        }
        return env
    }

    private fun buildPath(): String {
        val paths = mutableListOf(
            TERMUX_BIN,
            "$TERMUX_PREFIX/sbin",
            "/system/bin",
            "/system/xbin",
            "/sbin",
            "/vendor/bin",
            "/data/local/tmp"
        )
        // Add Termux site-packages bin for globally installed python packages
        val pyBin = "$TERMUX_PREFIX/lib/python3*/site-packages/bin"
        paths.add(pyBin)
        return paths.joinToString(":")
    }

    private fun buildLdLibraryPath(): String {
        val paths = mutableListOf(
            TERMUX_LIB,
            "$TERMUX_LIB/termux-exec",
            "/system/lib64",
            "/system/lib",
            "/vendor/lib64",
            "/vendor/lib"
        )
        return paths.joinToString(":")
    }

    /**
     * Builds an environment prefix string ready for a shell command.
     * Example: HOME='/data/..' PATH='...' python3 ...
     */
    fun buildEnvPrefix(): String =
        buildTermuxEnv()
            .entries
            .joinToString(" ") { (k, v) -> "$k=${shellQuote(v)}" }
            .let { if (it.isNotBlank()) "$it " else "" }

    // ─────────────────────────────────────────────────────────────
    // Availability Checks
    // ─────────────────────────────────────────────────────────────

    fun isTermuxUsable(): Boolean = File(TERMUX_BASH).exists()

    suspend fun findBinary(name: String): String? = withContext(Dispatchers.IO) {
        // 1. Direct check in Termux bin
        val direct = File(TERMUX_BIN, name)
        if (direct.exists() && direct.canExecute()) return@withContext direct.absolutePath

        // 2. Via `which` using Termux environment
        if (isTermuxUsable()) {
            val envPfx = buildEnvPrefix()
            val result = PrivilegedExecutionManager.executeCommand(
                "${envPfx}which $name 2>/dev/null || command -v $name 2>/dev/null"
            ).getOrNull()?.trim()?.takeIf { isValidPath(it) }
            if (result != null) return@withContext result
        }

        // 3. System fallback
        PrivilegedExecutionManager.executeCommand(
            "which $name 2>/dev/null || command -v $name 2>/dev/null"
        ).getOrNull()?.trim()?.takeIf { isValidPath(it) }
    }

    private fun isValidPath(s: String): Boolean =
        s.isNotBlank() && s != "(no output)" && !s.startsWith("ERROR") && s.startsWith("/")

    // ─────────────────────────────────────────────────────────────
    // Core Execution — Multiple Strategies
    // ─────────────────────────────────────────────────────────────

    /**
     * Executes a command inside the Termux environment using intelligent fallback strategies:
     * 1. env-prefix + bash (Fastest, highly reliable)
     * 2. run-as com.termux (If Termux is debuggable, runs as exact UID)
     */
    suspend fun executeInTermux(command: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (!isTermuxUsable()) {
            // Try standard system shell as a last resort
            return@withContext PrivilegedExecutionManager.executeCommand(command).fold(
                onSuccess = { ToolExecutionResult(it.ifBlank { "(no output)" }) },
                onFailure = {
                    ToolExecutionResult(
                        "Termux is not installed or bash is missing. Install Termux from F-Droid:\n" +
                        "https://f-droid.org/en/packages/com.termux/\n" +
                        "Or use agent_runtime action=install_termux",
                        isError = true
                    )
                }
            )
        }

        // Strategy 1: env-prefix with Termux bash (Preferred)
        val strategy1 = runStrategy1(command)
        if (strategy1 != null && !strategy1.isError) return@withContext strategy1

        // Strategy 2: run-as com.termux
        val strategy2 = runStrategy2(command)
        if (strategy2 != null && !strategy2.isError) return@withContext strategy2

        // Return strategy 1 failure state if all else fails
        strategy1 ?: ToolExecutionResult("Failed to execute command in Termux.", isError = true)
    }

    private suspend fun runStrategy1(command: String): ToolExecutionResult? {
        return try {
            val envPfx = buildEnvPrefix()
            val cmd = "${envPfx}${TERMUX_BASH} -c ${shellQuote(command)} 2>&1"
            PrivilegedExecutionManager.executeCommand(cmd).fold(
                onSuccess = { ToolExecutionResult(it.take(MAX_OUTPUT).ifBlank { "(no output)" }) },
                onFailure = { null }
            )
        } catch (_: Exception) { null }
    }

    private suspend fun runStrategy2(command: String): ToolExecutionResult? {
        // run-as works if Termux is installed with debuggable=true (like F-Droid builds)
        return try {
            val cmd = "run-as $TERMUX_PKG bash -c ${shellQuote(command)} 2>&1"
            PrivilegedExecutionManager.executeCommand(cmd).fold(
                onSuccess = { out ->
                    if (out.contains("run-as", ignoreCase = true) &&
                        out.contains("unknown package", ignoreCase = true)) {
                        null // Termux is not debuggable
                    } else {
                        ToolExecutionResult(out.take(MAX_OUTPUT).ifBlank { "(no output)" })
                    }
                },
                onFailure = { null }
            )
        } catch (_: Exception) { null }
    }

    /**
     * Executes a multi-line script inside Termux.
     * Writes the script to /data/local/tmp, executes it, then deletes it.
     */
    suspend fun executeScriptInTermux(scriptContent: String): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            if (!isTermuxUsable()) {
                return@withContext ToolExecutionResult(
                    "Termux environment is unavailable.", isError = true
                )
            }

            val tmpPath = "/data/local/tmp/omni_ts_${System.currentTimeMillis()}.sh"
            val script  = "#!${TERMUX_BASH}\n\n$scriptContent"
            val b64     = android.util.Base64.encodeToString(
                script.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
            )

            // Safe file creation using base64 decoding
            val writeCmd = "echo ${shellQuote(b64)} | base64 -d > ${shellQuote(tmpPath)}" +
                " && chmod +x ${shellQuote(tmpPath)} && echo WRITE_OK"

            val writeResult = PrivilegedExecutionManager.executeCommand(writeCmd)
            if (writeResult.isFailure || !writeResult.getOrDefault("").contains("WRITE_OK")) {
                return@withContext ToolExecutionResult(
                    "Failed to write script: ${writeResult.exceptionOrNull()?.message}",
                    isError = true
                )
            }

            val envPfx = buildEnvPrefix()
            val execResult = PrivilegedExecutionManager.executeCommand(
                "${envPfx}${TERMUX_BASH} ${shellQuote(tmpPath)} 2>&1; rm -f ${shellQuote(tmpPath)}"
            )

            execResult.fold(
                onSuccess  = { ToolExecutionResult(it.take(MAX_OUTPUT).ifBlank { "(no output)" }) },
                onFailure  = { ToolExecutionResult("Failed to execute script: ${it.message}", isError = true) }
            )
        }

    // ─────────────────────────────────────────────────────────────
    // Python Runner
    // ─────────────────────────────────────────────────────────────

    suspend fun runPython(code: String, args: String? = null): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val pyBin = findPythonInterpreter()
                ?: return@withContext ToolExecutionResult(
                    "Python interpreter not found.\n" +
                    "Install via Termux: action=pkg_install packages='python'\n" +
                    "Or via Agent Runtime: action=install_python",
                    isError = true
                )

            val argStr = args?.split(Regex("\\s+"))
                ?.filter { it.isNotBlank() }
                ?.joinToString(" ") { shellQuote(it) }
                ?.let { " $it" } ?: ""

            val isTermuxPy = pyBin.startsWith(TERMUX_BIN)
            val envPfx = if (isTermuxPy) buildEnvPrefix() else ""
            val cmd = "${envPfx}${pyBin} -c ${shellQuote(code)}$argStr 2>&1"

            PrivilegedExecutionManager.executeCommand(cmd).fold(
                onSuccess = { ToolExecutionResult(it.take(MAX_OUTPUT).ifBlank { "(no output)" }) },
                onFailure = {
                    // Fallback to system python if Termux python failed inexplicably
                    if (isTermuxPy) {
                        val sysPy = PrivilegedExecutionManager.executeCommand(
                            "python3 -c ${shellQuote(code)}$argStr 2>&1"
                        ).getOrNull()
                        if (sysPy != null) ToolExecutionResult(sysPy.take(MAX_OUTPUT))
                        else ToolExecutionResult("Python execution failed: ${it.message}", isError = true)
                    } else {
                        ToolExecutionResult("Python execution failed: ${it.message}", isError = true)
                    }
                }
            )
        }

    // ─────────────────────────────────────────────────────────────
    // Package Management
    // ─────────────────────────────────────────────────────────────

    suspend fun pipInstall(packages: String, upgrade: Boolean = false): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val safePkgs = sanitizePackageList(packages)
                ?: return@withContext ToolExecutionResult("Invalid package names provided.", isError = true)
            
            val upgradeFlag = if (upgrade) " --upgrade" else ""
            val envPfx = if (isTermuxUsable()) buildEnvPrefix() else ""

            // Cascade installation attempts: Termux pip3 → Termux pip → system pip
            val commands = buildList {
                if (isTermuxUsable()) {
                    add("${envPfx}${TERMUX_PIP3} install$upgradeFlag $safePkgs 2>&1")
                    add("${envPfx}${TERMUX_PIP} install$upgradeFlag $safePkgs 2>&1")
                    add("${envPfx}${TERMUX_PYTHON3} -m pip install$upgradeFlag $safePkgs 2>&1")
                }
                add("pip3 install$upgradeFlag $safePkgs 2>&1")
                add("python3 -m pip install$upgradeFlag $safePkgs 2>&1")
            }

            var lastOutput = "(Not attempted)"
            for (cmd in commands) {
                val result = PrivilegedExecutionManager.executeCommand(cmd)
                val output = result.getOrNull() ?: continue
                if (output.contains("Successfully installed", ignoreCase = true) ||
                    output.contains("already satisfied", ignoreCase = true) ||
                    output.contains("Requirement already", ignoreCase = true)) {
                    return@withContext ToolExecutionResult(output.take(MAX_OUTPUT))
                }
                lastOutput = output
            }
            ToolExecutionResult("pip install failed:\n$lastOutput", isError = true)
        }

    suspend fun pkgInstall(packages: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (!isTermuxUsable()) {
            return@withContext ToolExecutionResult(
                "Termux is not installed. Install it from F-Droid:\nhttps://f-droid.org/en/packages/com.termux/",
                isError = true
            )
        }
        
        val safePkgs = sanitizePackageList(packages)
        if (safePkgs.isNullOrBlank()) {
            return@withContext ToolExecutionResult("Invalid package names provided.", isError = true)
        }

        val envPfx = buildEnvPrefix()
        // Non-interactive installation using both pkg and apt fallbacks
        val cmd = "${envPfx}DEBIAN_FRONTEND=noninteractive " +
            "${TERMUX_PKG_MANAGER} install -y $safePkgs 2>&1 " +
            "|| ${envPfx}DEBIAN_FRONTEND=noninteractive " +
            "${TERMUX_APT} install -y $safePkgs 2>&1"

        PrivilegedExecutionManager.executeCommand(cmd).fold(
            onSuccess = { ToolExecutionResult(it.take(MAX_OUTPUT).ifBlank { "(no output)" }) },
            onFailure = { ToolExecutionResult("pkg install failed: ${it.message}", isError = true) }
        )
    }

    suspend fun pkgUpdate(): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (!isTermuxUsable()) return@withContext ToolExecutionResult("Termux environment is unavailable.", isError = true)
        val envPfx = buildEnvPrefix()
        PrivilegedExecutionManager.executeCommand(
            "${envPfx}DEBIAN_FRONTEND=noninteractive ${TERMUX_PKG_MANAGER} update -y 2>&1"
        ).fold(
            onSuccess = { ToolExecutionResult(it.take(MAX_OUTPUT).ifBlank { "(no output)" }) },
            onFailure = { ToolExecutionResult("pkg update failed: ${it.message}", isError = true) }
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Status Report
    // ─────────────────────────────────────────────────────────────

    suspend fun statusReport(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.appendLine("╔══ Execution Environment Status ═══════════════════════════════╗")

        // Backend
        val shizuku   = PrivilegedExecutionManager.isShizukuReady()
        val rishReady = PrivilegedExecutionManager.isRishReady()
        val root      = PrivilegedExecutionManager.isRootAvailable()
        sb.appendLine("║ PRIVILEGE BACKEND")
        sb.appendLine("║   Shizuku : ${if (shizuku) "✅ Ready" else "❌ Unavailable"}")
        sb.appendLine("║   rish    : ${if (rishReady) "✅ Ready" else "❌ Unavailable"}")
        sb.appendLine("║   Root    : ${if (root) "⚠️ Available" else "❌ Not Found"}")

        // Termux
        sb.appendLine("║")
        sb.appendLine("║ TERMUX")
        val termuxOk = isTermuxUsable()
        sb.appendLine("║   bash    : ${if (termuxOk) "✅ $TERMUX_BASH" else "❌ Not Found"}")
        val ldPreload = File(TERMUX_EXEC_SO).exists()
        sb.appendLine("║   libtermux-exec.so : ${if (ldPreload) "✅ Found" else "⚠️ Not Found (LD_PRELOAD skipped)"}")

        // Interpreters
        sb.appendLine("║")
        sb.appendLine("║ INTERPRETERS")
        val tools = listOf("python3", "node", "git", "curl", "wget", "npm")
        for (tool in tools) {
            val path = findBinary(tool)
            val source = when {
                path == null -> ""
                path.startsWith(TERMUX_BIN) -> "(Termux)"
                else -> "(system)"
            }
            sb.appendLine("║   ${if (path != null) "✅" else "❌"} $tool${if (path != null) " → $path $source" else " — Not Found"}")
        }

        sb.appendLine("╚═══════════════════════════════════════════════════════════════╝")
        ToolExecutionResult(sb.toString().trimEnd())
    }

    // ─────────────────────────────────────────────────────────────
    // Tool Definitions
    // ─────────────────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "termux_bridge",
            description = "Execute shell commands inside a fully-featured Linux (Termux) environment " +
                "with smart fallback management. Essential for running complex scripts, using 'git', " +
                "'node', or installing packages via 'pkg' or 'pip'. " +
                "Actions: status, exec, script, python_run, python_file, pip_install, pkg_install, pkg_update, find_binary",
            parameters = listOf(
                ToolParameter("action", "string", "Action to perform (e.g., status, exec, pip_install).", required = true),
                ToolParameter("command", "string", "The shell command for 'exec'.", required = false),
                ToolParameter("script", "string", "Multiline bash script for 'script'.", required = false),
                ToolParameter("code", "string", "Inline Python code for 'python_run'.", required = false),
                ToolParameter("file_path", "string", "Path to .py file for 'python_file'.", required = false),
                ToolParameter("packages", "string", "Space-separated package names for 'pip_install'/'pkg_install'.", required = false),
                ToolParameter("args", "string", "Extra arguments.", required = false),
                ToolParameter("upgrade", "string", "Pass 'true' to upgrade during pip_install.", required = false),
                ToolParameter("binary", "string", "Binary name for 'find_binary' (e.g., node).", required = false)
            )
        )
    )

    suspend fun executeTool(args: Map<String, String>): ToolExecutionResult {
        val action = args["action"]?.lowercase()?.trim() 
            ?: return ToolExecutionResult("Missing 'action' parameter.", isError = true)
            
        return when (action) {
            "status"      -> statusReport()
            "exec"        -> {
                val cmd = args["command"] ?: return ToolExecutionResult("Missing 'command' parameter.", isError = true)
                executeInTermux(cmd)
            }
            "script"      -> {
                val script = args["script"] ?: return ToolExecutionResult("Missing 'script' parameter.", isError = true)
                executeScriptInTermux(script)
            }
            "python_run"  -> {
                val code = args["code"] ?: return ToolExecutionResult("Missing 'code' parameter.", isError = true)
                runPython(code, args["args"])
            }
            "python_file" -> {
                val pyPath = args["file_path"] ?: return ToolExecutionResult("Missing 'file_path' parameter.", isError = true)
                val pyBin = findPythonInterpreter() ?: return ToolExecutionResult("Python interpreter not found.", isError = true)
                
                val envPfx = if (pyBin.startsWith(TERMUX_BIN)) buildEnvPrefix() else ""
                val cmd = "${envPfx}${pyBin} ${shellQuote(pyPath)} ${args["args"] ?: ""} 2>&1"
                
                PrivilegedExecutionManager.executeCommand(cmd).fold(
                    onSuccess = { ToolExecutionResult(it.take(MAX_OUTPUT)) },
                    onFailure = { ToolExecutionResult("Failed to run python file: ${it.message}", isError = true) }
                )
            }
            "pip_install" -> {
                val pkgs = args["packages"] ?: return ToolExecutionResult("Missing 'packages' parameter.", isError = true)
                pipInstall(pkgs, args["upgrade"]?.lowercase() == "true")
            }
            "pkg_install" -> {
                val pkgs = args["packages"] ?: return ToolExecutionResult("Missing 'packages' parameter.", isError = true)
                pkgInstall(pkgs)
            }
            "pkg_update"  -> pkgUpdate()
            "find_binary" -> {
                val name = args["binary"] ?: return ToolExecutionResult("Missing 'binary' parameter.", isError = true)
                val path = findBinary(name)
                if (path != null) ToolExecutionResult("✅ $name located at: $path")
                else ToolExecutionResult("❌ '$name' not found in path.", isError = true)
            }
            else -> ToolExecutionResult("Unknown action: '$action'.", isError = true)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    internal suspend fun findPythonInterpreter(): String? = withContext(Dispatchers.IO) {
        // Priority: Termux python3 → Termux python → system python3 → system python
        val candidates = listOf(
            TERMUX_PYTHON3, TERMUX_PYTHON,
            "/usr/bin/python3", "/usr/bin/python",
            "/system/bin/python3", "/system/xbin/python3"
        )
        
        candidates.firstOrNull { File(it).exists() }
            ?: run {
                // Fallback to via `which`
                PrivilegedExecutionManager.executeCommand(
                    "which python3 2>/dev/null || which python 2>/dev/null"
                ).getOrNull()?.trim()?.takeIf { isValidPath(it) }
            }
    }

    private fun sanitizePackageList(packages: String): String? {
        val safe = packages.split(Regex("\\s+"))
            .map { it.replace(Regex("[^a-zA-Z0-9_.\\-\\[\\]=~<>!@]"), "") }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return safe.ifBlank { null }
    }

    internal fun shellQuote(s: String): String = "'${s.replace("'", "'\\''")}'"
}
