package com.omnidev.workspace.data.tools

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * GodEyeProfilerTool — Network & Database Profiler for Autonomous Debugging.
 *
 * * HACKER UPGRADES:
 * 1. Native SQLite Dumping: Bypasses the missing `sqlite3` binary on production Android devices 
 * by securely copying the DB via Shizuku and reading it via native Android SQLite APIs.
 * 2. Multi-PID Resolution: Properly extracts the primary PID for multi-process apps to avoid /proc shell errors.
 * 3. Stack-Safe Logcat: Resolves PID before checking OkHttp logs to prevent multi-line JSON breaks.
 */
class GodEyeProfilerTool(
    private val context: Context,
    private val shizukuCommandTool: ShizukuCommandTool // Assumes this is injected/passed
) {

    companion object {
        private val TOOLS = listOf(
            ToolDefinition(
                name = "read_network_log",
                description = "Reads the last N lines of the target app's network debug log from logcat. " +
                        "Automatically resolves the app's PID to capture multi-line OkHttp request/response pairs.",
                parameters = listOf(
                    ToolParameter("package_name", "string", "Target app package name (e.g. com.example.app)", required = true),
                    ToolParameter("lines", "string", "Number of log lines to capture (default: 200)", required = false)
                )
            ),
            ToolDefinition(
                name = "read_db_schema",
                description = "Dumps the SQLite schema (CREATE TABLE/INDEX statements) of the target app's database. " +
                        "Uses Shizuku to securely copy and parse the DB cross-app.",
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
                        "using the /proc filesystem. Returns a formatted snapshot.",
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
                        lines = params["lines"]?.toIntOrNull() ?: 200
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

    // ─────────────────────────────────────────────────────────────
    // Network Profiler
    // ─────────────────────────────────────────────────────────────

    private suspend fun readNetworkLog(packageName: String, lines: Int): String {
        val safePackage = packageName.replace(Regex("[^a-zA-Z0-9._]"), "")
        if (safePackage.isEmpty()) return "Error: Invalid package name"

        val safeLines = lines.coerceIn(50, 2000)

        // 1. Get PIDs for the target package
        val pidRaw = shizukuCommandTool.execute("pidof $safePackage 2>/dev/null").toDisplayString().trim()
        val pids = pidRaw.split("\\s+".toRegex()).filter { it.isNotBlank() && it.all { c -> c.isDigit() } }

        val cmd = if (pids.isEmpty()) {
            // Fallback to text grep if app is dead
            "logcat -d -v threadtime -t ${safeLines * 2} | grep -F '$safePackage' | tail -n $safeLines"
        } else {
            // Accurate PID grep for multi-line OkHttp logs
            val pidRegex = pids.joinToString("|")
            "logcat -d -v threadtime -t ${safeLines * 3} | grep -E '^\\S+\\s+\\S+\\s+($pidRegex)\\s+' | tail -n $safeLines"
        }

        val result = shizukuCommandTool.execute(cmd).toDisplayString().trim()
        return if (result.isBlank()) "No network log entries found for $safePackage in the recent buffer." else result
    }

    // ─────────────────────────────────────────────────────────────
    // Database Schema Profiler
    // ─────────────────────────────────────────────────────────────

    private suspend fun readDbSchema(packageName: String, dbName: String): String {
        val safePackage = packageName.replace(Regex("[^a-zA-Z0-9._]"), "")
        val safeName = dbName.replace(Regex("[^a-zA-Z0-9._-]"), "")
        if (safePackage.isEmpty() || safeName.isEmpty()) return "Error: Invalid parameters"

        val originalDbPath = "/data/data/$safePackage/databases/$safeName"
        val tmpDbPath = "/data/local/tmp/omni_dump_${System.currentTimeMillis()}.db"

        // 1. Securely copy the DB using Shizuku to a readable location
        val copyCmd = "cp '$originalDbPath' '$tmpDbPath' && chmod 666 '$tmpDbPath'"
        val copyResult = shizukuCommandTool.execute(copyCmd).toDisplayString()
        
        if (copyResult.contains("No such file", ignoreCase = true)) {
            return "Error: Database '$safeName' not found at $originalDbPath."
        }

        // 2. Open locally using Android's native SQLite API (Bypasses missing sqlite3 binary)
        val tmpFile = File(tmpDbPath)
        if (!tmpFile.exists()) {
            return "Error: Failed to copy database via Shizuku. Check permissions."
        }

        val schemaBuilder = StringBuilder()
        schemaBuilder.appendLine("Schema for $safePackage / $safeName:")
        schemaBuilder.appendLine("─".repeat(50))

        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(tmpDbPath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = db.rawQuery("SELECT type, name, sql FROM sqlite_master WHERE sql IS NOT NULL", null)
            
            cursor.use { c ->
                val typeIdx = c.getColumnIndex("type")
                val nameIdx = c.getColumnIndex("name")
                val sqlIdx = c.getColumnIndex("sql")
                
                var count = 0
                while (c.moveToNext()) {
                    val type = c.getString(typeIdx)
                    val name = c.getString(nameIdx)
                    val sql = c.getString(sqlIdx)
                    
                    if (name != "android_metadata" && name != "sqlite_sequence") {
                        schemaBuilder.appendLine("-- $type: $name")
                        schemaBuilder.appendLine("$sql;\n")
                        count++
                    }
                }
                if (count == 0) schemaBuilder.appendLine("Database is empty or contains no readable tables.")
            }
        } catch (e: Exception) {
            schemaBuilder.appendLine("Failed to parse SQLite file natively: ${e.message}")
        } finally {
            db?.close()
            // 3. Clean up the temp file
            shizukuCommandTool.execute("rm -f '$tmpDbPath'")
            tmpFile.delete()
        }

        return schemaBuilder.toString().trimEnd()
    }

    // ─────────────────────────────────────────────────────────────
    // ANR Trace Profiler
    // ─────────────────────────────────────────────────────────────

    private suspend fun analyzeAnrTrace(packageName: String?): String {
        val traceDir = "/data/anr"
        val listResult = shizukuCommandTool.execute("ls -t $traceDir 2>/dev/null | head -5").toDisplayString()
        
        if (listResult.isBlank() || listResult.contains("No such file")) {
            return "No ANR traces found at $traceDir"
        }
        
        val latestFile = listResult.lines().firstOrNull { it.isNotBlank() }?.trim() 
            ?: return "No ANR trace files available."
            
        val cmd = if (packageName != null) {
            val safe = packageName.replace(Regex("[^a-zA-Z0-9._]"), "")
            // Grab 150 lines after finding the package name to capture the full thread dump
            "grep -A 150 '$safe' $traceDir/$latestFile 2>/dev/null"
        } else {
            "head -200 $traceDir/$latestFile 2>/dev/null"
        }
        
        val content = shizukuCommandTool.execute(cmd).toDisplayString()
        
        return if (content.isBlank()) {
            "ANR trace ($latestFile) exists, but no relevant content found for ${packageName ?: "any process"}."
        } else {
            "ANR Trace ($latestFile):\n$content"
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Memory Profiler
    // ─────────────────────────────────────────────────────────────

    private suspend fun memorySnapshot(packageName: String): String {
        val safePackage = packageName.replace(Regex("[^a-zA-Z0-9._]"), "")
        if (safePackage.isEmpty()) return "Error: Invalid package name"
        
        // FIX: Extract ONLY the first (primary) PID to avoid breaking the shell command
        val pidRaw = shizukuCommandTool.execute("pidof $safePackage 2>/dev/null").toDisplayString().trim()
        val primaryPid = pidRaw.split("\\s+".toRegex()).firstOrNull { it.isNotBlank() && it.all { c -> c.isDigit() } }
        
        if (primaryPid == null) return "Process not running: $safePackage"

        val status = shizukuCommandTool.execute("cat /proc/$primaryPid/status 2>/dev/null").toDisplayString()
        val meminfo = shizukuCommandTool.execute("cat /proc/$primaryPid/smaps_rollup 2>/dev/null | head -5").toDisplayString()
        
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        
        return buildString {
            appendLine("Memory Snapshot for $safePackage (Primary PID: $primaryPid) at $timestamp")
            appendLine("─".repeat(50))
            if (status.isNotBlank() && !status.contains("No such file")) {
                appendLine(status.lines().filter { line ->
                    line.startsWith("VmRSS") || line.startsWith("VmPeak") ||
                    line.startsWith("Threads") || line.startsWith("VmSwap")
                }.joinToString("\n"))
            } else {
                appendLine("Unable to read memory status (process may have died).")
            }
            
            if (meminfo.isNotBlank() && !meminfo.contains("No such file")) {
                appendLine("─".repeat(50))
                appendLine("Smaps Rollup:")
                appendLine(meminfo)
            }
        }.trimEnd()
    }
}
