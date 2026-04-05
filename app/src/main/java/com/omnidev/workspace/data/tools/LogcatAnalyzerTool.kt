package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Captures and analyzes Android logcat output precisely filtered to a specific package.
 *
 * * FIXES & UPGRADES:
 * 1. Process ID (PID) Resolution: Android logcat does NOT print package names on every line
 * of a Stacktrace. We must resolve the package to its PID first to get full crash logs.
 * 2. Tail-Reading: Grabs logs from the bottom up to ensure the newest crashes are captured 
 * before token limits are hit.
 * 3. Strict Privilege Routing: Android 13+ blocks normal apps from reading cross-app logcat.
 * This now strictly routes through the PrivilegedExecutionManager.
 */
object LogcatAnalyzerTool {

    /** Maximum characters returned in a single result to protect the LLM context window. */
    private const val MAX_OUTPUT_CHARS = 12_000

    // ──────────────────────────────────────────────
    //  Tool Definitions
    // ──────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "analyze_logcat",
            description = "Capture and analyze Android system logs (logcat) for a specific app. " +
                "CRITICAL for debugging app crashes, ANRs, or runtime errors. " +
                "Automatically resolves the package to its PID to capture full multi-line stacktraces.",
            parameters = listOf(
                ToolParameter(
                    name = "packageName",
                    type = "string",
                    description = "Exact package name to debug (e.g., 'com.example.app').",
                    required = true
                ),
                ToolParameter(
                    name = "lines",
                    type = "string",
                    description = "Number of recent log lines to fetch (default: 500).",
                    required = false
                ),
                ToolParameter(
                    name = "severity",
                    type = "string",
                    description = "Filter level: 'crash' (FATAL only), 'error' (E/), 'warning' (W/ & E/), 'all' (no filter). Default: 'error'.",
                    required = false
                )
            )
        )
    )

    // ──────────────────────────────────────────────
    //  Execution Router
    // ──────────────────────────────────────────────

    suspend fun executeTool(
        name: String,
        arguments: Map<String, String>
    ): ToolExecutionResult {
        if (name != "analyze_logcat") {
            return ToolExecutionResult("Unknown tool: $name", isError = true)
        }

        val packageName = arguments["packageName"]?.trim()
            ?: return ToolExecutionResult("Missing required parameter: packageName", isError = true)

        val lines = arguments["lines"]?.toIntOrNull() ?: 500
        val severity = arguments["severity"]?.trim() ?: "error"

        return execute(packageName, lines, severity)
    }

    // ──────────────────────────────────────────────
    //  Core Logic
    // ──────────────────────────────────────────────

    suspend fun execute(
        packageName: String,
        lines: Int = 500,
        severity: String = "error"
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            // 1. Ensure Privileged Access (Required for Android 13+)
            if (!PrivilegedExecutionManager.isShizukuReady() && !PrivilegedExecutionManager.isRishReady() && !PrivilegedExecutionManager.isRootAvailable()) {
                return@withContext ToolExecutionResult(
                    "Logcat analysis failed: Privileged access (Shizuku/Root) is required on modern Android to read cross-app logs.",
                    isError = true
                )
            }

            // 2. Resolve Package to PIDs
            // Apps can have multiple processes (e.g., com.app and com.app:service). We need all their PIDs.
            val pids = getProcessIdsForPackage(packageName)
            
            // 3. Fetch Logs
            val rawLines = if (pids.isEmpty()) {
                // App is not running. Fallback to a dumb text-grep across the whole logcat history
                // Note: This misses multi-line stacktraces because 'grep' only matches the exact line.
                fetchLogsByTextGrep(packageName, lines)
            } else {
                // App is running. Use proper PID filtering to get intact stacktraces.
                fetchLogsByPids(pids, lines)
            }

            if (rawLines.isEmpty()) {
                return@withContext ToolExecutionResult(
                    "No logs found for $packageName. The app might not have logged anything recently, or the buffer has rolled over."
                )
            }

            // 4. Apply Severity Filtering
            val filteredLines = filterBySeverity(rawLines, severity)

            if (filteredLines.isEmpty()) {
                return@withContext ToolExecutionResult(
                    "No logs matching severity '$severity' found for $packageName in the recent buffer."
                )
            }

            // 5. Format and Truncate (Keeping the tail/newest logs)
            val joined = filteredLines.joinToString("\n")
            val truncated = joined.length > MAX_OUTPUT_CHARS
            
            val output = if (truncated) {
                "...[TRUNCATED ${joined.length - MAX_OUTPUT_CHARS} chars to save tokens]...\n" + joined.takeLast(MAX_OUTPUT_CHARS)
            } else {
                joined
            }

            val header = "── Logcat Analysis: $packageName (PIDs: ${if (pids.isEmpty()) "Not Running" else pids.joinToString()}) ──\n"
            
            ToolExecutionResult(output = header + output, isError = false, truncated = truncated)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult("Failed to analyze logcat: ${e.message}", isError = true)
        }
    }

    // ──────────────────────────────────────────────
    //  Private Helpers
    // ──────────────────────────────────────────────

    /**
     * Finds all active Process IDs (PIDs) associated with a given package name.
     */
    private suspend fun getProcessIdsForPackage(packageName: String): List<String> {
        val safePackage = packageName.replace(Regex("[^a-zA-Z0-9._]"), "")
        val result = PrivilegedExecutionManager.executeCommand("pidof $safePackage")
        
        return if (result.isSuccess) {
            result.getOrDefault("").split(Regex("\\s+")).filter { it.isNotBlank() && it.all { char -> char.isDigit() } }
        } else {
            emptyList()
        }
    }

    /**
     * Highly accurate logcat fetch using process IDs. Preserves stacktraces completely.
     */
    private suspend fun fetchLogsByPids(pids: List<String>, maxLines: Int): List<String> {
        // Build a regex pattern for grep: "^\d+ +\d+ +\d+ +\d+ +(PID1|PID2) "
        // This matches the PID column in standard logcat output reliably
        val pidRegex = pids.joinToString("|")
        val safeLines = maxLines.coerceIn(100, 5000)
        
        // We dump a larger chunk of logcat, grep the PIDs, and then take the tail
        val cmd = "logcat -d -v threadtime -t ${safeLines * 3} | grep -E '^\\S+\\s+\\S+\\s+($pidRegex)\\s+' | tail -n $safeLines"
        
        val result = PrivilegedExecutionManager.executeCommand(cmd)
        return result.getOrDefault("").lines().filter { it.isNotBlank() }
    }

    /**
     * Fallback for when the app is dead/crashed and we don't have its PID.
     * We just grep the text. Stacktraces might be broken here, but it's better than nothing.
     */
    private suspend fun fetchLogsByTextGrep(packageName: String, maxLines: Int): List<String> {
        val safePackage = packageName.replace(Regex("[^a-zA-Z0-9._]"), "")
        val safeLines = maxLines.coerceIn(100, 5000)
        
        val cmd = "logcat -d -v threadtime -t ${safeLines * 3} | grep -F '$safePackage' | tail -n $safeLines"
        
        val result = PrivilegedExecutionManager.executeCommand(cmd)
        return result.getOrDefault("").lines().filter { it.isNotBlank() }
    }

    /**
     * Fast string-based severity filtering. Avoids heavy Regex inside loops.
     * Assumes standard `threadtime` format: "MM-DD HH:MM:SS.mmm PID TID LEVEL/Tag: Message"
     */
    private fun filterBySeverity(lines: List<String>, severity: String): List<String> {
        val targetSeverity = severity.lowercase().trim()
        
        if (targetSeverity == "all") return lines

        return lines.filter { line ->
            // In 'threadtime', the 5th token is usually "LEVEL/Tag" (e.g., "E/AndroidRuntime:")
            // A fast heuristic is checking if " E/" or " F/" exists, or doing a basic split.
            val isCrash = line.contains("FATAL EXCEPTION", ignoreCase = true) || line.contains("E/AndroidRuntime")
            
            when (targetSeverity) {
                "crash" -> isCrash
                "error" -> isCrash || line.contains(" E/") || line.contains(" F/")
                "warning" -> isCrash || line.contains(" E/") || line.contains(" F/") || line.contains(" W/")
                else -> isCrash || line.contains(" E/") // Default to error
            }
        }
    }
}
