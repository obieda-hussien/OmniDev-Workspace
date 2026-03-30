package com.omnidev.workspace.data.tools

import android.content.Context
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.MalformedURLException
import java.net.URL

/**
 * AgentRuntimeTool — The Master Router for autonomous tool execution.
 *
 * * UPGRADES IN V2:
 * 1. Deep Integration with TermuxEnvironmentBridge: Routes complex shell, node, and python 
 * scripts through the Base64 injection engine to prevent quoting/escaping crashes.
 * 2. Safe URL Parsing: Strips query parameters from downloaded filenames.
 * 3. Unified Package Management: Delegates npm and pip directly to the bridge.
 */
object AgentRuntimeTool {

    private val TERMUX_BIN  = TermuxEnvironmentBridge.TERMUX_BIN
    private val TERMUX_BASH = TermuxEnvironmentBridge.TERMUX_BASH

    private val ALLOWED_INTERPRETERS = setOf("sh", "bash", "dash", "ash", "python3", "python", "node", "perl", "ruby")

    private fun shellQuote(s: String): String = "'${s.replace("'", "'\\''")}'"

    // ─────────────────────────────────────────────────────────────────────
    // Tool Definitions
    // ─────────────────────────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "agent_runtime",
            description = """
                Execute advanced runtime operations: detect tools, run Python/Node/shell code inline,
                download/execute scripts, and manage packages. Backed by Shizuku/Root and Termux.

                Actions:
                • env_check           — Survey available runtimes.
                • tool_which          — Locate a binary (param: 'tool').
                • python_run          — Execute inline Python (param: 'code', optional: 'args', 'cwd').
                • node_run            — Execute inline JavaScript (param: 'code', optional: 'cwd').
                • shell_script        — Execute multi-line shell scripts safely (param: 'script', optional: 'cwd').
                • pip_install         — Install Python packages (param: 'packages', optional: 'upgrade').
                • pip_list            — List installed Python packages.
                • npm_install         — Install npm packages (param: 'packages', optional: 'global').
                • download_file       — Download file (param: 'url', optional: 'dest_path').
                • download_exec       — Download and execute a script (param: 'url', optional: 'args', 'cwd').
                • termux_check        — Check Termux status.
                • termux_run          — Execute command in Termux (param: 'command', optional: 'cwd').
                • termux_pkg_install  — Install Termux packages (param: 'packages').
                • tool_bootstrap      — Guide to bootstrap missing essential tools.
            """.trimIndent(),
            parameters = listOf(
                ToolParameter("action", "string", "Action to perform (e.g., shell_script, env_check).", required = true),
                ToolParameter("code", "string", "Source code for python_run / node_run.", required = false),
                ToolParameter("script", "string", "Multi-line script content for shell_script.", required = false),
                ToolParameter("command", "string", "Shell command for termux_run.", required = false),
                ToolParameter("packages", "string", "Space-separated package list.", required = false),
                ToolParameter("tool", "string", "Binary name for tool_which.", required = false),
                ToolParameter("url", "string", "URL for download_file / download_exec.", required = false),
                ToolParameter("dest_path", "string", "Destination path for download_file.", required = false),
                ToolParameter("args", "string", "Additional arguments.", required = false),
                ToolParameter("cwd", "string", "Current Working Directory for execution.", required = false),
                ToolParameter("interpreter", "string", "Interpreter for shell_script (default: sh).", required = false),
                ToolParameter("upgrade", "string", "If 'true', installs with --upgrade flag.", required = false),
                ToolParameter("global", "string", "If 'false', drops the -g flag for npm.", required = false)
            )
        )
    )

    // ─────────────────────────────────────────────────────────────────────
    // Execution Router
    // ─────────────────────────────────────────────────────────────

    suspend fun execute(
        context: Context,
        action: String,
        args: Map<String, String>
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val cwd = args["cwd"]
        
        when (action.lowercase().trim()) {
            "env_check", "termux_check" -> TermuxEnvironmentBridge.statusReport()

            "tool_which" -> {
                val tool = args["tool"] ?: return@withContext err("tool_which requires 'tool'")
                toolWhich(tool)
            }

            "python_run" -> {
                val code = args["code"] ?: return@withContext err("python_run requires 'code'")
                TermuxEnvironmentBridge.runPython(code, args["args"], cwd)
            }

            "node_run" -> {
                val code = args["code"] ?: return@withContext err("node_run requires 'code'")
                nodeRun(code, cwd)
            }

            "shell_script", "termux_run" -> {
                val script = args["script"] ?: args["command"] ?: return@withContext err("requires 'script' or 'command'")
                TermuxEnvironmentBridge.executeSingleShot(script, cwd)
            }

            "pip_install" -> {
                val pkgs = args["packages"] ?: return@withContext err("pip_install requires 'packages'")
                TermuxEnvironmentBridge.pipInstall(pkgs, args["upgrade"]?.lowercase() == "true")
            }

            "pip_list" -> TermuxEnvironmentBridge.executeSingleShot("pip list || pip3 list")

            "npm_install" -> {
                val pkgs = args["packages"] ?: return@withContext err("npm_install requires 'packages'")
                val isGlobal = args["global"]?.lowercase() != "false"
                val globalFlag = if (isGlobal) " -g" else ""
                TermuxEnvironmentBridge.npmCommand("install$globalFlag $pkgs", cwd)
            }

            "download_file" -> {
                val url = args["url"] ?: return@withContext err("download_file requires 'url'")
                downloadFile(url, args["dest_path"])
            }

            "download_exec" -> {
                val url = args["url"] ?: return@withContext err("download_exec requires 'url'")
                downloadExec(url, args["args"] ?: "", cwd)
            }

            "termux_pkg_install" -> {
                val pkgs = args["packages"] ?: return@withContext err("termux_pkg_install requires 'packages'")
                TermuxEnvironmentBridge.pkgInstall(pkgs)
            }

            "tool_bootstrap" -> toolBootstrap()

            else -> err("Unknown action: '$action'.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Action Implementations
    // ─────────────────────────────────────────────────────────────

    private suspend fun toolWhich(tool: String): ToolExecutionResult {
        val safeTool = tool.replace(Regex("[^a-zA-Z0-9_.\\-]"), "")
        if (safeTool.isEmpty()) return err("Invalid tool name.")
        val path = TermuxEnvironmentBridge.findBinary(safeTool)
        return if (path != null) {
            ToolExecutionResult("✅ $safeTool → $path")
        } else {
            ToolExecutionResult("❌ '$safeTool' not found on system PATH or in Termux.", isError = true)
        }
    }

    private suspend fun nodeRun(code: String, cwd: String?): ToolExecutionResult {
        val nodeBin = TermuxEnvironmentBridge.findBinary("node") ?: "node"
        
        // Build a robust shell script to run the JS code
        val scriptContent = buildString {
            if (!cwd.isNullOrBlank()) appendLine("cd ${shellQuote(cwd)} || exit 1")
            appendLine("$nodeBin -e ${shellQuote(code)}")
        }
        return TermuxEnvironmentBridge.executeSingleShot(scriptContent, cwd)
    }

    private suspend fun downloadFile(url: String, destPath: String?): ToolExecutionResult {
        val validatedUrl = validateUrl(url) ?: return err("Invalid URL: '$url'")
        
        // FIX: Strip query parameters (?token=...) before extracting the filename
        val cleanUrl = validatedUrl.substringBefore("?")
        val filename = cleanUrl.substringAfterLast("/").replace(Regex("[^a-zA-Z0-9_.\\-]"), "")
            .ifBlank { "download_${System.currentTimeMillis()}" }
            
        val dest = destPath?.replace(Regex("[^a-zA-Z0-9_.\\-/]"), "") ?: "/data/local/tmp/$filename"
        if (dest.contains("..")) return err("Destination path must not contain '..'")

        val cmd = "curl -fsSL -o ${shellQuote(dest)} ${shellQuote(validatedUrl)} 2>&1 || wget -q -O ${shellQuote(dest)} ${shellQuote(validatedUrl)} 2>&1"
        
        // Run via bridge to ensure network/DNS resolves properly
        val result = TermuxEnvironmentBridge.executeSingleShot(cmd)
        return if (result.isError) result else ToolExecutionResult("✅ Downloaded to: $dest\n${result.output}")
    }

    private suspend fun downloadExec(url: String, extraArgs: String, cwd: String?): ToolExecutionResult {
        val validatedUrl = validateUrl(url) ?: return err("Invalid URL: '$url'")
        
        val tmpPath = "/data/local/tmp/agent_dl_${System.currentTimeMillis()}.sh"
        val argPart = if (extraArgs.isBlank()) "" else " ${shellQuote(extraArgs)}"
        
        // Script injection ensures safe execution
        val script = buildString {
            if (!cwd.isNullOrBlank()) appendLine("cd ${shellQuote(cwd)} || exit 1")
            appendLine("curl -fsSL -o $tmpPath ${shellQuote(validatedUrl)} || wget -q -O $tmpPath ${shellQuote(validatedUrl)}")
            appendLine("chmod +x $tmpPath")
            appendLine("sh $tmpPath$argPart")
            appendLine("rm -f $tmpPath")
        }
        
        return TermuxEnvironmentBridge.executeSingleShot(script, cwd)
    }

    private suspend fun toolBootstrap(): ToolExecutionResult {
        val sb = StringBuilder("=== OmniDev Tool Bootstrap ===\n\n")
        
        val termuxOk = TermuxEnvironmentBridge.isTermuxUsable()
        if (!termuxOk) {
            sb.appendLine("⚠️ Termux is MISSING.")
            sb.appendLine("1. Install F-Droid: https://f-droid.org")
            sb.appendLine("2. Install Termux from F-Droid (NOT Play Store).")
            sb.appendLine("3. Open Termux once, then grant Shizuku access to OmniDev.")
            return ToolExecutionResult(sb.toString())
        }

        sb.appendLine("✅ Termux is installed. To install missing tools, run this action:")
        sb.appendLine("action=termux_pkg_install packages='python nodejs git wget curl busybox'")
        
        return ToolExecutionResult(sb.toString())
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun err(msg: String) = ToolExecutionResult(msg, isError = true)

    private fun validateUrl(url: String): String? {
        return try {
            val parsed = URL(url.trim())
            if (parsed.protocol !in setOf("http", "https")) null else url.trim()
        } catch (_: MalformedURLException) {
            null
        }
    }
}
