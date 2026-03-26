package com.omnidev.workspace.data.tools

import android.content.Context
import android.content.pm.PackageManager
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TermuxEnvironmentBridge — intelligent bridge for executing commands inside Termux's
 * full Linux environment without requiring the user to open Termux manually.
 *
 * ### Why this exists
 * Termux ships its own libc (bionic-based), dynamic linker, and a full GNU/Linux
 * userspace at `/data/data/com.termux/files/usr/`. Binaries like `python3`, `node`,
 * `gcc`, `clang`, `git`, etc. link against libraries in that prefix and therefore
 * **cannot** run when invoked bare from an ADB/Shizuku shell — they'll crash with
 * "cannot locate library" errors.  This bridge solves that by:
 *
 * 1. **Auto-detecting** whether Termux is installed and which interpreter/binary
 *    the agent needs.
 * 2. **Building a complete environment** (LD_LIBRARY_PATH, PATH, HOME, PREFIX …)
 *    matching what Termux injects when you open its terminal.
 * 3. **Cascading fallback**: Termux env → system PATH → busybox → fail with clear message.
 * 4. **Package bootstrap**: if a binary is missing, automatically suggest (or execute)
 *    `pkg install` to obtain it.
 *
 * ### Thread safety
 * All public `suspend` functions dispatch to [Dispatchers.IO].
 *
 * ### Security
 * Execution goes through [PrivilegedExecutionManager] (Shizuku → rish → root).
 * All user-supplied arguments are POSIX-quoted before shell injection.
 */
object TermuxEnvironmentBridge {

    // ─────────────────────────────────────────────────────────────────────
    // Termux filesystem constants
    // ─────────────────────────────────────────────────────────────────────

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

    /** Max characters captured from any single command output. */
    private const val MAX_OUTPUT = 10_000

    // ─────────────────────────────────────────────────────────────────────
    // Environment map
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns the full environment variable map that Termux exports when its
     * terminal opens.  Pass this to any shell command that needs Termux's libc/libs.
     */
    fun buildTermuxEnv(): Map<String, String> = mapOf(
        "HOME"              to TERMUX_HOME,
        "PREFIX"            to TERMUX_PREFIX,
        "TERMUX_VERSION"    to "0.118",
        "TERM"              to "xterm-256color",
        "COLORTERM"         to "truecolor",
        "PATH"              to "$TERMUX_BIN:/system/bin:/system/xbin:/sbin",
        "LD_LIBRARY_PATH"   to "$TERMUX_LIB:/system/lib64:/system/lib",
        "LD_PRELOAD"        to "$TERMUX_LIB/libtermux-exec.so",
        "LANG"              to "en_US.UTF-8",
        "TMPDIR"            to "$TERMUX_PREFIX/tmp",
        "ANDROID_ROOT"      to "/system",
        "ANDROID_DATA"      to "/data"
    )

    /**
     * Builds a single-line `env KEY=VALUE … command` prefix string suitable
     * for prepending to any shell command executed via Shizuku/root.
     */
    fun buildEnvPrefix(): String =
        buildTermuxEnv().entries.joinToString(" ") { (k, v) -> "$k=${shellQuote(v)}" } + " "

    // ─────────────────────────────────────────────────────────────────────
    // Availability checks
    // ─────────────────────────────────────────────────────────────────────

    /** Returns true if the Termux package is installed (does not check running state). */
    fun isTermuxInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getApplicationInfo(TERMUX_PKG, 0)
        true
    }.getOrDefault(false)

    /** Returns true if Termux's `bash` binary exists on disk. */
    fun isTermuxUsable(): Boolean = File(TERMUX_BASH).exists()

    /**
     * Checks whether [binaryName] is available — first inside Termux's prefix,
     * then on the system PATH via `which`.
     *
     * @return The full path to the binary, or `null` if not found anywhere.
     */
    suspend fun findBinary(binaryName: String): String? = withContext(Dispatchers.IO) {
        val termuxPath = File(TERMUX_BIN, binaryName)
        if (termuxPath.exists()) return@withContext termuxPath.absolutePath

        // Try system PATH via `which`
        val result = PrivilegedExecutionManager.executeCommand(
            "which ${shellQuote(binaryName)} 2>/dev/null"
        ).getOrNull()?.trim()

        if (!result.isNullOrBlank() && result != "(no output)" &&
            !result.startsWith("ERROR", ignoreCase = true)) {
            return@withContext result
        }
        null
    }

    // ─────────────────────────────────────────────────────────────────────
    // Core execution
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Execute [command] inside the full Termux environment.
     *
     * Wraps the command with `env KEY=VALUE… bash -c 'command'` so that all
     * Termux-linked binaries resolve their shared libraries correctly.
     *
     * Falls back to a bare Shizuku/root execution when Termux bash is not found.
     */
    suspend fun executeInTermux(command: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (!isTermuxUsable()) {
            // Termux bash not found — try plain system shell via PrivilegedExecutionManager
            return@withContext PrivilegedExecutionManager.executeCommand(command)
                .fold(
                    onSuccess = { ToolExecutionResult(it.ifBlank { "(no output)" }) },
                    onFailure = { ToolExecutionResult(
                        "Termux is not available and system shell failed: ${it.message}. " +
                        "Install Termux from F-Droid: https://f-droid.org/en/packages/com.termux/",
                        isError = true
                    )}
                )
        }

        val envPrefix = buildEnvPrefix()
        val fullCmd = "${envPrefix}${TERMUX_BASH} -c ${shellQuote(command)} 2>&1"
        PrivilegedExecutionManager.executeCommand(fullCmd).fold(
            onSuccess = { ToolExecutionResult(it.take(MAX_OUTPUT).ifBlank { "(no output)" }) },
            onFailure = { ToolExecutionResult("Termux execution failed: ${it.message}", isError = true) }
        )
    }

    /**
     * Execute a multi-line shell script inside Termux's bash.
     *
     * Writes the script to `/data/local/tmp/` (writable by ADB shell), executes it,
     * then removes the temp file.
     */
    suspend fun executeScriptInTermux(scriptContent: String): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            if (!isTermuxUsable()) {
                return@withContext ToolExecutionResult(
                    "Termux bash not found. Install Termux from F-Droid.",
                    isError = true
                )
            }

            val tmpPath = "/data/local/tmp/omni_termux_script_${System.currentTimeMillis()}.sh"
            val fullScript = "#!/data/data/com.termux/files/usr/bin/bash\n\n$scriptContent"
            val b64 = android.util.Base64.encodeToString(
                fullScript.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )

            // Write script via base64 decode to avoid quoting hell
            val writeResult = PrivilegedExecutionManager.executeCommand(
                "echo ${shellQuote(b64)} | base64 -d > ${shellQuote(tmpPath)} && " +
                "chmod +x ${shellQuote(tmpPath)} && echo WRITE_OK"
            )
            if (writeResult.isFailure || writeResult.getOrDefault("").contains("WRITE_OK").not()) {
                return@withContext ToolExecutionResult(
                    "Failed to write script to $tmpPath: ${writeResult.exceptionOrNull()?.message}",
                    isError = true
                )
            }

            val envPrefix = buildEnvPrefix()
            val execResult = PrivilegedExecutionManager.executeCommand(
                "${envPrefix}${TERMUX_BASH} ${shellQuote(tmpPath)} 2>&1 | head -c $MAX_OUTPUT; " +
                "rm -f ${shellQuote(tmpPath)}"
            )

            execResult.fold(
                onSuccess = { ToolExecutionResult(it.ifBlank { "(no output)" }) },
                onFailure = { ToolExecutionResult("Script execution failed: ${it.message}", isError = true) }
            )
        }

    // ─────────────────────────────────────────────────────────────────────
    // Python runner
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Run inline Python [code] using the best available Python interpreter.
     *
     * Resolution order:
     *  1. Termux python3  (`/data/data/com.termux/files/usr/bin/python3`)
     *  2. Termux python   (`/data/data/com.termux/files/usr/bin/python`)
     *  3. System python3  (via `which python3`)
     *  4. System python   (via `which python`)
     *
     * @param code   Python source to execute.
     * @param args   Optional extra sys.argv arguments.
     * @return [ToolExecutionResult] with stdout/stderr or a descriptive error.
     */
    suspend fun runPython(code: String, args: String? = null): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val pythonBin = findPythonInterpreter()
                ?: return@withContext ToolExecutionResult(
                    "Python not found. Install via Termux:\n" +
                    "  1. Open Termux\n  2. Run: pkg install python\n" +
                    "Or use agent_runtime action=termux_pkg_install packages='python'",
                    isError = true
                )

            val argStr = args?.split(Regex("\\s+"))
                ?.filter { it.isNotBlank() }
                ?.joinToString(" ") { shellQuote(it) }
                ?.let { " $it" } ?: ""

            val isTermuxPython = pythonBin.startsWith(TERMUX_BIN)
            val envPrefix = if (isTermuxPython) buildEnvPrefix() else ""
            val cmd = "${envPrefix}${pythonBin} -c ${shellQuote(code)}$argStr 2>&1"

            PrivilegedExecutionManager.executeCommand(cmd).fold(
                onSuccess = { ToolExecutionResult(it.take(MAX_OUTPUT).ifBlank { "(no output)" }) },
                onFailure = { ToolExecutionResult("Python run failed: ${it.message}", isError = true) }
            )
        }

    /**
     * Run a Python file at [filePath] with optional [args].
     * Automatically uses Termux env if the interpreter is from Termux.
     */
    suspend fun runPythonFile(filePath: String, args: String? = null): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val pythonBin = findPythonInterpreter()
                ?: return@withContext ToolExecutionResult(
                    "Python interpreter not found. Install via Termux: pkg install python",
                    isError = true
                )
            val argStr = args?.split(Regex("\\s+"))
                ?.filter { it.isNotBlank() }
                ?.joinToString(" ") { shellQuote(it) }
                ?.let { " $it" } ?: ""

            val isTermuxPython = pythonBin.startsWith(TERMUX_BIN)
            val envPrefix = if (isTermuxPython) buildEnvPrefix() else ""
            val cmd = "${envPrefix}${pythonBin} ${shellQuote(filePath)}$argStr 2>&1"

            PrivilegedExecutionManager.executeCommand(cmd).fold(
                onSuccess = { ToolExecutionResult(it.take(MAX_OUTPUT).ifBlank { "(no output)" }) },
                onFailure = { ToolExecutionResult("Python file run failed: ${it.message}", isError = true) }
            )
        }

    /**
     * Install Python packages via pip inside Termux.
     *
     * Tries pip3/pip in Termux first, then `python3 -m pip` as fallback.
     */
    suspend fun pipInstall(packages: String, upgrade: Boolean = false): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val safePkgs = packages.split(Regex("\\s+"))
                .map { it.replace(Regex("[^a-zA-Z0-9_.\\-\\[\\]=~]"), "") }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            if (safePkgs.isEmpty()) {
                return@withContext ToolExecutionResult("No valid package names.", isError = true)
            }
            val upgradeFlag = if (upgrade) " --upgrade" else ""
            val envPrefix = if (isTermuxUsable()) buildEnvPrefix() else ""

            // Cascade: Termux pip3 → Termux pip → Termux python3 -m pip → system pip3 → system pip
            val cmd = buildString {
                if (isTermuxUsable()) {
                    append("${envPrefix}${TERMUX_PIP3} install$upgradeFlag $safePkgs 2>&1")
                    append(" || ${envPrefix}${TERMUX_PIP} install$upgradeFlag $safePkgs 2>&1")
                    append(" || ${envPrefix}${TERMUX_PYTHON3} -m pip install$upgradeFlag $safePkgs 2>&1")
                    append(" || ${envPrefix}${TERMUX_PYTHON} -m pip install$upgradeFlag $safePkgs 2>&1")
                    append(" || ")
                }
                append("pip3 install$upgradeFlag $safePkgs 2>&1")
                append(" || pip install$upgradeFlag $safePkgs 2>&1")
                append(" || python3 -m pip install$upgradeFlag $safePkgs 2>&1")
            }
            PrivilegedExecutionManager.executeCommand(cmd).fold(
                onSuccess = { ToolExecutionResult(it.take(MAX_OUTPUT).ifBlank { "(no output)" }) },
                onFailure = { ToolExecutionResult("pip install failed: ${it.message}", isError = true) }
            )
        }

    // ─────────────────────────────────────────────────────────────────────
    // Package management
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Install Termux packages via `pkg install` or `apt install` in a non-interactive mode.
     *
     * Requires Shizuku or root to run Termux's package manager as the `shell` user
     * with the correct Termux environment variables.
     */
    suspend fun pkgInstall(packages: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (!isTermuxUsable()) {
            return@withContext ToolExecutionResult(
                "Termux is not installed or its bash is missing. " +
                "Install Termux from F-Droid: https://f-droid.org/en/packages/com.termux/",
                isError = true
            )
        }
        val safePkgs = packages.split(Regex("\\s+"))
            .map { it.replace(Regex("[^a-zA-Z0-9_.\\-+]"), "") }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (safePkgs.isEmpty()) {
            return@withContext ToolExecutionResult("No valid package names.", isError = true)
        }

        val envPrefix = buildEnvPrefix()
        // DEBIAN_FRONTEND=noninteractive suppresses interactive prompts
        val cmd = "${envPrefix}DEBIAN_FRONTEND=noninteractive " +
                  "${TERMUX_PKG_MANAGER} install -y $safePkgs 2>&1 " +
                  "|| ${envPrefix}DEBIAN_FRONTEND=noninteractive " +
                  "${TERMUX_APT} install -y $safePkgs 2>&1"

        PrivilegedExecutionManager.executeCommand(cmd).fold(
            onSuccess = { ToolExecutionResult(it.take(MAX_OUTPUT).ifBlank { "(no output)" }) },
            onFailure = { ToolExecutionResult("pkg install failed: ${it.message}", isError = true) }
        )
    }

    /**
     * Update Termux's package repositories (`pkg update`).
     */
    suspend fun pkgUpdate(): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (!isTermuxUsable()) {
            return@withContext ToolExecutionResult("Termux not available.", isError = true)
        }
        val envPrefix = buildEnvPrefix()
        PrivilegedExecutionManager.executeCommand(
            "${envPrefix}DEBIAN_FRONTEND=noninteractive ${TERMUX_PKG_MANAGER} update -y 2>&1"
        ).fold(
            onSuccess = { ToolExecutionResult(it.take(MAX_OUTPUT).ifBlank { "(no output)" }) },
            onFailure = { ToolExecutionResult("pkg update failed: ${it.message}", isError = true) }
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Auto-bootstrap
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Comprehensive environment status report covering Termux, Python, Node, Git,
     * and the privilege backend (Shizuku/root).
     *
     * Suitable for the agent to call before deciding which tools to use.
     */
    suspend fun statusReport(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.appendLine("╔══ OmniDev Environment Status ══════════════════════════════════╗")

        // ── Privilege backend ──────────────────────────────────────────────
        val shizukuReady  = PrivilegedExecutionManager.isShizukuReady()
        val rishReady     = PrivilegedExecutionManager.isRishReady()
        val rootAvailable = PrivilegedExecutionManager.isRootAvailable()
        sb.appendLine("║ PRIVILEGE BACKEND")
        sb.appendLine("║   Shizuku : ${if (shizukuReady) "✅ Ready" else "❌ Not ready"}")
        sb.appendLine("║   rish    : ${if (rishReady) "✅ Ready" else "❌ Not ready"}")
        sb.appendLine("║   Root/SU : ${if (rootAvailable) "⚠️ Available" else "❌ Not found"}")
        if (!shizukuReady && !rishReady && !rootAvailable) {
            sb.appendLine("║   ⚠️  No privilege backend! Shell tools will fail.")
            sb.appendLine("║      → Install Shizuku: play.google.com/store/apps/details?id=moe.shizuku.privileged.api")
        }
        sb.appendLine("║")

        // ── Termux ────────────────────────────────────────────────────────
        val termuxBashExists = File(TERMUX_BASH).exists()
        sb.appendLine("║ TERMUX")
        sb.appendLine("║   Installed : ${if (termuxBashExists) "✅ Yes ($TERMUX_BASH)" else "❌ Not found"}")
        if (!termuxBashExists) {
            sb.appendLine("║   → Install from F-Droid: https://f-droid.org/en/packages/com.termux/")
        }
        sb.appendLine("║")

        // ── Interpreters ──────────────────────────────────────────────────
        sb.appendLine("║ INTERPRETERS")
        val runtimes = listOf(
            "python3" to listOf(TERMUX_PYTHON3, TERMUX_PYTHON),
            "node"    to listOf(TERMUX_NODE),
            "git"     to listOf(TERMUX_GIT),
            "curl"    to listOf("$TERMUX_BIN/curl"),
            "wget"    to listOf("$TERMUX_BIN/wget")
        )
        for ((name, termuxPaths) in runtimes) {
            val termuxPath = termuxPaths.firstOrNull { File(it).exists() }
            val systemPath = if (termuxPath == null) {
                PrivilegedExecutionManager.executeCommand("which $name 2>/dev/null")
                    .getOrNull()?.trim()?.takeIf { it.isNotBlank() && it != "(no output)" }
            } else null
            val found = termuxPath ?: systemPath
            if (found != null) {
                val ver = runCatching {
                    val envP = if (found.startsWith(TERMUX_BIN)) buildEnvPrefix() else ""
                    PrivilegedExecutionManager.executeCommand(
                        "${envP}${found} --version 2>&1 | head -1"
                    ).getOrNull()?.trim()?.take(50)
                }.getOrNull() ?: ""
                val source = if (found.startsWith(TERMUX_BIN)) "(Termux)" else "(system)"
                sb.appendLine("║   ✅ $name → $found $source  [$ver]")
            } else {
                sb.appendLine("║   ❌ $name — not found")
                if (termuxBashExists) {
                    val pkgName = when (name) {
                        "python3" -> "python"
                        "node"    -> "nodejs"
                        else      -> name
                    }
                    sb.appendLine("║      → Run: agent_runtime action=termux_pkg_install packages='$pkgName'")
                }
            }
        }

        sb.appendLine("║")
        sb.appendLine("║ QUICK INSTALL (if Termux is installed)")
        sb.appendLine("║   Python  : action=termux_pkg_install packages='python'")
        sb.appendLine("║   Node.js : action=termux_pkg_install packages='nodejs'")
        sb.appendLine("║   Git     : action=termux_pkg_install packages='git'")
        sb.appendLine("║   All     : action=termux_pkg_install packages='python nodejs git curl wget'")
        sb.appendLine("╚════════════════════════════════════════════════════════════════╝")

        ToolExecutionResult(sb.toString().trimEnd())
    }

    // ─────────────────────────────────────────────────────────────────────
    // Tool definitions (for CompositeToolManager)
    // ─────────────────────────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "termux_bridge",
            description = """
Execute commands, scripts, and runtimes inside Termux's full Linux environment.
Automatically injects the correct LD_LIBRARY_PATH, PATH, HOME and other variables
so that Termux-installed binaries (python3, node, gcc, git, etc.) work correctly
even when called from a Shizuku/root shell — no manual Termux interaction required.

Requires Termux (from F-Droid) + Shizuku or root for privilege elevation.

Actions:
• status          — Full environment report: Shizuku/root, Termux, Python, Node, Git.
• exec            — command: run any command inside Termux bash environment.
• script          — script: multi-line bash script to execute in Termux environment.
• python_run      — code: Python source code. Optional: args (space-separated).
• python_file     — file_path: absolute path to .py file. Optional: args.
• pip_install     — packages: space-separated PyPI packages. Optional: upgrade=true.
• pkg_install     — packages: space-separated Termux packages (e.g. 'python nodejs git').
• pkg_update      — Update Termux package repository (apt update equivalent).
• find_binary     — binary: name of binary to locate (checks Termux + system PATH).
""".trimIndent(),
            parameters = listOf(
                ToolParameter("action", "string", "Action: status, exec, script, python_run, python_file, pip_install, pkg_install, pkg_update, find_binary.", required = true),
                ToolParameter("command", "string", "Shell command for action=exec.", required = false),
                ToolParameter("script", "string", "Multi-line bash script for action=script.", required = false),
                ToolParameter("code", "string", "Python source code for action=python_run.", required = false),
                ToolParameter("file_path", "string", "Path to .py file for action=python_file.", required = false),
                ToolParameter("packages", "string", "Space-separated package list for pip_install / pkg_install.", required = false),
                ToolParameter("args", "string", "Extra args for python_run / python_file.", required = false),
                ToolParameter("upgrade", "string", "Set 'true' to upgrade packages (pip_install).", required = false),
                ToolParameter("binary", "string", "Binary name for action=find_binary.", required = false)
            )
        )
    )

    // ─────────────────────────────────────────────────────────────────────
    // Execution dispatcher (called by CompositeToolManager)
    // ─────────────────────────────────────────────────────────────────────

    suspend fun executeTool(args: Map<String, String>): ToolExecutionResult {
        return when (val action = args["action"]?.lowercase()?.trim() ?: return ToolExecutionResult("Missing 'action' argument.", isError = true)) {
            "status"      -> statusReport()

            "exec"        -> {
                val cmd = args["command"] ?: return ToolExecutionResult("Missing 'command' for exec.", isError = true)
                executeInTermux(cmd)
            }

            "script"      -> {
                val script = args["script"] ?: return ToolExecutionResult("Missing 'script' for script.", isError = true)
                executeScriptInTermux(script)
            }

            "python_run"  -> {
                val code = args["code"] ?: return ToolExecutionResult("Missing 'code' for python_run.", isError = true)
                runPython(code, args["args"])
            }

            "python_file" -> {
                val filePath = args["file_path"] ?: return ToolExecutionResult("Missing 'file_path' for python_file.", isError = true)
                runPythonFile(filePath, args["args"])
            }

            "pip_install" -> {
                val pkgs = args["packages"] ?: return ToolExecutionResult("Missing 'packages' for pip_install.", isError = true)
                pipInstall(pkgs, args["upgrade"]?.lowercase() == "true")
            }

            "pkg_install" -> {
                val pkgs = args["packages"] ?: return ToolExecutionResult("Missing 'packages' for pkg_install.", isError = true)
                pkgInstall(pkgs)
            }

            "pkg_update"  -> pkgUpdate()

            "find_binary" -> {
                val binary = args["binary"] ?: return ToolExecutionResult("Missing 'binary' for find_binary.", isError = true)
                val path = findBinary(binary)
                if (path != null) {
                    ToolExecutionResult("✅ Found: $path")
                } else {
                    ToolExecutionResult("❌ '$binary' not found on system PATH or in Termux ($TERMUX_BIN/)", isError = true)
                }
            }

            else -> ToolExecutionResult("Unknown termux_bridge action: '$action'.", isError = true)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────

    /** Locate the best available Python interpreter (Termux first, then system PATH). */
    internal suspend fun findPythonInterpreter(): String? = withContext(Dispatchers.IO) {
        // Termux python3
        if (File(TERMUX_PYTHON3).exists()) return@withContext TERMUX_PYTHON3
        // Termux python (symlink to python3 in modern Termux)
        if (File(TERMUX_PYTHON).exists()) return@withContext TERMUX_PYTHON
        // System python3
        val sysPy3 = PrivilegedExecutionManager.executeCommand("which python3 2>/dev/null")
            .getOrNull()?.trim()?.takeIf { it.isNotBlank() && it != "(no output)" }
        if (sysPy3 != null) return@withContext sysPy3
        // System python
        val sysPy = PrivilegedExecutionManager.executeCommand("which python 2>/dev/null")
            .getOrNull()?.trim()?.takeIf { it.isNotBlank() && it != "(no output)" }
        sysPy
    }

    /** POSIX single-quote escaping. */
    private fun shellQuote(s: String): String = "'${s.replace("'", "'\\''")}'"
}
