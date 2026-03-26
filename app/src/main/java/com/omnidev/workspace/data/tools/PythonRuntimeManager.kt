package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PythonRuntimeManager — an autonomous, self-healing Python execution engine for Android.
 *
 * ### Capabilities
 * - **Auto-detection**: Finds Python across Termux, system PATH, and common Android paths.
 * - **Auto-install**: Installs Python via Termux `pkg install python` without user intervention.
 * - **Virtual environments**: Creates, activates, and manages Python venvs.
 * - **Package management**: pip install/uninstall/upgrade/list with full Termux-env injection.
 * - **Script execution**: Run inline code snippets or .py files with proper env.
 * - **Health check**: Comprehensive runtime health report with version, pip, site-packages.
 * - **Self-healing**: If interpreter is broken (library mismatch), attempts to reinstall.
 *
 * ### Resolution order for interpreter
 * 1. Termux python3 (`/data/data/com.termux/files/usr/bin/python3`)
 * 2. Termux python  (`/data/data/com.termux/files/usr/bin/python`)
 * 3. System python3 (via `which python3`)
 * 4. System python  (via `which python`)
 * 5. `/system/bin/python3`, `/system/bin/python` (some AOSP builds ship Python)
 *
 * ### Tool name
 * Exposed to the AI agent as **`python_runtime`** through [getToolDefinitions].
 *
 * @see TermuxEnvironmentBridge for the Termux env-injection logic.
 * @see AgentRuntimeTool for the higher-level runtime dispatcher.
 */
object PythonRuntimeManager {

    // ─────────────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────────────

    private const val MAX_OUTPUT     = 12_000
    private const val VENV_BASE_DIR  = "/data/local/tmp/omni_venvs"

    /** Extra system paths checked when Termux and `which` both fail. */
    private val SYSTEM_PYTHON_PATHS = listOf(
        "/system/bin/python3",
        "/system/bin/python",
        "/system/xbin/python3",
        "/system/xbin/python",
        "/data/usr/bin/python3",
        "/data/usr/bin/python"
    )

    // ─────────────────────────────────────────────────────────────────────
    // Tool definitions
    // ─────────────────────────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "python_runtime",
            description = """
Autonomous Python runtime manager for Android.  Detects, installs, and manages Python
environments without any manual user steps.  Backed by Shizuku/root + Termux.

Actions and required parameters:
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
RUNTIME DETECTION & SETUP
• health_check      — Full diagnostic: interpreter path, version, pip, site-packages, Termux status.
• find_interpreter  — Locate best available Python interpreter. Returns path or install hint.
• install_python    — Install Python via Termux pkg. Requires Termux + Shizuku/root.
• version           — Print Python version of best available interpreter.

CODE EXECUTION
• run_code     — code: Python source. Optional: args (space-sep strings passed as sys.argv[1:]).
• run_file     — file_path: absolute .py path. Optional: args, venv_name.
• run_module   — module: module name (e.g. "http.server"). Optional: args.

PACKAGE MANAGEMENT
• pip_install       — packages: space-separated PyPI names (e.g. "requests numpy pillow").
                      Optional: upgrade=true, venv_name (install into venv).
• pip_uninstall     — packages: space-separated names to uninstall.
• pip_list          — List installed packages. Optional: venv_name.
• pip_search        — query: search PyPI (uses pip index versions). param: package_name.
• pip_show          — package_name: show details of an installed package.

VIRTUAL ENVIRONMENTS
• venv_create       — venv_name: create a new venv at /data/local/tmp/omni_venvs/<name>.
• venv_list         — List all managed venvs.
• venv_delete       — venv_name: delete a venv.
• venv_run          — venv_name + code: run Python code inside the venv.

UTILITIES
• which_pip         — Find pip binary path.
• self_heal         — Attempt to repair a broken Python installation (re-installs via Termux).
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
""".trimIndent(),
            parameters = listOf(
                ToolParameter("action",       "string", "The action to perform (see description).", required = true),
                ToolParameter("code",         "string", "Python source code for run_code / venv_run.", required = false),
                ToolParameter("file_path",    "string", "Absolute path to a .py file.", required = false),
                ToolParameter("module",       "string", "Python module name for run_module.", required = false),
                ToolParameter("packages",     "string", "Space-separated package names for pip_install / pip_uninstall.", required = false),
                ToolParameter("package_name", "string", "Single package name for pip_show / pip_search.", required = false),
                ToolParameter("venv_name",    "string", "Virtual environment name for venv_* and run_file.", required = false),
                ToolParameter("args",         "string", "Extra arguments (space-separated) for run_code / run_file / run_module.", required = false),
                ToolParameter("upgrade",      "string", "Set 'true' to pip install --upgrade.", required = false),
                ToolParameter("query",        "string", "Search query for pip_search.", required = false)
            )
        )
    )

    // ─────────────────────────────────────────────────────────────────────
    // Main dispatcher
    // ─────────────────────────────────────────────────────────────────────

    suspend fun execute(action: String, args: Map<String, String>): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            when (action.lowercase().trim()) {
                "health_check"     -> healthCheck()
                "find_interpreter" -> findInterpreterTool()
                "install_python"   -> installPython()
                "version"          -> getVersion()

                "run_code" -> {
                    val code = args["code"] ?: return@withContext err("run_code requires 'code'")
                    runCode(code, args["args"], args["venv_name"])
                }

                "run_file" -> {
                    val path = args["file_path"] ?: return@withContext err("run_file requires 'file_path'")
                    runFile(path, args["args"], args["venv_name"])
                }

                "run_module" -> {
                    val mod = args["module"] ?: return@withContext err("run_module requires 'module'")
                    runModule(mod, args["args"])
                }

                "pip_install" -> {
                    val pkgs = args["packages"] ?: return@withContext err("pip_install requires 'packages'")
                    pipInstall(pkgs, args["upgrade"]?.lowercase() == "true", args["venv_name"])
                }

                "pip_uninstall" -> {
                    val pkgs = args["packages"] ?: return@withContext err("pip_uninstall requires 'packages'")
                    pipUninstall(pkgs, args["venv_name"])
                }

                "pip_list"    -> pipList(args["venv_name"])
                "pip_search"  -> pipSearch(args["query"] ?: args["package_name"] ?: return@withContext err("pip_search requires 'query' or 'package_name'"))
                "pip_show"    -> pipShow(args["package_name"] ?: return@withContext err("pip_show requires 'package_name'"))
                "which_pip"   -> whichPip()

                "venv_create" -> venvCreate(args["venv_name"] ?: return@withContext err("venv_create requires 'venv_name'"))
                "venv_list"   -> venvList()
                "venv_delete" -> venvDelete(args["venv_name"] ?: return@withContext err("venv_delete requires 'venv_name'"))
                "venv_run"    -> {
                    val venv = args["venv_name"] ?: return@withContext err("venv_run requires 'venv_name'")
                    val code = args["code"] ?: return@withContext err("venv_run requires 'code'")
                    venvRun(venv, code)
                }

                "self_heal" -> selfHeal()

                else -> err("Unknown python_runtime action: '$action'. See tool description for supported actions.")
            }
        }

    // ─────────────────────────────────────────────────────────────────────
    // RUNTIME DETECTION & SETUP
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Full health check: interpreter path, version, pip, site-packages info.
     */
    private suspend fun healthCheck(): ToolExecutionResult {
        val sb = StringBuilder()
        sb.appendLine("╔══ Python Runtime Health Check ═══════════════════════════════╗")

        // Privilege backend
        val shizuku  = PrivilegedExecutionManager.isShizukuReady()
        val rishReady = PrivilegedExecutionManager.isRishReady()
        val root     = PrivilegedExecutionManager.isRootAvailable()
        sb.appendLine("║ PRIVILEGE BACKEND")
        sb.appendLine("║   Shizuku : ${if (shizuku) "✅" else "❌"} | rish : ${if (rishReady) "✅" else "❌"} | Root : ${if (root) "✅" else "❌"}")
        if (!shizuku && !rishReady && !root) {
            sb.appendLine("║   ⚠️  No backend! Install Shizuku or enable root for Python execution.")
        }
        sb.appendLine("║")

        // Termux
        val termuxInstalled = File(TermuxEnvironmentBridge.TERMUX_BASH).exists()
        sb.appendLine("║ TERMUX : ${if (termuxInstalled) "✅ installed" else "❌ not found (install from F-Droid)"}")
        sb.appendLine("║")

        // Python interpreter
        val pyPath = findBestInterpreter()
        sb.appendLine("║ PYTHON INTERPRETER")
        if (pyPath == null) {
            sb.appendLine("║   ❌ Not found")
            sb.appendLine("║   → Use action=install_python (requires Termux)")
        } else {
            sb.appendLine("║   ✅ Path: $pyPath")
            val envP = if (pyPath.startsWith(TermuxEnvironmentBridge.TERMUX_BIN)) TermuxEnvironmentBridge.buildEnvPrefix() else ""
            // Version
            val version = exec("${envP}${pyPath} --version 2>&1")?.trim() ?: "unknown"
            sb.appendLine("║   Version: $version")

            // Pip
            val pipPath = findPip(pyPath)
            sb.appendLine("║   pip: ${if (pipPath != null) "✅ $pipPath" else "❌ not found (run: $pyPath -m ensurepip --upgrade)"}")

            // Site-packages
            val sitePkgsOut = exec("${envP}${pyPath} -c \"import site; print(site.getsitepackages())\" 2>&1")
                ?.trim()?.take(200)
            sb.appendLine("║   site-packages: ${sitePkgsOut ?: "unknown"}")

            // Quick test
            val testOut = exec("${envP}${pyPath} -c \"print('hello from python')\" 2>&1")
            sb.appendLine("║   Smoke test: ${if (testOut?.contains("hello from python") == true) "✅ PASS" else "❌ FAIL ($testOut)"}")
        }

        sb.appendLine("║")

        // Installed packages sample
        if (pyPath != null) {
            val envP = if (pyPath.startsWith(TermuxEnvironmentBridge.TERMUX_BIN)) TermuxEnvironmentBridge.buildEnvPrefix() else ""
            val pipPath = findPip(pyPath)
            if (pipPath != null) {
                val pkgList = exec("${envP}${pipPath} list --format=columns 2>&1 | head -20")
                if (!pkgList.isNullOrBlank()) {
                    sb.appendLine("║ INSTALLED PACKAGES (sample)")
                    pkgList.lines().take(15).forEach { sb.appendLine("║   $it") }
                    sb.appendLine("║")
                }
            }
        }

        // Quick install hints
        sb.appendLine("║ QUICK ACTIONS")
        sb.appendLine("║   Install Python : action=install_python")
        sb.appendLine("║   Run code       : action=run_code  code='print(\"hello\")'")
        sb.appendLine("║   Install package: action=pip_install  packages='requests numpy'")
        sb.appendLine("║   Create venv    : action=venv_create  venv_name='myenv'")
        sb.appendLine("╚═══════════════════════════════════════════════════════════════╝")
        return ToolExecutionResult(sb.toString().trimEnd())
    }

    /** Returns best interpreter path or an install hint. */
    private suspend fun findInterpreterTool(): ToolExecutionResult {
        val path = findBestInterpreter()
        return if (path != null) {
            ToolExecutionResult("✅ Python interpreter found: $path")
        } else {
            ToolExecutionResult(
                "❌ Python not found on this device.\n\n" +
                "Install options:\n" +
                "  1. Via OmniDev (automatic): action=install_python\n" +
                "  2. Via Termux manually: open Termux → run: pkg install python\n" +
                "  3. Install Termux first (from F-Droid) if not installed.\n\n" +
                "After installing, use action=health_check to verify.",
                isError = true
            )
        }
    }

    /**
     * Install Python via Termux `pkg install python -y`.
     * Fully non-interactive (DEBIAN_FRONTEND=noninteractive).
     */
    private suspend fun installPython(): ToolExecutionResult {
        if (!File(TermuxEnvironmentBridge.TERMUX_BASH).exists()) {
            return ToolExecutionResult(
                "❌ Termux is not installed.\n\n" +
                "Python can only be installed through Termux on non-rooted devices.\n" +
                "Steps:\n" +
                "  1. Install Termux from F-Droid: https://f-droid.org/en/packages/com.termux/\n" +
                "  2. Open Termux and run: pkg update && pkg upgrade -y\n" +
                "  3. Then come back and use action=install_python",
                isError = true
            )
        }

        // Check if already installed
        if (File(TermuxEnvironmentBridge.TERMUX_PYTHON3).exists() ||
            File(TermuxEnvironmentBridge.TERMUX_PYTHON).exists()) {
            val envP = TermuxEnvironmentBridge.buildEnvPrefix()
            val pyBin = if (File(TermuxEnvironmentBridge.TERMUX_PYTHON3).exists())
                TermuxEnvironmentBridge.TERMUX_PYTHON3 else TermuxEnvironmentBridge.TERMUX_PYTHON
            val ver = exec("${envP}${pyBin} --version 2>&1") ?: "unknown"
            return ToolExecutionResult("✅ Python is already installed: $pyBin\nVersion: $ver")
        }

        // Install via Termux pkg
        val envP = TermuxEnvironmentBridge.buildEnvPrefix()
        val result = PrivilegedExecutionManager.executeCommand(
            "${envP}DEBIAN_FRONTEND=noninteractive " +
            "${TermuxEnvironmentBridge.TERMUX_PKG_MANAGER} install -y python 2>&1"
        )

        return if (result.isSuccess) {
            val output = result.getOrDefault("").take(MAX_OUTPUT)
            // Verify installation
            val pythonNowExists = File(TermuxEnvironmentBridge.TERMUX_PYTHON3).exists() ||
                                  File(TermuxEnvironmentBridge.TERMUX_PYTHON).exists()
            if (pythonNowExists) {
                val pyBin = if (File(TermuxEnvironmentBridge.TERMUX_PYTHON3).exists())
                    TermuxEnvironmentBridge.TERMUX_PYTHON3 else TermuxEnvironmentBridge.TERMUX_PYTHON
                val ver = exec("${envP}${pyBin} --version 2>&1") ?: "?"
                ToolExecutionResult("✅ Python installed successfully!\nPath: $pyBin\nVersion: $ver\n\nInstall output:\n$output")
            } else {
                ToolExecutionResult(
                    "⚠️ pkg install completed but Python binary not found yet.\nOutput:\n$output\n\n" +
                    "Try: action=health_check to re-verify.",
                    isError = true
                )
            }
        } else {
            ToolExecutionResult(
                "❌ Failed to install Python via Termux:\n${result.exceptionOrNull()?.message}\n\n" +
                "Manual fix: Open Termux and run: pkg install python",
                isError = true
            )
        }
    }

    /** Print Python version. */
    private suspend fun getVersion(): ToolExecutionResult {
        val pyPath = findBestInterpreter()
            ?: return ToolExecutionResult("Python not found. Use action=install_python.", isError = true)
        val envP = if (pyPath.startsWith(TermuxEnvironmentBridge.TERMUX_BIN)) TermuxEnvironmentBridge.buildEnvPrefix() else ""
        val ver = exec("${envP}${pyPath} --version 2>&1") ?: "(no output)"
        return ToolExecutionResult("Python: $pyPath\nVersion: $ver")
    }

    // ─────────────────────────────────────────────────────────────────────
    // CODE EXECUTION
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Run inline Python code.
     * Uses Termux env injection if the interpreter comes from Termux.
     * [venvName] activates a managed venv before running if provided.
     */
    private suspend fun runCode(
        code: String,
        extraArgs: String?,
        venvName: String? = null
    ): ToolExecutionResult {
        val (pythonBin, envPrefix) = resolveInterpreterAndEnv(venvName)
            ?: return ToolExecutionResult(
                "❌ Python not found.\nInstall via: action=install_python",
                isError = true
            )

        // Build sys.argv extras
        val argStr = extraArgs?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            ?.joinToString(" ") { shellQuote(it) }
            ?.let { " $it" } ?: ""

        val cmd = "${envPrefix}${pythonBin} -c ${shellQuote(code)}$argStr 2>&1"
        return execCaptured(cmd)
    }

    /**
     * Run a Python file at [filePath].
     * Optionally activates a managed venv via [venvName].
     */
    private suspend fun runFile(
        filePath: String,
        extraArgs: String?,
        venvName: String? = null
    ): ToolExecutionResult {
        if (filePath.contains("..")) return err("File path must not contain '..'")
        val (pythonBin, envPrefix) = resolveInterpreterAndEnv(venvName)
            ?: return err("Python not found. Use action=install_python.")

        val argStr = extraArgs?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            ?.joinToString(" ") { shellQuote(it) }
            ?.let { " $it" } ?: ""

        val cmd = "${envPrefix}${pythonBin} ${shellQuote(filePath)}$argStr 2>&1"
        return execCaptured(cmd)
    }

    /** Run a Python module via `python -m <module>`. */
    private suspend fun runModule(module: String, extraArgs: String?): ToolExecutionResult {
        val safeMod = module.replace(Regex("[^a-zA-Z0-9_.]"), "")
        if (safeMod.isEmpty()) return err("Invalid module name.")
        val (pythonBin, envPrefix) = resolveInterpreterAndEnv(null)
            ?: return err("Python not found. Use action=install_python.")
        val argStr = extraArgs?.let { " $it" } ?: ""
        val cmd = "${envPrefix}${pythonBin} -m $safeMod$argStr 2>&1"
        return execCaptured(cmd)
    }

    // ─────────────────────────────────────────────────────────────────────
    // PACKAGE MANAGEMENT
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun pipInstall(
        packages: String,
        upgrade: Boolean,
        venvName: String? = null
    ): ToolExecutionResult {
        val safePkgs = sanitizePackageList(packages)
            ?: return err("No valid package names provided.")
        val (pyBin, envPrefix) = resolveInterpreterAndEnv(venvName)
            ?: return err("Python not found. Use action=install_python.")
        val upgradeFlag = if (upgrade) " --upgrade" else ""

        // Prefer dedicated pip binary, fall back to python -m pip
        val pipBin = findPip(pyBin)
        val pipCmd = if (pipBin != null) {
            "${envPrefix}${pipBin} install$upgradeFlag $safePkgs 2>&1"
        } else {
            "${envPrefix}${pyBin} -m pip install$upgradeFlag $safePkgs 2>&1"
        }
        return execCaptured(pipCmd)
    }

    private suspend fun pipUninstall(packages: String, venvName: String? = null): ToolExecutionResult {
        val safePkgs = sanitizePackageList(packages)
            ?: return err("No valid package names provided.")
        val (pyBin, envPrefix) = resolveInterpreterAndEnv(venvName)
            ?: return err("Python not found. Use action=install_python.")
        val pipBin = findPip(pyBin)
        val cmd = if (pipBin != null)
            "${envPrefix}${pipBin} uninstall -y $safePkgs 2>&1"
        else
            "${envPrefix}${pyBin} -m pip uninstall -y $safePkgs 2>&1"
        return execCaptured(cmd)
    }

    private suspend fun pipList(venvName: String? = null): ToolExecutionResult {
        val (pyBin, envPrefix) = resolveInterpreterAndEnv(venvName)
            ?: return err("Python not found.")
        val pipBin = findPip(pyBin)
        val cmd = if (pipBin != null)
            "${envPrefix}${pipBin} list --format=columns 2>&1"
        else
            "${envPrefix}${pyBin} -m pip list --format=columns 2>&1"
        return execCaptured(cmd)
    }

    private suspend fun pipSearch(query: String): ToolExecutionResult {
        // pip search was removed from PyPI; use pip index versions as a proxy
        val safePkg = query.replace(Regex("[^a-zA-Z0-9_.\\-]"), "")
        if (safePkg.isEmpty()) return err("Invalid query.")
        val (pyBin, envPrefix) = resolveInterpreterAndEnv(null)
            ?: return err("Python not found.")
        val pipBin = findPip(pyBin)
        val cmd = if (pipBin != null)
            "${envPrefix}${pipBin} index versions $safePkg 2>&1 | head -5"
        else
            "${envPrefix}${pyBin} -m pip index versions $safePkg 2>&1 | head -5"
        return execCaptured(cmd)
    }

    private suspend fun pipShow(packageName: String): ToolExecutionResult {
        val safePkg = packageName.replace(Regex("[^a-zA-Z0-9_.\\-]"), "")
        if (safePkg.isEmpty()) return err("Invalid package name.")
        val (pyBin, envPrefix) = resolveInterpreterAndEnv(null)
            ?: return err("Python not found.")
        val pipBin = findPip(pyBin)
        val cmd = if (pipBin != null)
            "${envPrefix}${pipBin} show $safePkg 2>&1"
        else
            "${envPrefix}${pyBin} -m pip show $safePkg 2>&1"
        return execCaptured(cmd)
    }

    private suspend fun whichPip(): ToolExecutionResult {
        val pyPath = findBestInterpreter() ?: return err("Python not found.")
        val pipPath = findPip(pyPath)
        return if (pipPath != null) {
            ToolExecutionResult("✅ pip found: $pipPath")
        } else {
            ToolExecutionResult(
                "❌ pip not found.\nFix: action=run_code  code='import ensurepip; ensurepip.bootstrap()'",
                isError = true
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // VIRTUAL ENVIRONMENTS
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun venvCreate(venvName: String): ToolExecutionResult {
        val safeName = venvName.replace(Regex("[^a-zA-Z0-9_\\-]"), "")
        if (safeName.isEmpty()) return err("Invalid venv name (use letters, numbers, _ or -).")
        val venvPath = "$VENV_BASE_DIR/$safeName"
        val (pyBin, envPrefix) = resolveInterpreterAndEnv(null)
            ?: return err("Python not found. Use action=install_python first.")

        // Create base dir if needed
        PrivilegedExecutionManager.executeCommand("mkdir -p $VENV_BASE_DIR 2>&1")

        val cmd = "${envPrefix}${pyBin} -m venv ${shellQuote(venvPath)} 2>&1"
        val result = PrivilegedExecutionManager.executeCommand(cmd)

        return if (result.isSuccess && File("$venvPath/bin/python").exists() ||
                   File("$venvPath/bin/python3").exists()) {
            ToolExecutionResult(
                "✅ Virtual environment created: $venvPath\n" +
                "Use action=venv_run venv_name=$safeName code='...' to run code inside it.\n" +
                "Use action=pip_install packages='...' venv_name=$safeName to install packages."
            )
        } else {
            val out = result.getOrDefault("").take(2000)
            ToolExecutionResult("❌ Failed to create venv at $venvPath\n$out", isError = true)
        }
    }

    private suspend fun venvList(): ToolExecutionResult {
        val out = exec("ls $VENV_BASE_DIR 2>/dev/null || echo '(none)'") ?: "(none)"
        return ToolExecutionResult("Managed virtual environments in $VENV_BASE_DIR:\n$out")
    }

    private suspend fun venvDelete(venvName: String): ToolExecutionResult {
        val safeName = venvName.replace(Regex("[^a-zA-Z0-9_\\-]"), "")
        if (safeName.isEmpty()) return err("Invalid venv name.")
        val venvPath = "$VENV_BASE_DIR/$safeName"
        if (venvPath.contains("..")) return err("Invalid path.")
        val result = PrivilegedExecutionManager.executeCommand("rm -rf ${shellQuote(venvPath)} && echo DELETED")
        return if (result.getOrDefault("").contains("DELETED")) {
            ToolExecutionResult("✅ Deleted venv: $venvPath")
        } else {
            ToolExecutionResult("❌ Failed to delete $venvPath: ${result.exceptionOrNull()?.message}", isError = true)
        }
    }

    private suspend fun venvRun(venvName: String, code: String): ToolExecutionResult {
        val safeName = venvName.replace(Regex("[^a-zA-Z0-9_\\-]"), "")
        if (safeName.isEmpty()) return err("Invalid venv name.")
        val venvPath = "$VENV_BASE_DIR/$safeName"
        // Try both python3 and python inside venv
        val pyBin = when {
            File("$venvPath/bin/python3").exists() -> "$venvPath/bin/python3"
            File("$venvPath/bin/python").exists()  -> "$venvPath/bin/python"
            else -> return err("Venv '$safeName' not found at $venvPath. Create it first with action=venv_create.")
        }
        // For Termux-based venvs, inject env
        val envPrefix = if (TermuxEnvironmentBridge.isTermuxUsable()) TermuxEnvironmentBridge.buildEnvPrefix() else ""
        val cmd = "${envPrefix}${pyBin} -c ${shellQuote(code)} 2>&1"
        return execCaptured(cmd)
    }

    // ─────────────────────────────────────────────────────────────────────
    // SELF-HEAL
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Attempt to repair a broken Python installation.
     * Strategy:
     * 1. Run a quick smoke test.
     * 2. If broken (library not found / segfault / bad exit), reinstall via `pkg install --reinstall python`.
     * 3. Re-verify.
     */
    private suspend fun selfHeal(): ToolExecutionResult {
        val sb = StringBuilder("=== Python Self-Heal ===\n")

        val pyPath = findBestInterpreter()
        if (pyPath == null) {
            sb.appendLine("Python interpreter not found. Attempting fresh install...")
            val installResult = installPython()
            sb.append(installResult.output)
            return ToolExecutionResult(sb.toString(), isError = installResult.isError)
        }

        sb.appendLine("Interpreter found: $pyPath")
        val envP = if (pyPath.startsWith(TermuxEnvironmentBridge.TERMUX_BIN)) TermuxEnvironmentBridge.buildEnvPrefix() else ""
        val smokeOutput = exec("${envP}${pyPath} -c \"print('smoke_ok')\" 2>&1")

        if (smokeOutput?.contains("smoke_ok") == true) {
            sb.appendLine("✅ Python is working correctly. No repair needed.")
            sb.appendLine("Version: ${exec("${envP}${pyPath} --version 2>&1") ?: "?"}")
            return ToolExecutionResult(sb.toString())
        }

        sb.appendLine("⚠️ Smoke test failed: $smokeOutput")
        sb.appendLine("Attempting reinstall via Termux...")

        if (!File(TermuxEnvironmentBridge.TERMUX_BASH).exists()) {
            sb.appendLine("❌ Cannot reinstall — Termux is not available.")
            return ToolExecutionResult(sb.toString(), isError = true)
        }

        val envPrefix = TermuxEnvironmentBridge.buildEnvPrefix()
        val reinstallResult = PrivilegedExecutionManager.executeCommand(
            "${envPrefix}DEBIAN_FRONTEND=noninteractive " +
            "${TermuxEnvironmentBridge.TERMUX_PKG_MANAGER} install --reinstall -y python 2>&1"
        )

        sb.appendLine("Reinstall output:\n${reinstallResult.getOrDefault("").take(2000)}")

        // Re-verify
        val newSmoke = exec("${envP}${pyPath} -c \"print('smoke_ok')\" 2>&1")
        return if (newSmoke?.contains("smoke_ok") == true) {
            sb.appendLine("✅ Python repaired successfully!")
            ToolExecutionResult(sb.toString())
        } else {
            sb.appendLine("❌ Repair failed. Manual intervention may be needed.")
            sb.appendLine("Try opening Termux and running: pkg install --reinstall python")
            ToolExecutionResult(sb.toString(), isError = true)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Find the best available Python interpreter.
     * Resolution order: Termux python3 → Termux python → system python3 → system python → AOSP paths.
     */
    internal suspend fun findBestInterpreter(): String? = withContext(Dispatchers.IO) {
        // 1. Termux python3
        if (File(TermuxEnvironmentBridge.TERMUX_PYTHON3).exists()) return@withContext TermuxEnvironmentBridge.TERMUX_PYTHON3
        // 2. Termux python
        if (File(TermuxEnvironmentBridge.TERMUX_PYTHON).exists()) return@withContext TermuxEnvironmentBridge.TERMUX_PYTHON
        // 3. System `which` (PATH-based lookup)
        val sysPy3 = exec("which python3 2>/dev/null")?.trim()
            ?.takeIf { it.isNotBlank() && it != "(no output)" && !it.startsWith("ERROR") }
        if (sysPy3 != null) return@withContext sysPy3
        val sysPy = exec("which python 2>/dev/null")?.trim()
            ?.takeIf { it.isNotBlank() && it != "(no output)" && !it.startsWith("ERROR") }
        if (sysPy != null) return@withContext sysPy
        // 4. Known AOSP/OEM paths
        SYSTEM_PYTHON_PATHS.firstOrNull { File(it).exists() }
    }

    /**
     * Resolve the interpreter to use and build the env prefix.
     * If [venvName] is given, uses that venv's python (must already exist).
     */
    private suspend fun resolveInterpreterAndEnv(venvName: String?): Pair<String, String>? {
        if (!venvName.isNullOrBlank()) {
            val safeName = venvName.replace(Regex("[^a-zA-Z0-9_\\-]"), "")
            val venvPath = "$VENV_BASE_DIR/$safeName"
            val venvPy = when {
                File("$venvPath/bin/python3").exists() -> "$venvPath/bin/python3"
                File("$venvPath/bin/python").exists()  -> "$venvPath/bin/python"
                else -> null
            }
            if (venvPy != null) {
                // Always inject Termux env for venvs (they may depend on Termux's libc)
                val envP = if (TermuxEnvironmentBridge.isTermuxUsable()) TermuxEnvironmentBridge.buildEnvPrefix() else ""
                return Pair(venvPy, envP)
            }
        }
        val pyPath = findBestInterpreter() ?: return null
        val envP = if (pyPath.startsWith(TermuxEnvironmentBridge.TERMUX_BIN)) TermuxEnvironmentBridge.buildEnvPrefix() else ""
        return Pair(pyPath, envP)
    }

    /**
     * Find pip for the given Python interpreter.
     * Tries: pip3 / pip in same bin dir, then `python -m pip` existence check.
     */
    private suspend fun findPip(pythonBin: String): String? {
        val binDir = File(pythonBin).parent ?: return null
        val pip3 = File(binDir, "pip3")
        if (pip3.exists()) return pip3.absolutePath
        val pip = File(binDir, "pip")
        if (pip.exists()) return pip.absolutePath

        // Try system pip3 / pip via which
        val envP = if (pythonBin.startsWith(TermuxEnvironmentBridge.TERMUX_BIN)) TermuxEnvironmentBridge.buildEnvPrefix() else ""
        val sysPip3 = exec("${envP}which pip3 2>/dev/null")?.trim()
            ?.takeIf { it.isNotBlank() && it != "(no output)" && !it.startsWith("ERROR") }
        if (sysPip3 != null) return sysPip3
        val sysPip = exec("${envP}which pip 2>/dev/null")?.trim()
            ?.takeIf { it.isNotBlank() && it != "(no output)" && !it.startsWith("ERROR") }
        return sysPip
    }

    /**
     * Execute [cmd] and capture output, returning a [ToolExecutionResult].
     * Unlike [exec], this propagates errors as an error result rather than null.
     *
     * IMPORTANT: This version does NOT fail on non-zero exit codes from python itself.
     * Python scripts that print output and exit non-zero (e.g. sys.exit(1)) should
     * still return their stdout as a success result.
     */
    private suspend fun execCaptured(cmd: String): ToolExecutionResult {
        val result = PrivilegedExecutionManager.executeCommand(cmd)
        return result.fold(
            onSuccess = { output ->
                ToolExecutionResult(output.take(MAX_OUTPUT).ifBlank { "(no output)" })
            },
            onFailure = { ex ->
                // Check if this is a "non-zero exit but with output" case
                // Many Python scripts exit with code != 0 but still produce useful stdout
                val msg = ex.message ?: ""
                val stdoutMatch = Regex("stdout:\\s*(.*?)(?:\\nstderr:|$)", RegexOption.DOT_MATCHES_ALL)
                    .find(msg)?.groupValues?.getOrNull(1)?.trim()
                if (!stdoutMatch.isNullOrBlank()) {
                    // There IS stdout output — return it (Python ran but exited non-zero)
                    ToolExecutionResult(stdoutMatch.take(MAX_OUTPUT))
                } else {
                    ToolExecutionResult("❌ ${msg.take(2000)}", isError = true)
                }
            }
        )
    }

    /**
     * Execute [cmd] via [PrivilegedExecutionManager] and return stdout as a nullable String.
     * Returns null on failure.
     */
    private suspend fun exec(cmd: String): String? =
        PrivilegedExecutionManager.executeCommand(cmd).getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "(no output)" }

    /** Sanitise a space-separated package list to safe PyPI name chars. */
    private fun sanitizePackageList(packages: String): String? {
        val safe = packages.split(Regex("\\s+"))
            .map { it.replace(Regex("[^a-zA-Z0-9_.\\-\\[\\]=~<>!]"), "") }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return safe.ifBlank { null }
    }

    /** POSIX single-quote escaping. */
    private fun shellQuote(s: String): String = "'${s.replace("'", "'\\''")}'"

    private fun err(msg: String) = ToolExecutionResult(msg, isError = true)
}
