package com.omnidev.workspace.data.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Captures and analyzes Android logcat output filtered to a specific package.
 *
 * The tool dumps the in-memory logcat ring buffer, filters lines that mention
 * the requested package name, and further narrows to crash/error/warning
 * severity depending on the caller's request.
 *
 * When Shizuku is available and authorized the tool uses an elevated shell
 * for logcat access (useful on user-builds where per-app log isolation is
 * enforced). Otherwise it falls back to a standard [ProcessBuilder] call
 * which works on debug/eng builds and emulators.
 */
object LogcatAnalyzerTool {

    /** Maximum characters returned in a single result to guard context window usage. */
    private const val MAX_OUTPUT_CHARS = 8_000

    /** Timeout in seconds for the logcat dump process. */
    private const val LOGCAT_TIMEOUT_SECONDS = 15L

    // ──────────────────────────────────────────────
    //  Tool Definitions
    // ──────────────────────────────────────────────

    /**
     * Returns the tool definitions for inclusion in the AI function-calling schema.
     */
    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "analyze_logcat",
            description = "Capture and analyze Android logcat output for a specific package. " +
                "Filters for crashes (FATAL EXCEPTION) and errors. Useful for debugging runtime failures.",
            parameters = listOf(
                ToolParameter(
                    name = "packageName",
                    type = "string",
                    description = "Package name to filter logcat for (e.g., com.example.app)",
                    required = true
                ),
                ToolParameter(
                    name = "lastMinutes",
                    type = "string",
                    description = "Number of minutes of logs to capture (default: 5)",
                    required = false
                ),
                ToolParameter(
                    name = "severity",
                    type = "string",
                    description = "Minimum severity: 'crash', 'error', 'warning', 'all' (default: 'error')",
                    required = false
                )
            )
        )
    )

    // ──────────────────────────────────────────────
    //  Execution
    // ──────────────────────────────────────────────

    /**
     * Captures logcat output, filters it by [packageName] and [severity], and returns
     * a [ToolExecutionResult] with the matching lines.
     *
     * @param packageName Android application ID to filter for.
     * @param lastMinutes Approximate number of minutes of history to include. Used as a
     *                    hint — the actual buffer depth depends on the device.
     * @param severity    One of `"crash"`, `"error"`, `"warning"`, or `"all"`.
     * @return Filtered logcat output or a "no errors found" message.
     */
    suspend fun execute(
        packageName: String,
        lastMinutes: Int = 5,
        severity: String = "error"
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val rawOutput = captureLogcat(packageName, lastMinutes)
            val filteredLines = filterBySeverity(rawOutput, severity)

            if (filteredLines.isEmpty()) {
                return@withContext ToolExecutionResult(
                    output = "No errors found for $packageName in the last $lastMinutes minutes.",
                    isError = false
                )
            }

            val joined = filteredLines.joinToString("\n")
            val truncated = joined.length > MAX_OUTPUT_CHARS
            val output = if (truncated) {
                joined.take(MAX_OUTPUT_CHARS) + "\n[TRUNCATED]"
            } else {
                joined
            }

            ToolExecutionResult(output = output, isError = false, truncated = truncated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult(
                output = "Failed to capture logcat: ${e.message}",
                isError = true
            )
        }
    }

    // ──────────────────────────────────────────────
    //  Dispatcher
    // ──────────────────────────────────────────────

    /**
     * Dispatches a tool call by [name] with the given [arguments] map.
     *
     * @param name      Must be `"analyze_logcat"`.
     * @param arguments Key-value argument map from the AI model.
     * @return The result of the tool execution.
     */
    suspend fun executeTool(
        name: String,
        arguments: Map<String, String>
    ): ToolExecutionResult {
        if (name != "analyze_logcat") {
            return ToolExecutionResult(
                output = "Unknown tool: $name",
                isError = true
            )
        }

        val packageName = arguments["packageName"]
            ?: return ToolExecutionResult(
                output = "Missing required parameter: packageName",
                isError = true
            )

        val lastMinutes = arguments["lastMinutes"]?.toIntOrNull() ?: 5
        val severity = arguments["severity"] ?: "error"

        return execute(packageName, lastMinutes, severity)
    }

    // ──────────────────────────────────────────────
    //  Internal helpers
    // ──────────────────────────────────────────────

    /**
     * Captures the logcat ring-buffer dump filtered to lines containing [packageName].
     *
     * Attempts Shizuku first for elevated access (needed on user-builds); falls back
     * to a local [ProcessBuilder] invocation.
     */
    private suspend fun captureLogcat(packageName: String, lastMinutes: Int): List<String> {
        // Try Shizuku for elevated logcat access (e.g., user builds with log isolation).
        if (ShizukuCommandTool.isAvailable() && ShizukuCommandTool.hasPermission()) {
            // Sanitize inputs to prevent shell injection — only allow package-name-safe chars.
            val safePackage = packageName.replace(Regex("[^a-zA-Z0-9._]"), "")
            val safeMinutes = lastMinutes.coerceIn(1, 60)
            val result = ShizukuCommandTool.execute(
                "logcat -d -v threadtime -t '${safeMinutes}m' | grep '$safePackage'"
            )
            if (result is ShizukuResult.Success) {
                return result.output.lines().filter { it.isNotBlank() }
            }
        }

        // Fallback: standard ProcessBuilder (works on debug builds / emulators).
        return captureLogcatViaProcess(packageName)
    }

    /**
     * Runs `logcat -d -v threadtime` through [ProcessBuilder] and filters output
     * to lines containing [packageName].
     *
     * Uses the API-24-compatible timeout pattern: a dedicated wait-thread joined with
     * a millisecond timeout, followed by [Process.destroy] on timeout.
     */
    private fun captureLogcatViaProcess(packageName: String): List<String> {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime")
            .redirectErrorStream(true)
            .start()

        // Read output on a dedicated thread to prevent pipe-buffer deadlock.
        // StringBuffer is used for thread safety in case readerThread is still
        // draining after join() times out.
        val outputBuffer = StringBuffer()
        val readerThread = Thread {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    reader.lineSequence().forEach { line ->
                        if (outputBuffer.length < MAX_OUTPUT_CHARS * 2) {
                            outputBuffer.appendLine(line)
                        }
                    }
                }
            } catch (_: Exception) { /* process killed — exit gracefully */ }
        }
        readerThread.start()

        // API-24-compatible timeout: Thread.join(millis) has been available since API 1.
        val waitThread = Thread {
            try { process.waitFor() } catch (_: InterruptedException) { /* timeout handling */ }
        }
        waitThread.start()
        waitThread.join(LOGCAT_TIMEOUT_SECONDS * 1000L)

        val completed = !waitThread.isAlive
        if (!completed) {
            process.destroy()
            readerThread.interrupt()
        } else {
            readerThread.join(2_000L)
        }

        return outputBuffer.toString()
            .lines()
            .filter { it.contains(packageName) && it.isNotBlank() }
    }

    /**
     * Filters a list of logcat lines to only those matching the requested [severity].
     *
     * Severity levels (most → least restrictive):
     * - `"crash"` — FATAL EXCEPTION / `E/AndroidRuntime`
     * - `"error"` — any `E/` tag (includes crashes)
     * - `"warning"` — any `W/` or `E/` tag
     * - `"all"` — no filtering
     */
    private fun filterBySeverity(lines: List<String>, severity: String): List<String> {
        return when (severity.lowercase()) {
            "crash" -> lines.filter { line ->
                line.contains("FATAL EXCEPTION") || line.contains("E/AndroidRuntime")
            }
            "error" -> lines.filter { line ->
                line.contains("FATAL EXCEPTION") ||
                    line.contains("E/AndroidRuntime") ||
                    line.contains(" E/") || Regex(" E( |$)").containsMatchIn(line)
            }
            "warning" -> lines.filter { line ->
                line.contains("FATAL EXCEPTION") ||
                    line.contains("E/AndroidRuntime") ||
                    line.contains(" E/") || Regex(" E( |$)").containsMatchIn(line) ||
                    line.contains(" W/") || Regex(" W( |$)").containsMatchIn(line)
            }
            "all" -> lines
            else -> lines.filter { line ->
                line.contains(" E/") || Regex(" E( |$)").containsMatchIn(line) ||
                    line.contains("FATAL EXCEPTION") ||
                    line.contains("E/AndroidRuntime")
            }
        }
    }
}
