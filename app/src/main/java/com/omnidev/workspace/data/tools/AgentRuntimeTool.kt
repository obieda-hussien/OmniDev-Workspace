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
 * All execution goes through [PrivilegedExecutionManager] (Shizuku → rish → root/SU),
 * so commands run with ADB-level or root-level privilege as available.
 *
 * ### Supported actions (`agent_runtime` tool)
 * | Action               | Description                                                  |
 * |----------------------|--------------------------------------------------------------|
 * | `env_check`          | Detect available runtimes: Python, Node, npm, pip, curl, wget, git, busybox, Termux |
 * | `tool_which`         | Check if a specific tool binary is on PATH                   |
 * | `python_run`         | Execute inline Python code (creates & runs a temp .py file)  |
 * | `node_run`           | Execute inline JavaScript (creates & runs a temp .js file)   |
 * | `shell_script`       | Write and execute a multi-line shell script                  |
 * | `pip_install`        | Install Python packages via pip/pip3                         |
 * | `pip_list`           | List installed Python packages                               |
 * | `pip_run`            | Run a Python one-liner via `python3 -c`                      |
 * | `npm_install`        | Install npm packages globally                                |
 * | `download_file`      | Download a file from a URL via curl or wget                  |
 * | `download_exec`      | Download a script from a URL and execute it                  |
 * | `termux_check`       | Check if Termux is installed and available                   |
 * | `termux_run`         | Execute a command in Termux's environment (via Shizuku)       |
 * | `termux_pkg_install` | Install a Termux package via `pkg install`                   |
 * | `busybox_run`        | Execute a command via busybox (useful when coreutils missing)|
 * | `tool_bootstrap`     | Bootstrap minimal tools (busybox, curl, Python) if missing   |
 */
object AgentRuntimeTool {

    private const val TERMUX_PKG = "com.termux"
    private const val TERMUX_BIN = "/data/data/com.termux/files/usr/bin"
    private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"

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
                PrivilegedExecutionManager.executeCommand("python3 -c ${shellQuote(code)}").toToolResult()
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
        val tools = listOf(
            "python3", "python", "node", "nodejs", "npm",
            "pip3", "pip", "curl", "wget", "git", "perl", "ruby",
            "busybox", "bash", "zsh", "adb"
        )
        val sb = StringBuilder("=== Runtime environment check ===\n")

        for (tool in tools) {
            val result = PrivilegedExecutionManager.executeCommand("which $tool 2>/dev/null")
            val path = result.getOrNull()?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("ERROR") }
            if (path != null) {
                // Also try to get version
                val ver = PrivilegedExecutionManager.executeCommand("$tool --version 2>&1 | head -1")
                    .getOrNull()?.trim()?.take(60) ?: ""
                sb.appendLine("✅ $tool → $path  [$ver]")
            } else {
                sb.appendLine("❌ $tool — not found")
            }
        }

        // Check Termux
        val termuxInstalled = PrivilegedExecutionManager.executeCommand(
            "pm list packages | grep com.termux"
        ).getOrNull()?.contains("com.termux") == true
        sb.appendLine(if (termuxInstalled) "✅ Termux — installed ($TERMUX_PKG)" else "❌ Termux — not installed")

        // Check Shizuku / rish
        val shizukuReady = PrivilegedExecutionManager.isShizukuReady()
        val rootAvailable = PrivilegedExecutionManager.isRootAvailable()
        sb.appendLine()
        sb.appendLine("Privilege backend: ${when { shizukuReady -> "✅ Shizuku"; rootAvailable -> "⚠️ Root only"; else -> "❌ None" }}")

        return ToolExecutionResult(sb.toString().trimEnd())
    }

    private suspend fun toolWhich(tool: String): ToolExecutionResult {
        val safeTool = tool.replace(Regex("[^a-zA-Z0-9_.\\-]"), "")
        if (safeTool.isEmpty()) return err("Invalid tool name.")
        return PrivilegedExecutionManager.executeCommand("which $safeTool 2>/dev/null || echo 'NOT FOUND'").toToolResult()
    }

    private suspend fun pythonRun(code: String, extraArgs: String?): ToolExecutionResult {
        // Run inline via `python3 -c` — avoids writing to app-private cacheDir,
        // which the Shizuku `shell` process cannot access due to SELinux type enforcement.
        // extraArgs are forwarded as quoted positional arguments after the -c code block
        // (sys.argv[0] == '-c', sys.argv[1..] == the extra args).
        val argStr = extraArgs?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            ?.joinToString(" ") { shellQuote(it) }
            ?.let { " $it" } ?: ""
        return PrivilegedExecutionManager.executeCommand(
            "python3 -c ${shellQuote(code)}$argStr 2>&1"
        ).toToolResult()
    }

    private suspend fun nodeRun(code: String): ToolExecutionResult {
        // Run inline via `node -e` — avoids app-private cacheDir access issue.
        return PrivilegedExecutionManager.executeCommand(
            "node -e ${shellQuote(code)} 2>&1"
        ).toToolResult()
    }

    private suspend fun shellScript(scriptContent: String, interpreter: String): ToolExecutionResult {
        val safeInterpreter = interpreter.lowercase().trim()
            .let { ALLOWED_INTERPRETERS.firstOrNull { allowed -> allowed == it } ?: "sh" }
        // Use the appropriate inline evaluation flag:
        // node uses `--eval` / `-e`; all shell interpreters and Python use `-c`.
        val flag = if (safeInterpreter == "node") "-e" else "-c"
        return PrivilegedExecutionManager.executeCommand(
            "$safeInterpreter $flag ${shellQuote(scriptContent)} 2>&1"
        ).toToolResult()
    }

    private suspend fun pipInstall(packages: String, upgrade: Boolean): ToolExecutionResult {
        // Allow only valid PyPI package specifiers: name + optional version specifiers (== >= <= ~=)
        // Intentionally exclude < > ! which could be used for redirection/injection
        val safePkgs = packages.split(Regex("\\s+"))
            .map { it.replace(Regex("[^a-zA-Z0-9_.\\-\\[\\]=~]"), "") }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (safePkgs.isEmpty()) return err("No valid package names provided.")
        val upgradeFlag = if (upgrade) " --upgrade" else ""
        // Try pip3/pip on PATH first, then python3 -m pip (works even without pip on PATH),
        // then Termux's Python as a last resort for environments where only Termux has Python.
        val cmd = buildString {
            append("pip3 install$upgradeFlag $safePkgs 2>&1")
            append(" || pip install$upgradeFlag $safePkgs 2>&1")
            append(" || python3 -m pip install$upgradeFlag $safePkgs 2>&1")
            append(" || $TERMUX_BIN/python3 -m pip install$upgradeFlag $safePkgs 2>&1")
            append(" || $TERMUX_BIN/python -m pip install$upgradeFlag $safePkgs 2>&1")
        }
        return PrivilegedExecutionManager.executeCommand(cmd).toToolResult()
    }

    private suspend fun pipList(): ToolExecutionResult {
        val cmd = buildString {
            append("pip3 list 2>&1")
            append(" || pip list 2>&1")
            append(" || python3 -m pip list 2>&1")
            append(" || $TERMUX_BIN/python3 -m pip list 2>&1")
            append(" || $TERMUX_BIN/python -m pip list 2>&1")
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
        return PrivilegedExecutionManager.executeCommand(
            "npm install$globalFlag $safePkgs 2>&1 || $TERMUX_BIN/npm install$globalFlag $safePkgs 2>&1"
        ).toToolResult()
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
        val installed = PrivilegedExecutionManager.executeCommand(
            "pm list packages | grep com.termux"
        ).getOrNull()?.contains("com.termux") == true

        if (!installed) {
            sb.appendLine("❌ Termux is not installed.")
            sb.appendLine("Install from F-Droid: https://f-droid.org/en/packages/com.termux/")
            return ToolExecutionResult(sb.toString().trim())
        }
        sb.appendLine("✅ Termux is installed")

        // Check if bash exists
        val bashExists = File(TERMUX_BASH).exists()
        sb.appendLine("Termux bash: ${if (bashExists) "✅ $TERMUX_BASH" else "❌ not found"}")

        // List key Termux packages if accessible
        if (bashExists && (PrivilegedExecutionManager.isShizukuReady() || PrivilegedExecutionManager.isRootAvailable())) {
            val pkgList = PrivilegedExecutionManager.executeCommand(
                "TERMUX_PREFIX=/data/data/com.termux/files/usr " +
                "run-as com.termux /data/data/com.termux/files/usr/bin/dpkg --list 2>/dev/null | head -20 " +
                "|| ls /data/data/com.termux/files/usr/bin/ | head -30 2>/dev/null"
            ).getOrNull()
            if (!pkgList.isNullOrBlank()) {
                sb.appendLine("\nTermux binaries/packages (sample):")
                sb.append(pkgList.take(2000))
            }
        }
        return ToolExecutionResult(sb.toString().trimEnd())
    }

    private suspend fun termuxRun(command: String): ToolExecutionResult {
        // Wrap the user command in proper POSIX quoting via shellQuote
        // so that the bash -c argument receives exactly the command string
        // without risk of shell injection at the wrapping level.
        val termuxEnvCmd = buildString {
            append("PATH=/data/data/com.termux/files/usr/bin:")
            append("/data/data/com.termux/files/usr/sbin:")
            append("/system/bin:/system/xbin ")
            append("HOME=/data/data/com.termux/files/home ")
            append("PREFIX=/data/data/com.termux/files/usr ")
            append("/data/data/com.termux/files/usr/bin/bash -c ${shellQuote(command)}")
        }
        return PrivilegedExecutionManager.executeCommand(termuxEnvCmd).toToolResult()
    }

    private suspend fun termuxPkgInstall(packages: String): ToolExecutionResult {
        val safePkgs = packages.split(Regex("\\s+"))
            .map { it.replace(Regex("[^a-zA-Z0-9_.\\-+]"), "") }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (safePkgs.isEmpty()) return err("No valid package names.")
        val cmd = buildString {
            append("PATH=/data/data/com.termux/files/usr/bin ")
            append("HOME=/data/data/com.termux/files/home ")
            append("PREFIX=/data/data/com.termux/files/usr ")
            append("TERMUX_APP_PACKAGE_MANAGER=apt ")
            append("/data/data/com.termux/files/usr/bin/pkg install -y $safePkgs 2>&1")
        }
        return PrivilegedExecutionManager.executeCommand(cmd).toToolResult()
    }

    private suspend fun toolBootstrap(): ToolExecutionResult {
        val sb = StringBuilder("=== Tool Bootstrap ===\n")

        // Check curl
        val hasCurl = PrivilegedExecutionManager.executeCommand("which curl 2>/dev/null")
            .getOrNull()?.trim()?.isNotEmpty() == true
        sb.appendLine("curl: ${if (hasCurl) "✅ already available" else "❌ not found"}")

        // Check wget
        val hasWget = PrivilegedExecutionManager.executeCommand("which wget 2>/dev/null")
            .getOrNull()?.trim()?.isNotEmpty() == true
        sb.appendLine("wget: ${if (hasWget) "✅ already available" else "❌ not found"}")

        // Check busybox
        val hasBusybox = PrivilegedExecutionManager.executeCommand("which busybox 2>/dev/null")
            .getOrNull()?.trim()?.isNotEmpty() == true
        sb.appendLine("busybox: ${if (hasBusybox) "✅ already available" else "❌ not found"}")

        // Check python3
        val hasPython = PrivilegedExecutionManager.executeCommand("which python3 2>/dev/null")
            .getOrNull()?.trim()?.isNotEmpty() == true
        sb.appendLine("python3: ${if (hasPython) "✅ already available" else "❌ not found"}")

        sb.appendLine()
        sb.appendLine("To install missing tools, use:")
        if (!hasCurl && !hasWget) {
            sb.appendLine("• action=termux_pkg_install packages='curl wget' (requires Termux)")
        }
        if (!hasPython) {
            sb.appendLine("• action=termux_pkg_install packages='python' (requires Termux)")
            sb.appendLine("• Or use action=download_file to fetch a Python binary for Android")
        }
        sb.appendLine()
        sb.appendLine("For Termux integration:")
        sb.appendLine("• Install Termux from F-Droid, then use action=termux_run and action=termux_pkg_install")
        sb.appendLine("• Termux provides: python, node, git, gcc, clang, and 1000+ Unix tools")

        return ToolExecutionResult(sb.toString().trimEnd())
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun err(msg: String) = ToolExecutionResult(msg, isError = true)

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
