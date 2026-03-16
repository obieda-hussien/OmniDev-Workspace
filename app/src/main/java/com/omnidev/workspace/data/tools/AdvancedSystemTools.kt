package com.omnidev.workspace.data.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.Telephony
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ────────────────────────────────────────────────────────────────────────────────
//  Advanced System Tools
//
//  Extends the agent's system-level capabilities with deeper OS access:
//  • Call log reader / deleter
//  • SMS reader
//  • Screenshot capture via Shizuku
//  • System settings modifier
//  • APK installer via Shizuku
//  • Advanced root shell with complex piped commands
// ────────────────────────────────────────────────────────────────────────────────

/**
 * Reads and queries the device call log via [CallLog.Calls.CONTENT_URI].
 *
 * Requires `READ_CALL_LOG` permission at runtime.
 */
object CallLogTool {

    private const val MAX_RESULTS = 50

    /**
     * Reads recent call log entries, optionally filtered by phone number.
     *
     * @param context Application context for ContentResolver and permission checks.
     * @param action  One of `"read_recent"`, `"search"`, or `"count"`.
     * @param query   Optional phone number substring to filter (for `"search"`).
     * @param limit   Max entries to return (default [MAX_RESULTS]).
     */
    fun execute(
        context: Context,
        action: String,
        query: String? = null,
        limit: Int = MAX_RESULTS
    ): ToolExecutionResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolExecutionResult(
                "READ_CALL_LOG permission not granted. Please grant it in Settings → App Permissions.",
                isError = true
            )
        }
        return runCatching {
            when (action.lowercase()) {
                "read_recent" -> readRecent(context, limit)
                "search" -> {
                    if (query.isNullOrBlank()) {
                        return ToolExecutionResult(
                            "Missing 'query' parameter for search action.", isError = true
                        )
                    }
                    searchCalls(context, query, limit)
                }
                "count" -> countCalls(context)
                else -> ToolExecutionResult(
                    "Unknown call_log action '$action'. Use read_recent, search, or count.",
                    isError = true
                )
            }
        }.getOrElse { e ->
            ToolExecutionResult("Call log error: ${e.message}", isError = true)
        }
    }

    private fun readRecent(context: Context, limit: Int): ToolExecutionResult {
        val safeLim = limit.coerceIn(1, MAX_RESULTS)
        val cursor: Cursor? = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            ),
            null, null,
            "${CallLog.Calls.DATE} DESC"
        )
        return formatCursor(cursor, safeLim)
    }

    private fun searchCalls(context: Context, query: String, limit: Int): ToolExecutionResult {
        val safeQuery = query.replace("%", "\\%").replace("_", "\\_")
        val safeLim = limit.coerceIn(1, MAX_RESULTS)
        val cursor: Cursor? = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            ),
            "${CallLog.Calls.NUMBER} LIKE ? ESCAPE '\\' OR ${CallLog.Calls.CACHED_NAME} LIKE ? ESCAPE '\\'",
            arrayOf("%$safeQuery%", "%$safeQuery%"),
            "${CallLog.Calls.DATE} DESC"
        )
        return formatCursor(cursor, safeLim)
    }

    private fun countCalls(context: Context): ToolExecutionResult {
        val cursor: Cursor? = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf("count(*) AS count"),
            null, null, null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val count = it.getInt(0)
                return ToolExecutionResult("Total call log entries: $count")
            }
        }
        return ToolExecutionResult("Unable to count call log entries.", isError = true)
    }

    private fun formatCursor(cursor: Cursor?, limit: Int): ToolExecutionResult {
        if (cursor == null) {
            return ToolExecutionResult("Failed to query call log.", isError = true)
        }
        cursor.use {
            if (!it.moveToFirst()) {
                return ToolExecutionResult("No call log entries found.")
            }
            val sb = StringBuilder()
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
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
                val date = df.format(Date(it.getLong(3)))
                val duration = it.getLong(4)
                val nameStr = if (name.isNotBlank()) " ($name)" else ""
                sb.appendLine("$type | $number$nameStr | $date | ${duration}s")
                count++
            } while (it.moveToNext())
            sb.insert(0, "Call Log ($count entries):\n")
            return ToolExecutionResult(sb.toString().trim())
        }
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "call_log_tool",
            description = "Read the device call log. " +
                "Actions: 'read_recent' (last N calls), 'search' (filter by number or name), " +
                "'count' (total entries). Requires READ_CALL_LOG permission.",
            parameters = listOf(
                ToolParameter("action", "string", "Action: read_recent, search, or count.", required = true),
                ToolParameter("query", "string", "Phone number or name to search for (used with 'search').", required = false),
                ToolParameter("limit", "string", "Max entries to return (default 50, max 50).", required = false)
            )
        )
    )
}

/**
 * Reads SMS messages from the device inbox and sent folders.
 *
 * Requires `READ_SMS` permission at runtime.
 */
object SmsReaderTool {

    private const val MAX_RESULTS = 30

    /**
     * Reads SMS messages.
     *
     * @param context Application context.
     * @param action  One of `"read_inbox"`, `"read_sent"`, `"search"`.
     * @param query   Optional filter for search (phone number or body text).
     * @param limit   Max entries (default [MAX_RESULTS]).
     */
    fun execute(
        context: Context,
        action: String,
        query: String? = null,
        limit: Int = MAX_RESULTS
    ): ToolExecutionResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolExecutionResult(
                "READ_SMS permission not granted. Please grant it in Settings → App Permissions.",
                isError = true
            )
        }
        return runCatching {
            when (action.lowercase()) {
                "read_inbox" -> readMessages(context, Telephony.Sms.Inbox.CONTENT_URI, limit)
                "read_sent" -> readMessages(context, Telephony.Sms.Sent.CONTENT_URI, limit)
                "search" -> {
                    if (query.isNullOrBlank()) {
                        return ToolExecutionResult(
                            "Missing 'query' for search.", isError = true
                        )
                    }
                    searchMessages(context, query, limit)
                }
                else -> ToolExecutionResult(
                    "Unknown sms action '$action'. Use read_inbox, read_sent, or search.",
                    isError = true
                )
            }
        }.getOrElse { e ->
            ToolExecutionResult("SMS error: ${e.message}", isError = true)
        }
    }

    private fun readMessages(context: Context, uri: Uri, limit: Int): ToolExecutionResult {
        val safeLim = limit.coerceIn(1, MAX_RESULTS)
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ
            ),
            null, null,
            "${Telephony.Sms.DATE} DESC"
        )
        return formatSmsCursor(cursor, safeLim)
    }

    private fun searchMessages(context: Context, query: String, limit: Int): ToolExecutionResult {
        val safeQuery = query.replace("%", "\\%").replace("_", "\\_")
        val safeLim = limit.coerceIn(1, MAX_RESULTS)
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ
            ),
            "${Telephony.Sms.ADDRESS} LIKE ? ESCAPE '\\' OR ${Telephony.Sms.BODY} LIKE ? ESCAPE '\\'",
            arrayOf("%$safeQuery%", "%$safeQuery%"),
            "${Telephony.Sms.DATE} DESC"
        )
        return formatSmsCursor(cursor, safeLim)
    }

    private fun formatSmsCursor(cursor: Cursor?, limit: Int): ToolExecutionResult {
        if (cursor == null) {
            return ToolExecutionResult("Failed to query SMS.", isError = true)
        }
        cursor.use {
            if (!it.moveToFirst()) {
                return ToolExecutionResult("No SMS messages found.")
            }
            val sb = StringBuilder()
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            var count = 0
            do {
                if (count >= limit) break
                val address = it.getString(0) ?: "Unknown"
                val body = it.getString(1) ?: ""
                val date = df.format(Date(it.getLong(2)))
                val isRead = it.getInt(3) == 1
                val readIcon = if (isRead) "✅" else "🆕"
                // Truncate body to prevent excessive output
                val truncBody = if (body.length > 120) body.take(120) + "…" else body
                sb.appendLine("$readIcon $address | $date")
                sb.appendLine("   $truncBody")
                count++
            } while (it.moveToNext())
            sb.insert(0, "SMS Messages ($count entries):\n")
            return ToolExecutionResult(sb.toString().trim())
        }
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "sms_reader_tool",
            description = "Read SMS messages from the device. " +
                "Actions: 'read_inbox', 'read_sent', 'search' (by number or body text). " +
                "Requires READ_SMS permission.",
            parameters = listOf(
                ToolParameter("action", "string", "Action: read_inbox, read_sent, or search.", required = true),
                ToolParameter("query", "string", "Phone number or text to search for (used with 'search').", required = false),
                ToolParameter("limit", "string", "Max messages to return (default 30).", required = false)
            )
        )
    )
}

/**
 * Takes a screenshot of the current device screen via Shizuku-brokered `screencap`.
 *
 * Saves the screenshot to `/data/local/tmp/` and returns the file path.
 * The agent can then use file tools to read or share the image.
 */
object ScreenshotTool {

    private const val SCREENSHOT_DIR = "/data/local/tmp"

    /**
     * Captures a screenshot.
     *
     * @param filename Optional filename (default: `omnidev_screenshot.png`).
     */
    suspend fun execute(filename: String? = null): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val safeName = (filename ?: "omnidev_screenshot.png")
                .replace(Regex("[^a-zA-Z0-9._-]"), "")
            if (safeName.isEmpty()) {
                return@withContext ToolExecutionResult("Invalid filename.", isError = true)
            }
            val path = "$SCREENSHOT_DIR/$safeName"
            val result = ShizukuCommandTool.execute("screencap -p $path")
            when (result) {
                is ShizukuResult.Success ->
                    ToolExecutionResult("✅ Screenshot saved to $path")
                is ShizukuResult.Failure ->
                    ToolExecutionResult("Screenshot failed: ${result.reason}", isError = true)
                is ShizukuResult.PermissionRequired ->
                    ToolExecutionResult(result.message, isError = true)
                is ShizukuResult.Unavailable ->
                    ToolExecutionResult(result.message, isError = true)
            }
        }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "screenshot_tool",
            description = "Take a screenshot of the current device screen. " +
                "Saves to /data/local/tmp/ as a PNG file. Requires Shizuku.",
            parameters = listOf(
                ToolParameter("filename", "string", "Output filename (default: omnidev_screenshot.png).", required = false)
            )
        )
    )
}

/**
 * Modifies Android system settings via Shizuku shell commands.
 *
 * Supports `system`, `secure`, and `global` namespaces — mirroring
 * the `settings put/get` ADB interface.
 */
object SystemSettingsTool {

    /**
     * Reads or writes a system setting.
     *
     * @param action    `"get"` or `"put"`.
     * @param namespace `"system"`, `"secure"`, or `"global"`.
     * @param key       The setting key (e.g. `"screen_brightness"`, `"location_mode"`).
     * @param value     The value to set (required for `"put"`).
     */
    suspend fun execute(
        action: String,
        namespace: String,
        key: String,
        value: String? = null
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val safeNamespace = namespace.lowercase()
        if (safeNamespace !in listOf("system", "secure", "global")) {
            return@withContext ToolExecutionResult(
                "Invalid namespace '$namespace'. Use system, secure, or global.",
                isError = true
            )
        }
        // Sanitize key: only allow alphanumeric, underscore, and dot
        val safeKey = key.replace(Regex("[^a-zA-Z0-9_.]"), "")
        if (safeKey.isEmpty()) {
            return@withContext ToolExecutionResult("Invalid setting key.", isError = true)
        }

        when (action.lowercase()) {
            "get" -> {
                val result = ShizukuCommandTool.execute("settings get $safeNamespace $safeKey")
                when (result) {
                    is ShizukuResult.Success ->
                        ToolExecutionResult("$safeNamespace/$safeKey = ${result.output.trim()}")
                    is ShizukuResult.Failure ->
                        ToolExecutionResult(result.reason, isError = true)
                    is ShizukuResult.PermissionRequired ->
                        ToolExecutionResult(result.message, isError = true)
                    is ShizukuResult.Unavailable ->
                        ToolExecutionResult(result.message, isError = true)
                }
            }
            "put" -> {
                if (value.isNullOrBlank()) {
                    return@withContext ToolExecutionResult(
                        "Missing 'value' for put action.", isError = true
                    )
                }
                // Sanitize value: only allow alphanumeric, underscore, dot, dash, and space
                val safeValue = value.replace(Regex("[^a-zA-Z0-9_. -]"), "")
                val result = ShizukuCommandTool.execute(
                    "settings put $safeNamespace $safeKey $safeValue"
                )
                when (result) {
                    is ShizukuResult.Success ->
                        ToolExecutionResult("✅ Set $safeNamespace/$safeKey = $safeValue")
                    is ShizukuResult.Failure ->
                        ToolExecutionResult(result.reason, isError = true)
                    is ShizukuResult.PermissionRequired ->
                        ToolExecutionResult(result.message, isError = true)
                    is ShizukuResult.Unavailable ->
                        ToolExecutionResult(result.message, isError = true)
                }
            }
            else -> ToolExecutionResult(
                "Unknown settings action '$action'. Use get or put.",
                isError = true
            )
        }
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "system_settings_tool",
            description = "Read or modify Android system settings (system, secure, global). " +
                "Actions: 'get' (read a setting), 'put' (write a setting). " +
                "Examples: screen_brightness, screen_off_timeout, location_mode, airplane_mode_on. " +
                "Requires Shizuku.",
            parameters = listOf(
                ToolParameter("action", "string", "Action: 'get' or 'put'.", required = true),
                ToolParameter("namespace", "string", "Settings namespace: 'system', 'secure', or 'global'.", required = true),
                ToolParameter("key", "string", "Setting key (e.g. screen_brightness, location_mode).", required = true),
                ToolParameter("value", "string", "Value to set (required for 'put').", required = false)
            )
        )
    )
}

/**
 * Installs or uninstalls APK packages via Shizuku shell commands.
 *
 * All file paths are sanitized to prevent shell injection.
 */
object PackageInstallerTool {

    /**
     * Installs or uninstalls a package.
     *
     * @param action      `"install"` or `"uninstall"`.
     * @param target      APK file path (for install) or package name (for uninstall).
     * @param extraFlags  Optional extra `pm` flags (e.g. `-r` for reinstall, `-g` for grant permissions).
     */
    suspend fun execute(
        action: String,
        target: String,
        extraFlags: String? = null
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        // Sanitize target: for install, allow path chars; for uninstall, allow package name chars
        when (action.lowercase()) {
            "install" -> {
                val safePath = target.replace(Regex("[^a-zA-Z0-9_.\\-/]"), "")
                if (safePath.isEmpty() || !safePath.endsWith(".apk")) {
                    return@withContext ToolExecutionResult(
                        "Invalid APK path. Must end with .apk.", isError = true
                    )
                }
                val flags = extraFlags?.replace(Regex("[^a-zA-Z0-9_\\- ]"), "") ?: ""
                val cmd = "pm install $flags $safePath".trim()
                executeShizuku(cmd)
            }
            "uninstall" -> {
                val safePkg = target.replace(Regex("[^a-zA-Z0-9._]"), "")
                if (safePkg.isEmpty()) {
                    return@withContext ToolExecutionResult(
                        "Invalid package name.", isError = true
                    )
                }
                executeShizuku("pm uninstall $safePkg")
            }
            "list_packages" -> {
                val filter = target.replace(Regex("[^a-zA-Z0-9._]"), "")
                val cmd = if (filter.isNotEmpty()) "pm list packages | grep -F $filter" else "pm list packages"
                executeShizuku(cmd)
            }
            else -> ToolExecutionResult(
                "Unknown package action '$action'. Use install, uninstall, or list_packages.",
                isError = true
            )
        }
    }

    private suspend fun executeShizuku(cmd: String): ToolExecutionResult {
        return when (val result = ShizukuCommandTool.execute(cmd)) {
            is ShizukuResult.Success -> ToolExecutionResult("✅ ${result.output}")
            is ShizukuResult.Failure -> ToolExecutionResult(result.reason, isError = true)
            is ShizukuResult.PermissionRequired -> ToolExecutionResult(result.message, isError = true)
            is ShizukuResult.Unavailable -> ToolExecutionResult(result.message, isError = true)
        }
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "package_installer_tool",
            description = "Install, uninstall, or list APK packages using Shizuku (root-level pm commands). " +
                "Actions: 'install' (APK path), 'uninstall' (package name), 'list_packages' (filter string). " +
                "Use '-r' flag for reinstall, '-g' for auto-grant permissions.",
            parameters = listOf(
                ToolParameter("action", "string", "Action: install, uninstall, or list_packages.", required = true),
                ToolParameter("target", "string", "APK file path (install) or package name (uninstall/list_packages).", required = true),
                ToolParameter("extraFlags", "string", "Extra pm flags like '-r -g' (install only).", required = false)
            )
        )
    )
}

/**
 * Advanced root shell tool for complex piped/chained Shizuku commands.
 *
 * This gives the agent the ability to execute arbitrary shell command pipelines
 * with a higher character limit and output capture suitable for system diagnostics.
 */
object AdvancedRootShellTool {

    /** Max output characters returned to avoid context overflow. */
    private const val MAX_OUTPUT = 6_000

    /**
     * Executes an arbitrary shell command via Shizuku.
     *
     * @param command The full shell command (may include pipes, redirects, etc.).
     */
    suspend fun execute(command: String): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            if (command.isBlank()) {
                return@withContext ToolExecutionResult("Empty command.", isError = true)
            }
            val result = ShizukuCommandTool.execute(command)
            when (result) {
                is ShizukuResult.Success -> {
                    val output = result.output
                    val truncated = output.length > MAX_OUTPUT
                    ToolExecutionResult(
                        output = if (truncated) output.take(MAX_OUTPUT) + "\n...[truncated]" else output,
                        truncated = truncated
                    )
                }
                is ShizukuResult.Failure ->
                    ToolExecutionResult(result.reason, isError = true)
                is ShizukuResult.PermissionRequired ->
                    ToolExecutionResult(result.message, isError = true)
                is ShizukuResult.Unavailable ->
                    ToolExecutionResult(result.message, isError = true)
            }
        }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "root_shell_tool",
            description = "Execute advanced root-level shell commands via Shizuku. " +
                "Supports complex piped commands, redirects, and system-level operations. " +
                "Examples: 'dumpsys battery', 'getprop ro.build.version.sdk', " +
                "'cat /proc/meminfo | head -20', 'wm size', 'wm density'. " +
                "Use this for any system command not covered by other specialized tools. " +
                "For opening URLs/apps, prefer Android intent tools instead of root shell.",
            parameters = listOf(
                ToolParameter("command", "string", "Full shell command to execute.", required = true)
            )
        )
    )
}
