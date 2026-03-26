package com.omnidev.workspace.data.tools

import android.content.Context
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.MalformedURLException
import java.net.URL

/**
 * AgentRuntimeTool — gives the AI agent the ability to autonomously discover,
 * download, install, and execute tools and scripts on the device.
 *
 * Backed by [PrivilegedExecutionManager] (Shizuku → rish → root/SU) for
 * system-level privilege, and [TermuxEnvironmentBridge] for running commands
 * inside Termux's full Linux userspace (proper LD_LIBRARY_PATH, PATH, libc, etc.).
 *
 * ### Supported actions (`agent_runtime` tool)
 * | Action               | Description                                                         |
 * |----------------------|---------------------------------------------------------------------|
 * | `env_check`          | Full survey: Python, Node, npm, pip, curl, wget, git, busybox, Termux, Shizuku |
 * | `tool_which`         | Locate a binary on PATH or in Termux prefix                         |
 * | `python_run`         | Execute inline Python code via best available interpreter           |
 * | `node_run`           | Execute inline JavaScript via node                                  |
 * | `shell_script`       | Write and execute a multi-line shell script                         |
 * | `pip_install`        | Install Python packages (Termux pip first, system pip fallback)     |
 * | `pip_list`           | List installed Python packages                                      |
 * | `pip_run`            | Run a Python one-liner                                              |
 * | `npm_install`        | Install npm packages globally                                       |
 * | `download_file`      | Download a file via curl/wget                                       |
 * | `download_exec`      | Download a script from URL and execute it                           |
 * | `termux_check`       | Check Termux installation & available packages                      |
 * | `termux_run`         | Execute a command with full Termux environment (LD_LIBRARY_PATH etc)|
 * | `termux_pkg_install` | Install Termux packages via `pkg install`                           |
 * | `busybox_run`        | Execute a command via busybox                                       |
 * | `tool_bootstrap`     | Bootstrap missing essential tools (via Termux if available)         |
 */
object AgentRuntimeTool {

    // Aliases from TermuxEnvironmentBridge for backward compat
    private val TERMUX_PKG  = TermuxEnvironmentBridge.TERMUX_PKG
    private val TERMUX_BIN  = TermuxEnvironmentBridge.TERMUX_BIN
    private val TERMUX_BASH = TermuxEnvironmentBridge.TERMUX_BASH

    /** Whitelist of allowed shell interpreters for shell_script action. */
    private val ALLOWED_INTERPRETERS = setOf("sh", "bash", "dash", "ash", "python3", "python", "node", "perl", "ruby")

    /** POSIX single-quote escaping: wraps [s] in single quotes and escapes embedded single quotes. */
    private fun shellQuote(s: String): String = "'${s.replace("'", "'\\''")}'"

    // ─────────────────────────────────────────────────────────────────────
    // Tool definitions
    // ─────────────────────────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "agent_runtime",
            description = """
Execute advanced runtime operations: detect installed tools, run Python/Node/shell code inline,
download and execute scripts, install packages (pip/npm/Termux pkg), and use Termux environment.
All execution is backed by Shizuku (preferred) or root/SU fallback — giving ADB/root-level access.

Actions and required parameters:
• env_check           — Survey available runtimes (python3, node, npm, pip, curl, wget, git, termux, busybox). No extra params.
• tool_which          — tool: binary name to locate (e.g. "python3", "curl"). Returns full path.
• python_run          — code: Python source code to execute inline (runs via python3). Optional: args (space-sep).
• node_run            — code: JavaScript source code to execute inline (runs via node).
• shell_script        — script: multi-line shell script content. Optional: interpreter (default: sh).
• pip_install         — packages: space-separated package names (e.g. "requests numpy"). Optional: upgrade=true.
• pip_list            — No extra params. Lists installed Python packages.
• pip_run             — code: Python one-liner (runs via python3 -c "code").
• npm_install         — packages: space-separated npm package names. Optional: global=true (default).
• download_file       — url: URL to download. Optional: dest_path (default: /data/local/tmp/<filename>).
• download_exec       — url: URL of script to download and execute. Optional: args for the script.
• termux_check        — Check if Termux is installed and what packages it has. No extra params.
• termux_run          — command: command to run in Termux's shell (/data/data/com.termux/...). Requires Shizuku or root.
• termux_pkg_install  — packages: space-separated Termux package names (e.g. "python nodejs git").
• busybox_run         — command: command to run via busybox (e.g. "busybox wget -O /tmp/f https://...").
• tool_bootstrap      — Bootstrap essential tools (busybox, curl, Python) if not found. No extra params.
""".trimIndent(),
            parameters = listOf(
                ToolParameter("action", "string", "The action to perform (see description).", required = true),
                ToolParameter("code", "string", "Python or JavaScript source code for python_run / node_run / pip_run.", required = false),
                ToolParameter("script", "string", "Multi-line shell script content for shell_script.", required = false),
                ToolParameter("command", "string", "Shell command for termux_run / busybox_run.", required = false),
                ToolParameter("packages", "string", "Space-separated package list for pip_install / npm_install / termux_pkg_install.", required = false),
                ToolParameter("tool", "string", "Binary name for tool_which.", required = false),
                ToolParameter("url", "string", "URL for download_file / download_exec.", required = false),
                ToolParameter("dest_path", "string", "Destination path for download_file.", required = false),
                ToolParameter("args", "string", "Additional arguments for download_exec or python_run.", required = false),
                ToolParameter("interpreter", "string", "Shell interpreter for shell_script (default: sh).", required = false),
                ToolParameter("upgrade", "string", "If 'true', pip install with --upgrade flag.", required = false),
                ToolParameter("global", "string", "If 'true' (default), npm install with -g flag.", required = false)
            )
        )
    )

    // ─────────────────────────────────────────────────────────────────────
    // Execution router
    // ─────────────────────────────────────────────────────────────────────

    suspend fun execute(
        context: Context,
        action: String,
        args: Map<String, String>
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        when (action.lowercase().trim()) {

            "env_check" -> envCheck()

            "tool_which" -> {
                val tool = args["tool"] ?: return@withContext err("tool_which requires 'tool'")
                toolWhich(tool)
            }

            "python_run" -> {
                val code = args["code"] ?: return@withContext err("python_run requires 'code'")
                pythonRun(code, args["args"])
            }

            "node_run" -> {
                val code = args["code"] ?: return@withContext err("node_run requires 'code'")
                nodeRun(code)
            }

            "shell_script" -> {
                val script = args["script"] ?: return@withContext err("shell_script requires 'script'")
                shellScript(script, args["interpreter"] ?: "sh")
            }

            "pip_install" -> {
                val pkgs = args["packages"] ?: return@withContext err("pip_install requires 'packages'")
                pipInstall(pkgs, args["upgrade"]?.lowercase() == "true")
            }

            "pip_list" -> pipList()

            "pip_run" -> {
                val code = args["code"] ?: return@withContext err("pip_run requires 'code'")
                PrivilegedExecutionManager.executeCommand(
                    "python3 -c ${shellQuote(code)} 2>&1" +
                    " || $TERMUX_BIN/python3 -c ${shellQuote(code)} 2>&1" +
                    " || $TERMUX_BIN/python -c ${shellQuote(code)} 2>&1"
                ).toToolResult()
            }

            "npm_install" -> {
                val pkgs = args["packages"] ?: return@withContext err("npm_install requires 'packages'")
                val isGlobal = args["global"]?.lowercase() != "false"
                npmInstall(pkgs, isGlobal)
            }

            "download_file" -> {
                val url = args["url"] ?: return@withContext err("download_file requires 'url'")
                val dest = args["dest_path"]
                downloadFile(url, dest)
            }

            "download_exec" -> {
                val url = args["url"] ?: return@withContext err("download_exec requires 'url'")
                downloadExec(url, args["args"] ?: "")
            }

            "termux_check" -> termuxCheck()

            "termux_run" -> {
                val cmd = args["command"] ?: return@withContext err("termux_run requires 'command'")
                termuxRun(cmd)
            }

            "termux_pkg_install" -> {
                val pkgs = args["packages"] ?: return@withContext err("termux_pkg_install requires 'packages'")
                termuxPkgInstall(pkgs)
            }

            "busybox_run" -> {
                val cmd = args["command"] ?: return@withContext err("busybox_run requires 'command'")
                // Run the command through busybox sh to allow full sh syntax
                PrivilegedExecutionManager.executeCommand("busybox sh -c ${shellQuote(cmd)}").toToolResult()
            }

            "tool_bootstrap" -> toolBootstrap()

            else -> err("Unknown agent_runtime action: '$action'. See tool description for supported actions.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Action implementations
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun envCheck(): ToolExecutionResult {
        // Delegate to TermuxEnvironmentBridge for a comprehensive, well-formatted status report
        return TermuxEnvironmentBridge.statusReport()
    }

    private suspend fun toolWhich(tool: String): ToolExecutionResult {
        val safeTool = tool.replace(Regex("[^a-zA-Z0-9_.\\-]"), "")
        if (safeTool.isEmpty()) return err("Invalid tool name.")
        val path = TermuxEnvironmentBridge.findBinary(safeTool)
        return if (path != null) {
            ToolExecutionResult("✅ $safeTool → $path")
        } else {
            ToolExecutionResult("❌ '$safeTool' not found on system PATH or in Termux ($TERMUX_BIN/)", isError = true)
        }
    }

    private suspend fun pythonRun(code: String, extraArgs: String?): ToolExecutionResult {
        // Delegate to TermuxEnvironmentBridge which handles Termux env injection automatically
        return TermuxEnvironmentBridge.runPython(code, extraArgs)
    }

    private suspend fun nodeRun(code: String): ToolExecutionResult {
        // Try Termux node first (with proper env), then system node
        val termuxNode = TermuxEnvironmentBridge.TERMUX_NODE
        val isTermuxNode = File(termuxNode).exists()
        val envPrefix = if (isTermuxNode) TermuxEnvironmentBridge.buildEnvPrefix() else ""
        val nodeBin = if (isTermuxNode) termuxNode else "node"
        return PrivilegedExecutionManager.executeCommand(
            "${envPrefix}${nodeBin} -e ${shellQuote(code)} 2>&1"
        ).toToolResult()
    }

    private suspend fun shellScript(scriptContent: String, interpreter: String): ToolExecutionResult {
        val safeInterpreter = interpreter.lowercase().trim()
            .let { ALLOWED_INTERPRETERS.firstOrNull { allowed -> allowed == it } ?: "sh" }

        // For bash/python/node, use Termux env so libs resolve correctly
        val isTermuxInterp = when (safeInterpreter) {
            "bash"    -> File(TermuxEnvironmentBridge.TERMUX_BASH).exists()
            "python3", "python" -> File(TermuxEnvironmentBridge.TERMUX_PYTHON3).exists() ||
                                   File(TermuxEnvironmentBridge.TERMUX_PYTHON).exists()
            "node"    -> File(TermuxEnvironmentBridge.TERMUX_NODE).exists()
            else      -> false
        }
        val envPrefix = if (isTermuxInterp) TermuxEnvironmentBridge.buildEnvPrefix() else ""

        val resolvedInterp = when {
            isTermuxInterp && safeInterpreter == "bash"    -> TermuxEnvironmentBridge.TERMUX_BASH
            isTermuxInterp && safeInterpreter == "python3" -> TermuxEnvironmentBridge.TERMUX_PYTHON3
            isTermuxInterp && safeInterpreter == "python"  -> TermuxEnvironmentBridge.TERMUX_PYTHON
            isTermuxInterp && safeInterpreter == "node"    -> TermuxEnvironmentBridge.TERMUX_NODE
            else -> safeInterpreter
        }

        val flag = if (safeInterpreter == "node") "-e" else "-c"
        return PrivilegedExecutionManager.executeCommand(
            "${envPrefix}${resolvedInterp} $flag ${shellQuote(scriptContent)} 2>&1"
        ).toToolResult()
    }

    private suspend fun pipInstall(packages: String, upgrade: Boolean): ToolExecutionResult {
        // Delegate to TermuxEnvironmentBridge which handles Termux env + cascading fallback
        return TermuxEnvironmentBridge.pipInstall(packages, upgrade)
    }

    private suspend fun pipList(): ToolExecutionResult {
        val envPrefix = if (TermuxEnvironmentBridge.isTermuxUsable()) TermuxEnvironmentBridge.buildEnvPrefix() else ""
        val cmd = buildString {
            if (TermuxEnvironmentBridge.isTermuxUsable()) {
                append("${envPrefix}${TermuxEnvironmentBridge.TERMUX_PIP3} list 2>&1")
                append(" || ${envPrefix}${TermuxEnvironmentBridge.TERMUX_PIP} list 2>&1")
                append(" || ${envPrefix}${TermuxEnvironmentBridge.TERMUX_PYTHON3} -m pip list 2>&1")
                append(" || ")
            }
            append("pip3 list 2>&1 || pip list 2>&1 || python3 -m pip list 2>&1")
        }
        return PrivilegedExecutionManager.executeCommand(cmd).toToolResult()
    }

    private suspend fun npmInstall(packages: String, global: Boolean): ToolExecutionResult {
        val safePkgs = packages.split(Regex("\\s+"))
            .map { it.replace(Regex("[^a-zA-Z0-9_.\\-@/]"), "") }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (safePkgs.isEmpty()) return err("No valid package names provided.")
        val globalFlag = if (global) " -g" else ""
        val termuxNode = TermuxEnvironmentBridge.TERMUX_NODE
        val termuxNpm  = TermuxEnvironmentBridge.TERMUX_NPM
        val isTermux   = File(termuxNpm).exists()
        val envPrefix  = if (isTermux) TermuxEnvironmentBridge.buildEnvPrefix() else ""
        return if (isTermux) {
            PrivilegedExecutionManager.executeCommand(
                "${envPrefix}${termuxNpm} install$globalFlag $safePkgs 2>&1"
            ).toToolResult()
        } else {
            PrivilegedExecutionManager.executeCommand(
                "npm install$globalFlag $safePkgs 2>&1"
            ).toToolResult()
        }
    }

    private suspend fun downloadFile(url: String, destPath: String?): ToolExecutionResult {
        if (url.isBlank()) return err("URL is blank.")
        // Validate URL structure before using it in a shell command
        val validatedUrl = validateUrl(url) ?: return err("Invalid URL: '$url'")
        val filename = validatedUrl.substringAfterLast("/").replace(Regex("[^a-zA-Z0-9_.\\-]"), "")
            .ifBlank { "download_${System.currentTimeMillis()}" }
        val dest = destPath?.replace(Regex("[^a-zA-Z0-9_.\\-/]"), "")
            ?: "/data/local/tmp/$filename"
        if (dest.contains("..")) return err("Destination path must not contain '..'")

        val cmd = "curl -fsSL -o ${shellQuote(dest)} ${shellQuote(validatedUrl)} 2>&1" +
                  " || wget -q -O ${shellQuote(dest)} ${shellQuote(validatedUrl)} 2>&1"
        return PrivilegedExecutionManager.executeCommand(cmd).map { "Downloaded to: $dest" }.toToolResult()
    }

    private suspend fun downloadExec(url: String, extraArgs: String): ToolExecutionResult {
        if (url.isBlank()) return err("URL is blank.")
        val validatedUrl = validateUrl(url) ?: return err("Invalid URL: '$url'")
        // Use /data/local/tmp/ (writable by the `shell` user) instead of app-private cacheDir.
        // The entire download + execute is performed as a single privileged command so the
        // Shizuku/root process owns the temp file and can both write and execute it.
        val tmpPath = "/data/local/tmp/agent_dl_${System.currentTimeMillis()}.sh"
        val argPart = if (extraArgs.isBlank()) "" else " ${shellQuote(extraArgs)}"
        return PrivilegedExecutionManager.executeCommand(
            "(curl -fsSL -o $tmpPath ${shellQuote(validatedUrl)} 2>&1" +
            " || wget -q -O $tmpPath ${shellQuote(validatedUrl)} 2>&1)" +
            " && chmod +x $tmpPath && sh $tmpPath$argPart 2>&1; rm -f $tmpPath"
        ).toToolResult()
    }

    private suspend fun termuxCheck(): ToolExecutionResult {
        val sb = StringBuilder()
        val bashExists = File(TERMUX_BASH).exists()

        if (!bashExists) {
            sb.appendLine("❌ Termux is not installed or its bash is missing.")
            sb.appendLine("Install from F-Droid: https://f-droid.org/en/packages/com.termux/")
            sb.appendLine()
            sb.appendLine("After installing Termux:")
            sb.appendLine("  1. Open Termux and run: pkg update && pkg upgrade -y")
            sb.appendLine("  2. Grant Shizuku permission to this app")
            sb.appendLine("  3. Use action=termux_pkg_install packages='python nodejs git'")
            return ToolExecutionResult(sb.toString().trim())
        }
        sb.appendLine("✅ Termux is installed")
        sb.appendLine("Termux bash: ✅ $TERMUX_BASH")

        // List key installed packages via dpkg
        val isPrivileged = PrivilegedExecutionManager.isShizukuReady() ||
                           PrivilegedExecutionManager.isRootAvailable()
        if (isPrivileged) {
            val envPrefix = TermuxEnvironmentBridge.buildEnvPrefix()
            val pkgList = PrivilegedExecutionManager.executeCommand(
                "${envPrefix}${TermuxEnvironmentBridge.TERMUX_BIN}/dpkg --list 2>/dev/null | " +
                "grep '^ii' | awk '{print \$2\" \"\$3}' | head -30 2>/dev/null " +
                "|| ls $TERMUX_BIN/ | head -40 2>/dev/null"
            ).getOrNull()
            if (!pkgList.isNullOrBlank() && pkgList != "(no output)") {
                sb.appendLine("\nInstalled Termux packages (sample):")
                sb.append(pkgList.take(2000))
            }
        }
        return ToolExecutionResult(sb.toString().trimEnd())
    }

    private suspend fun termuxRun(command: String): ToolExecutionResult {
        // Delegate to TermuxEnvironmentBridge which injects all required env vars
        return TermuxEnvironmentBridge.executeInTermux(command)
    }

    private suspend fun termuxPkgInstall(packages: String): ToolExecutionResult {
        // Delegate to TermuxEnvironmentBridge which handles DEBIAN_FRONTEND + env
        return TermuxEnvironmentBridge.pkgInstall(packages)
    }

    private suspend fun toolBootstrap(): ToolExecutionResult {
        val sb = StringBuilder("=== Tool Bootstrap ===\n")

        suspend fun checkTool(name: String): String? =
            PrivilegedExecutionManager.executeCommand(
                "which $name 2>/dev/null || (test -f $TERMUX_BIN/$name && echo $TERMUX_BIN/$name)"
            ).getOrNull().let(::normalizeExecOutput)

        val curlPath   = checkTool("curl")
        val wgetPath   = checkTool("wget")
        val busyboxPath= checkTool("busybox")
        val pythonPath = checkTool("python3") ?: checkTool("python")
        val nodePath   = checkTool("node")

        sb.appendLine("curl:    ${if (curlPath != null) "✅ $curlPath" else "❌ not found"}")
        sb.appendLine("wget:    ${if (wgetPath != null) "✅ $wgetPath" else "❌ not found"}")
        sb.appendLine("busybox: ${if (busyboxPath != null) "✅ $busyboxPath" else "❌ not found"}")
        sb.appendLine("python3: ${if (pythonPath != null) "✅ $pythonPath" else "❌ not found"}")
        sb.appendLine("node:    ${if (nodePath != null) "✅ $nodePath" else "❌ not found"}")

        val termuxInstalled = PrivilegedExecutionManager.executeCommand(
            "pm list packages | grep com.termux"
        ).getOrNull()?.contains("com.termux") == true
        sb.appendLine("Termux:  ${if (termuxInstalled) "✅ installed" else "❌ not installed"}")

        sb.appendLine()
        if (!termuxInstalled) {
            sb.appendLine("⚠️  Termux is not installed. Most tools require it on stock Android.")
            sb.appendLine("   Install Termux from F-Droid: https://f-droid.org/en/packages/com.termux/")
            sb.appendLine("   (Do NOT use the Play Store version — it is outdated.)")
            sb.appendLine()
        }
        if (termuxInstalled) {
            val missingPkgs = buildList {
                if (curlPath == null) add("curl")
                if (wgetPath == null) add("wget")
                if (pythonPath == null) add("python")
                if (nodePath == null) add("nodejs")
                if (busyboxPath == null) add("busybox")
            }
            if (missingPkgs.isNotEmpty()) {
                sb.appendLine("Install missing tools via Termux:")
                sb.appendLine("  action=termux_pkg_install packages='${missingPkgs.joinToString(" ")}'")
            } else {
                sb.appendLine("✅ All essential tools are available.")
            }
        }
        sb.appendLine()
        sb.appendLine("Tip: after installing Termux packages, use action=env_check to verify.")

        return ToolExecutionResult(sb.toString().trimEnd())
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun err(msg: String) = ToolExecutionResult(msg, isError = true)

    /**
     * Normalizes command output where some backends may return "(no output)" placeholder
     * for successful commands with empty stdout.
     */
    private fun normalizeExecOutput(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value == "(no output)") return null
        if (value.startsWith("ERROR", ignoreCase = true)) return null
        return value
    }

    private fun Result<String>.toToolResult(): ToolExecutionResult =
        fold(
            onSuccess = { ToolExecutionResult(it.ifBlank { "(no output)" }) },
            onFailure = { ToolExecutionResult("❌ ${it.message}", isError = true) }
        )

    /**
     * Validate [url] using [java.net.URL] and return the original string if valid,
     * or null if the URL is malformed or uses a non-http/https scheme.
     */
    private fun validateUrl(url: String): String? {
        return try {
            val parsed = URL(url.trim())
            if (parsed.protocol !in setOf("http", "https")) null else url.trim()
        } catch (_: MalformedURLException) {
            null
        }
    }
}
