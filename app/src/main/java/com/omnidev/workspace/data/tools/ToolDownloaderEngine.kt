package com.omnidev.workspace.data.tools

import android.content.Context
import android.util.Log
import java.net.URL

/**
 * Unified downloader/installer surface used by the agent runtime and composite manager.
 */
object ToolDownloaderEngine {

    private const val TAG = "ToolDownloaderEngine"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "install_tool",
            description = "Install/provision a tool by name. Uses EnvironmentSetupManager ensure_tool flow and optional direct HTTPS fallback download.",
            parameters = listOf(
                ToolParameter("tool", "string", "Tool name to install (python, node, git, aapt2, jadx, apktool, curl...)", required = true),
                ToolParameter("language", "string", "Optional runtime hint.", required = false),
                ToolParameter("termux_package", "string", "Optional Termux package override.", required = false),
                ToolParameter("pip_package", "string", "Optional pip package to install.", required = false),
                ToolParameter("npm_package", "string", "Optional npm package to install.", required = false),
                ToolParameter("fallback_url", "string", "Optional HTTPS fallback URL.", required = false),
                ToolParameter("java_class", "string", "Optional Java main class for jar fallback.", required = false),
                ToolParameter("verify_command", "string", "Optional verification command.", required = false)
            )
        ),
        ToolDefinition(
            name = "tools_status",
            description = "Show runtime/tools status report.",
            parameters = emptyList()
        )
    )

    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult {
        return when (name) {
            "install_tool" -> installTool(args)
            "tools_status" -> toolsStatus()
            else -> ToolExecutionResult("Unknown tool downloader action: $name", isError = true)
        }
    }

    suspend fun installTool(args: Map<String, String>): ToolExecutionResult {
        val tool = args["tool"]?.trim().orEmpty()
        if (tool.isBlank()) return ToolExecutionResult("install_tool requires 'tool'.", isError = true)
        val payload = buildMap {
            put("action", "ensure_tool")
            put("tool", tool)
            args["language"]?.let { put("language", it) }
            args["termux_package"]?.let { put("termux_package", it) }
            args["pip_package"]?.let { put("pip_package", it) }
            args["npm_package"]?.let { put("npm_package", it) }
            args["fallback_url"]?.let { put("fallback_url", it) }
            args["java_class"]?.let { put("java_class", it) }
            args["verify_command"]?.let { put("verify_command", it) }
        }
        return EnvironmentSetupManager.executeTool("advanced_terminal", payload)
    }

    suspend fun toolsStatus(): ToolExecutionResult = EnvironmentSetupManager.statusReport()

    suspend fun downloadFile(url: String, destPath: String?): ToolExecutionResult {
        validateHttpsUrl(url) ?: return ToolExecutionResult("Invalid URL: '$url'", isError = true)
        val cleanUrl = url.substringBefore("?")
        val filename = cleanUrl.substringAfterLast("/")
            .replace(Regex("[^a-zA-Z0-9_.\\-]"), "")
            .ifBlank { "download_${System.currentTimeMillis()}" }
        val dest = destPath?.takeIf { !it.contains("..") } ?: "/data/local/tmp/$filename"
        val cmd = """
            dir_path="$(dirname ${EnvironmentSetupManager.shellQuote(dest)})" &&
            mkdir -p "${'$'}dir_path" &&
            if command -v curl >/dev/null 2>&1; then
              curl -fsSL -o ${EnvironmentSetupManager.shellQuote(dest)} ${EnvironmentSetupManager.shellQuote(url)} 2>&1
            elif command -v wget >/dev/null 2>&1; then
              wget -q -O ${EnvironmentSetupManager.shellQuote(dest)} ${EnvironmentSetupManager.shellQuote(url)} 2>&1
            else
              echo "Missing curl and wget"; exit 1
            fi
        """.trimIndent()
        val res = EnvironmentSetupManager.executeShell(cmd, useBase64 = true)
        return if (res.isError) res else ToolExecutionResult("✅ Downloaded to: $dest")
    }

    suspend fun downloadExec(url: String, extraArgs: String, cwd: String?): ToolExecutionResult {
        validateHttpsUrl(url) ?: return ToolExecutionResult("Invalid URL: '$url'", isError = true)
        val tmp = "/data/local/tmp/agent_dl_${System.currentTimeMillis()}.sh"
        val argPart = extraArgs.trim().takeIf { it.isNotBlank() }?.let { " ${EnvironmentSetupManager.shellQuote(it)}" } ?: ""
        val script = buildString {
            if (!cwd.isNullOrBlank()) appendLine("cd ${EnvironmentSetupManager.shellQuote(cwd)} || exit 1")
            appendLine("if command -v curl >/dev/null 2>&1; then curl -fsSL -o $tmp ${EnvironmentSetupManager.shellQuote(url)}; else wget -q -O $tmp ${EnvironmentSetupManager.shellQuote(url)}; fi")
            appendLine("chmod +x $tmp")
            appendLine("sh $tmp$argPart")
            appendLine("rm -f $tmp")
        }
        return EnvironmentSetupManager.executeShell(script, useBase64 = true)
    }

    suspend fun downloadVerify(url: String, checksum: String, dest: String?): ToolExecutionResult {
        val expected = checksum.lowercase().replace(Regex("[^a-f0-9]"), "")
        if (expected.length != 64) return ToolExecutionResult("checksum must be a 64-char SHA256 hex string.", isError = true)
        val dl = downloadFile(url, dest)
        if (dl.isError) return dl
        val filePath = dl.output.substringAfter("Downloaded to: ").trim()
        val hashRes = EnvironmentSetupManager.executeShell("sha256sum ${EnvironmentSetupManager.shellQuote(filePath)} 2>&1")
        val actual = hashRes.output.substringBefore(" ").trim().lowercase()
        return if (actual == expected) {
            ToolExecutionResult("✅ File verified: SHA256 matches.\nPath: $filePath")
        } else {
            EnvironmentSetupManager.executeShell("rm -f ${EnvironmentSetupManager.shellQuote(filePath)}")
            ToolExecutionResult("❌ Checksum mismatch. File deleted.", isError = true)
        }
    }

    private fun validateHttpsUrl(url: String): String? {
        return try {
            val parsed = URL(url.trim())
            if (parsed.protocol != "https") null else url.trim()
        } catch (e: Exception) {
            Log.w(TAG, "Invalid URL: ${e.message}")
            null
        }
    }
}
