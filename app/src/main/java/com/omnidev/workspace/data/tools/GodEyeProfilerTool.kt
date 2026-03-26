package com.omnidev.workspace.data.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * GodEyeProfilerTool — Network & Database Profiler for Autonomous Debugging.
 *
 * Provides the agent with abilities to:
 * 1. `read_network_log`   — Parse OkHttp debug logs or system network traces from the target app
 * 2. `read_db_schema`     — Dump the SQLite schema of a target app's database file
 * 3. `analyze_anr_trace`  — Parse ANR trace files from /data/anr/ (requires Shizuku)
 * 4. `memory_snapshot`    — Report memory stats of a target process via /proc/<pid>/status
 */
class GodEyeProfilerTool(
    private val context: Context,
    private val shizukuCommandTool: ShizukuCommandTool
) {

    companion object {
        private val TOOLS = listOf(
            ToolDefinition(
                name = "read_network_log",
                description = "Reads the last N lines of the target app's network debug log from logcat. " +
                        "Filter by OkHttp tag to capture HTTP request/response pairs.",
                parameters = listOf(
                    ToolParameter("package_name", "string", "Target app package name (e.g. com.example.app)", required = true),
                    ToolParameter("lines", "string", "Number of log lines to capture (default: 100)", required = false)
                )
            ),
            ToolDefinition(
                name = "read_db_schema",
                description = "Dumps the SQLite schema (CREATE TABLE statements) of the target app's database. " +
                        "Requires Shizuku for cross-app database access.",
                parameters = listOf(
                    ToolParameter("package_name", "string", "Target app package name", required = true),
                    ToolParameter("db_name", "string", "Database file name (e.g. app_database or room.db)", required = true)
                )
            ),
            ToolDefinition(
                name = "analyze_anr_trace",
                description = "Reads and summarises the latest ANR (Application Not Responding) trace file " +
                        "for the target app. Requires Shizuku.",
                parameters = listOf(
                    ToolParameter("package_name", "string", "Target app package name to filter traces", required = false)
                )
            ),
            ToolDefinition(
                name = "memory_snapshot",
                description = "Reports the memory usage (RSS, VmPeak, Threads) for a running app process " +
                        "using /proc filesystem. Returns a formatted snapshot.",
                parameters = listOf(
                    ToolParameter("package_name", "string", "Target app package name", required = true)
                )
            )
        )
    }

    fun getToolDefs(): List<ToolDefinition> = TOOLS

    suspend fun execute(toolName: String, params: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            try {
                when (toolName) {
                    "read_network_log" -> readNetworkLog(
                        packageName = params["package_name"] ?: return@withContext "Error: package_name required",
                        lines = params["lines"]?.toIntOrNull() ?: 100
                    )
                    "read_db_schema" -> readDbSchema(
                        packageName = params["package_name"] ?: return@withContext "Error: package_name required",
                        dbName = params["db_name"] ?: return@withContext "Error: db_name required"
                    )
                    "analyze_anr_trace" -> analyzeAnrTrace(
                        packageName = params["package_name"]
                    )
                    "memory_snapshot" -> memorySnapshot(
                        packageName = params["package_name"] ?: return@withContext "Error: package_name required"
                    )
                    else -> "Error: Unknown profiler tool: $toolName"
                }
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }

    private suspend fun readNetworkLog(packageName: String, lines: Int): String {
        val safePackage = packageName.replace(Regex("[^a-zA-Z0-9._]"), "")
        if (safePackage.isEmpty()) return "Error: Invalid package name"
        val result = shizukuCommandTool.execute(
            "logcat -d -t $lines -v time | grep -F '$safePackage' | tail -n $lines"
        ).toDisplayString()
        return if (result.isBlank()) "No network log entries found for $safePackage" else result
    }

    private suspend fun readDbSchema(packageName: String, dbName: String): String {
        val safePackage = packageName.replace(Regex("[^a-zA-Z0-9._]"), "")
        val safeName = dbName.replace(Regex("[^a-zA-Z0-9._-]"), "")
        if (safePackage.isEmpty() || safeName.isEmpty()) return "Error: Invalid parameters"

        val dbPath = "/data/data/$safePackage/databases/$safeName"
        val tmpPath = "/data/local/tmp/omnidev_schema_dump.txt"
        shizukuCommandTool.execute("sqlite3 $dbPath .schema > $tmpPath 2>&1")
        val schema = shizukuCommandTool.execute("cat $tmpPath").toDisplayString()
        shizukuCommandTool.execute("rm -f $tmpPath")
        return if (schema.isBlank()) "No schema found at $dbPath" else "Schema for $safePackage/$safeName:\n$schema"
    }

    private suspend fun analyzeAnrTrace(packageName: String?): String {
        val traceDir = "/data/anr"
        val listResult = shizukuCommandTool.execute("ls -t $traceDir 2>/dev/null | head -5").toDisplayString()
        if (listResult.isBlank() || listResult.contains("No such file")) {
            return "No ANR traces found at $traceDir"
        }
        val latestFile = listResult.lines().firstOrNull()?.trim() ?: return "No ANR trace files"
        val content = shizukuCommandTool.execute(
            if (packageName != null) {
                val safe = packageName.replace(Regex("[^a-zA-Z0-9._]"), "")
                "grep -A 50 '$safe' $traceDir/$latestFile 2>/dev/null | head -100"
            } else {
                "head -100 $traceDir/$latestFile 2>/dev/null"
            }
        ).toDisplayString()
        return if (content.isBlank()) "ANR trace found but no content for ${packageName ?: "any process"}"
               else "ANR Trace ($latestFile):\n$content"
    }

    private suspend fun memorySnapshot(packageName: String): String {
        val safePackage = packageName.replace(Regex("[^a-zA-Z0-9._]"), "")
        if (safePackage.isEmpty()) return "Error: Invalid package name"
        val pid = shizukuCommandTool.execute("pidof $safePackage 2>/dev/null").toDisplayString().trim()
        if (pid.isBlank()) return "Process not running: $safePackage"
        val status = shizukuCommandTool.execute("cat /proc/$pid/status 2>/dev/null").toDisplayString()
        val meminfo = shizukuCommandTool.execute("cat /proc/$pid/smaps_rollup 2>/dev/null | head -5").toDisplayString()
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        return buildString {
            appendLine("Memory Snapshot for $safePackage (PID: $pid) at $timestamp")
            appendLine("─".repeat(50))
            if (status.isNotBlank()) {
                appendLine(status.lines().filter { line ->
                    line.startsWith("VmRSS") || line.startsWith("VmPeak") ||
                    line.startsWith("Threads") || line.startsWith("VmSwap")
                }.joinToString("\n"))
            }
            if (meminfo.isNotBlank()) {
                appendLine("─".repeat(50))
                appendLine("Smaps Rollup:")
                appendLine(meminfo)
            }
        }
    }
}
