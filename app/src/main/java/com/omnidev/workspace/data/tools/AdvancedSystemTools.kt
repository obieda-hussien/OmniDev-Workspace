package com.omnidev.workspace.data.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.Telephony
import android.util.Base64
import androidx.core.content.ContextCompat
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ────────────────────────────────────────────────────────────────────────────────
//  Advanced System Tools
//
//  Extends the agent's system-level capabilities with deeper OS access:
//  • Call log reader / searcher
//  • SMS reader / searcher
//  • Screenshot capture via Shizuku
//  • System settings modifier (FIXED: Safe shell quoting instead of strict regex)
//  • APK installer via Shizuku (FIXED: Supports paths with spaces)
//  • Advanced root shell (FIXED: Uses Base64 injection for complex piped commands)
// ────────────────────────────────────────────────────────────────────────────────

// Helper for safe shell argument escaping
private fun shellQuote(s: String): String = "'${s.replace("'", "'\\''")}'"

/**
 * Reads and queries the device call log via [CallLog.Calls.CONTENT_URI].
 * Requires `READ_CALL_LOG` permission.
 */
object CallLogTool {

    private const val MAX_RESULTS = 50

    fun execute(
        context: Context,
        action: String,
        query: String? = null,
        limit: Int = MAX_RESULTS
    ): ToolExecutionResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return ToolExecutionResult("READ_CALL_LOG permission not granted. Please grant it in Settings.", isError = true)
        }
        return runCatching {
            when (action.lowercase()) {
                "read_recent" -> readRecent(context, limit)
                "search" -> {
                    if (query.isNullOrBlank()) return ToolExecutionResult("Missing 'query' parameter.", isError = true)
                    searchCalls(context, query, limit)
                }
                "count" -> countCalls(context)
                else -> ToolExecutionResult("Unknown action '$action'.", isError = true)
            }
        }.getOrElse { e -> ToolExecutionResult("Call log error: ${e.message}", isError = true) }
    }

    private fun readRecent(context: Context, limit: Int): ToolExecutionResult {
        val safeLim = limit.coerceIn(1, MAX_RESULTS)
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null, null, "${CallLog.Calls.DATE} DESC"
        )
        return formatCursor(cursor, safeLim)
    }

    private fun searchCalls(context: Context, query: String, limit: Int): ToolExecutionResult {
        val safeQuery = query.replace("%", "\\%").replace("_", "\\_")
        val safeLim = limit.coerceIn(1, MAX_RESULTS)
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            "${CallLog.Calls.NUMBER} LIKE ? ESCAPE '\\' OR ${CallLog.Calls.CACHED_NAME} LIKE ? ESCAPE '\\'",
            arrayOf("%$safeQuery%", "%$safeQuery%"),
            "${CallLog.Calls.DATE} DESC"
        )
        return formatCursor(cursor, safeLim)
    }

    private fun countCalls(context: Context): ToolExecutionResult {
        context.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf("count(*)"), null, null, null)?.use {
            if (it.moveToFirst()) return ToolExecutionResult("Total call log entries: ${it.getInt(0)}")
        }
        return ToolExecutionResult("Unable to count call log entries.", isError = true)
    }

    private fun formatCursor(cursor: Cursor?, limit: Int): ToolExecutionResult {
        if (cursor == null) return ToolExecutionResult("Failed to query call log.", isError = true)
        cursor.use {
            if (!it.moveToFirst()) return ToolExecutionResult("No call log entries found.")
            val sb = StringBuilder()
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            var count = 0
            do {
                if (count >= limit) break
                val number = it.getString(0) ?: "Unknown"
                val name = it.getString(1) ?: ""
                val type = when (it.getInt(2)) {
                    CallLog.Calls.INCOMING_TYPE -> "📥 Incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "📤 Outgoing"
                    CallLog.Calls.MISSED_TYPE -> "❌ Missed"
                    CallLog.Calls.REJECTED_TYPE -> "🚫 Rejected"
                    else -> "Unknown"
                }
                val nameStr = if (name.isNotBlank()) " ($name)" else ""
                sb.appendLine("$type | $number$nameStr | ${df.format(Date(it.getLong(3)))} | ${it.getLong(4)}s")
                count++
            } while (it.moveToNext())
            return ToolExecutionResult("Call Log ($count entries):\n${sb.toString().trimEnd()}")
        }
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "call_log_tool",
            description = "Read device call logs (Requires READ_CALL_LOG). Actions: 'read_recent', 'search', 'count'.",
            parameters = listOf(
                ToolParameter("action", "string", "Action: read_recent, search, count.", required = true),
                ToolParameter("query", "string", "Search filter (number/name).", required = false),
                ToolParameter("limit", "string", "Max entries (default 50).", required = false)
            )
        )
    )
}

/**
 * Reads SMS messages. Requires `READ_SMS` permission.
 */
object SmsReaderTool {

    private const val MAX_RESULTS = 30

    fun execute(
        context: Context,
        action: String,
        query: String? = null,
        limit: Int = MAX_RESULTS
    ): ToolExecutionResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return ToolExecutionResult("READ_SMS permission not granted. Please grant it in Settings.", isError = true)
        }
        return runCatching {
            when (action.lowercase()) {
                "read_inbox" -> readMessages(context, Telephony.Sms.Inbox.CONTENT_URI, limit)
                "read_sent" -> readMessages(context, Telephony.Sms.Sent.CONTENT_URI, limit)
                "search" -> {
                    if (query.isNullOrBlank()) return ToolExecutionResult("Missing 'query'.", isError = true)
                    searchMessages(context, query, limit)
                }
                else -> ToolExecutionResult("Unknown sms action '$action'.", isError = true)
            }
        }.getOrElse { e -> ToolExecutionResult("SMS error: ${e.message}", isError = true) }
    }

    private fun readMessages(context: Context, uri: Uri, limit: Int): ToolExecutionResult {
        val safeLim = limit.coerceIn(1, MAX_RESULTS)
        val cursor = context.contentResolver.query(
            uri, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ),
            null, null, "${Telephony.Sms.DATE} DESC"
        )
        return formatSmsCursor(cursor, safeLim)
    }

    private fun searchMessages(context: Context, query: String, limit: Int): ToolExecutionResult {
        val safeQuery = query.replace("%", "\\%").replace("_", "\\_")
        val safeLim = limit.coerceIn(1, MAX_RESULTS)
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ),
            "${Telephony.Sms.ADDRESS} LIKE ? ESCAPE '\\' OR ${Telephony.Sms.BODY} LIKE ? ESCAPE '\\'",
            arrayOf("%$safeQuery%", "%$safeQuery%"),
            "${Telephony.Sms.DATE} DESC"
        )
        return formatSmsCursor(cursor, safeLim)
    }

    private fun formatSmsCursor(cursor: Cursor?, limit: Int): ToolExecutionResult {
        if (cursor == null) return ToolExecutionResult("Failed to query SMS.", isError = true)
        cursor.use {
            if (!it.moveToFirst()) return ToolExecutionResult("No SMS messages found.")
            val sb = StringBuilder()
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            var count = 0
            do {
                if (count >= limit) break
                val address = it.getString(0) ?: "Unknown"
                val body = it.getString(1) ?: ""
                val isRead = it.getInt(3) == 1
                
                // Truncate body to prevent huge outputs, but give enough context (250 chars)
                val truncBody = if (body.length > 250) body.take(250) + "…" else body
                sb.appendLine("${if (isRead) "✅" else "🆕"} $address | ${df.format(Date(it.getLong(2)))}")
                sb.appendLine("   $truncBody")
                count++
            } while (it.moveToNext())
            return ToolExecutionResult("SMS Messages ($count entries):\n${sb.toString().trimEnd()}")
        }
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "sms_reader_tool",
            description = "Read device SMS. Actions: 'read_inbox', 'read_sent', 'search'. Requires READ_SMS.",
            parameters = listOf(
                ToolParameter("action", "string", "Action: read_inbox, read_sent, search.", required = true),
                ToolParameter("query", "string", "Search filter.", required = false),
                ToolParameter("limit", "string", "Max entries (default 30).", required = false)
            )
        )
    )
}

/**
 * Saves a screenshot to disk via Shizuku.
 * Note: For LLM visual inspection, `visual_inspector` (Base64) is preferred.
 */
object ScreenshotTool {
    private const val SCREENSHOT_DIR = "/data/local/tmp"

    suspend fun execute(filename: String? = null): ToolExecutionResult = withContext(Dispatchers.IO) {
        val safeName = (filename ?: "omnidev_screenshot_${System.currentTimeMillis()}.png").replace(Regex("[^a-zA-Z0-9._-]"), "")
        if (safeName.isEmpty()) return@withContext ToolExecutionResult("Invalid filename.", isError = true)
        
        val path = "$SCREENSHOT_DIR/$safeName"
        val result = PrivilegedExecutionManager.executeCommand("screencap -p > ${shellQuote(path)} && chmod 666 ${shellQuote(path)}")
        
        if (result.isSuccess) ToolExecutionResult("✅ Screenshot saved to $path")
        else ToolExecutionResult("Screenshot failed: ${result.exceptionOrNull()?.message}", isError = true)
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "screenshot_tool",
            description = "Take a screenshot and save it to /data/local/tmp/ as PNG. Note: If you want to visually inspect the screen, use 'visual_inspector' instead.",
            parameters = listOf(ToolParameter("filename", "string", "Output filename.", required = false))
        )
    )
}

/**
 * Modifies Android system settings (system, secure, global) securely via Shizuku.
 */
object SystemSettingsTool {

    suspend fun execute(action: String, namespace: String, key: String, value: String? = null): ToolExecutionResult = withContext(Dispatchers.IO) {
        val safeNamespace = namespace.lowercase()
        if (safeNamespace !in listOf("system", "secure", "global")) {
            return@withContext ToolExecutionResult("Invalid namespace. Use system, secure, or global.", isError = true)
        }
        
        // Keys are usually strict alphanumeric/underscores
        val safeKey = key.replace(Regex("[^a-zA-Z0-9_.]"), "")
        if (safeKey.isEmpty()) return@withContext ToolExecutionResult("Invalid setting key.", isError = true)

        return@withContext when (action.lowercase()) {
            "get" -> {
                val result = PrivilegedExecutionManager.executeCommand("settings get $safeNamespace $safeKey")
                if (result.isSuccess) ToolExecutionResult("$safeNamespace/$safeKey = ${result.getOrDefault("").trim()}")
                else ToolExecutionResult("Failed to get setting: ${result.exceptionOrNull()?.message}", isError = true)
            }
            "put" -> {
                if (value.isNullOrBlank()) {
                    ToolExecutionResult("Missing 'value' for put action.", isError = true)
                } else {
                    // FIX: Safe quoting allows URLs, colons, slashes, and spaces in values (e.g. accessibility services)
                    val cmd = "settings put $safeNamespace $safeKey ${shellQuote(value)}"
                    val result = PrivilegedExecutionManager.executeCommand(cmd)
                    
                    if (result.isSuccess) ToolExecutionResult("✅ Set $safeNamespace/$safeKey = $value")
                    else ToolExecutionResult("Failed to set setting: ${result.exceptionOrNull()?.message}", isError = true)
                }
            }
            else -> ToolExecutionResult("Unknown action. Use get or put.", isError = true)
        }
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "system_settings_tool",
            description = "Read or modify Android system settings (system, secure, global). Requires Shizuku/Root.",
            parameters = listOf(
                ToolParameter("action", "string", "Action: 'get' or 'put'.", required = true),
                ToolParameter("namespace", "string", "Namespace: 'system', 'secure', or 'global'.", required = true),
                ToolParameter("key", "string", "Setting key (e.g. screen_brightness).", required = true),
                ToolParameter("value", "string", "Value to set (for 'put').", required = false)
            )
        )
    )
}

/**
 * Installs or uninstalls APK packages safely via Shizuku.
 */
object PackageInstallerTool {

    suspend fun execute(action: String, target: String, extraFlags: String? = null): ToolExecutionResult = withContext(Dispatchers.IO) {
        when (action.lowercase()) {
            "install" -> {
                // FIX: Use shellQuote instead of Regex to allow valid paths with spaces
                val flags = extraFlags?.replace(Regex("[^a-zA-Z0-9_\\- ]"), "") ?: ""
                val cmd = "pm install $flags ${shellQuote(target)}".trim()
                
                val result = PrivilegedExecutionManager.executeCommand(cmd)
                if (result.isSuccess) ToolExecutionResult("✅ ${result.getOrDefault("")}")
                else ToolExecutionResult("Install failed: ${result.exceptionOrNull()?.message}", isError = true)
            }
            "uninstall" -> {
                val safePkg = target.replace(Regex("[^a-zA-Z0-9._]"), "")
                if (safePkg.isEmpty()) return@withContext ToolExecutionResult("Invalid package name.", isError = true)
                
                val result = PrivilegedExecutionManager.executeCommand("pm uninstall $safePkg")
                if (result.isSuccess) ToolExecutionResult("✅ ${result.getOrDefault("")}")
                else ToolExecutionResult("Uninstall failed: ${result.exceptionOrNull()?.message}", isError = true)
            }
            "list_packages" -> {
                val filter = target.replace(Regex("[^a-zA-Z0-9._]"), "")
                val cmd = if (filter.isNotEmpty()) "pm list packages | grep -F ${shellQuote(filter)}" else "pm list packages"
                
                val result = PrivilegedExecutionManager.executeCommand(cmd)
                if (result.isSuccess) ToolExecutionResult(result.getOrDefault(""))
                else ToolExecutionResult("Failed to list packages: ${result.exceptionOrNull()?.message}", isError = true)
            }
            else -> ToolExecutionResult("Unknown action. Use install, uninstall, or list_packages.", isError = true)
        }
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "package_installer_tool",
            description = "Install, uninstall, or list APKs via Shizuku. Use '-r -g' flags for install to upgrade and grant permissions.",
            parameters = listOf(
                ToolParameter("action", "string", "install, uninstall, list_packages.", required = true),
                ToolParameter("target", "string", "APK path or package name.", required = true),
                ToolParameter("extraFlags", "string", "e.g. '-r -g'.", required = false)
            )
        )
    )
}

/**
 * Advanced root shell tool using Base64 script injection for ultimate stability.
 */
object AdvancedRootShellTool {

    private const val MAX_OUTPUT = 8_000

    suspend fun execute(command: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (command.isBlank()) return@withContext ToolExecutionResult("Empty command.", isError = true)

        // FIX: Use Base64 Script Injection to completely bypass shell quoting hell
        // This ensures complex commands (like awk, sed, and chained pipes) execute perfectly.
        val scriptContent = buildString {
            appendLine("#!/system/bin/sh")
            appendLine(command)
        }

        val tmpPath = "/data/local/tmp/omni_root_${System.currentTimeMillis()}.sh"
        val b64 = Base64.encodeToString(scriptContent.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        
        val writeCmd = "echo ${shellQuote(b64)} | base64 -d > $tmpPath && chmod +x $tmpPath && echo WRITE_OK"
        val writeResult = PrivilegedExecutionManager.executeCommand(writeCmd)
        
        if (writeResult.isFailure || !writeResult.getOrDefault("").contains("WRITE_OK")) {
            return@withContext ToolExecutionResult("Failed to inject root script.", isError = true)
        }

        val result = PrivilegedExecutionManager.executeCommand("sh $tmpPath 2>&1; rm -f $tmpPath")
        
        result.fold(
            onSuccess = { output ->
                val text = output.trim().ifBlank { "(no output)" }
                val truncated = text.length > MAX_OUTPUT
                ToolExecutionResult(
                    output = if (truncated) "...[TRUNCATED]...\n" + text.takeLast(MAX_OUTPUT) else text,
                    truncated = truncated
                )
            },
            onFailure = { e -> ToolExecutionResult("Root command failed: ${e.message}", isError = true) }
        )
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "root_shell_tool",
            description = "Execute complex root-level shell commands. Supports pipes, redirects, and heavy awk/sed processing flawlessly using Base64 injection.",
            parameters = listOf(
                ToolParameter("command", "string", "Full shell command to execute.", required = true)
            )
        )
    )
}
