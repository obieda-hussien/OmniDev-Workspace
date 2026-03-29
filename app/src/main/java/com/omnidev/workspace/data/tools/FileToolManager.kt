package com.omnidev.workspace.data.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Production implementation of [ToolManager] providing token-optimized file operations.
 *
 * Every file path argument is canonicalized and validated against the user's active
 * [scopePath] to prevent unintended reads/writes outside the project boundary.
 *
 * Tools implemented:
 * - `read_file_lines`: Read specific line range from a file (avoids loading entire files).
 * - `search_codebase`: Regex search across a directory tree with line-level snippets.
 * - `patch_file_content`: Find-and-replace within a file without rewriting the whole document.
 * - `create_file`: Create a new file with content.
 * - `delete_file`: Delete a file.
 * - `run_terminal`: Execute a shell command in the Target Context directory.
 * - `python_runner`: Run inline Python code or execute a Python file.
 * - `web_search`: Search the web using DuckDuckGo Lite and return the top results.
 *
 * File-modifying operations (`patch_file_content`, `create_file`, `delete_file`) generate a
 * human-readable preview and, when [confirmationGate] is set, suspend until the user approves
 * or denies the change. This enables the Visual Code Diff Viewer confirmation flow.
 */
class FileToolManager(
    /**
     * When true, the scope-restriction check in [validateScope] is bypassed.
     * This enables the agent to read/write anywhere on the filesystem.
     *
     * **SAFETY**: God Mode actions must be gated behind
     * [com.omnidev.workspace.ui.chat.ConfirmationGate] in the agent pipeline —
     * the agent cannot use God Mode operations without explicit user approval.
     *
     * This flag is updated at runtime by [ChatViewModel] when the user toggles
     * God Mode in Settings.
     */
    @Volatile var godModeEnabled: Boolean = false
) : ToolManager {

    /**
     * Optional gate for file-modifying operations.
     *
     * When set, `patch_file_content`, `create_file`, and `delete_file` will suspend and
     * call this lambda with a human-readable [preview] string and an optional [diffContent]
     * unified-diff string (null for deletions). The lambda must return `true` to approve
     * the change or `false` to deny it (in which case the tool returns an error result).
     *
     * This is wired from [ChatViewModel] using a [kotlinx.coroutines.CompletableDeferred]
     * to bridge the coroutine suspension to the Compose confirmation dialog.
     */
    @Volatile var confirmationGate: (suspend (preview: String, diffContent: String?) -> Boolean)? = null

    companion object {
        /** Maximum lines returned from a single read to guard context window usage. */
        private const val MAX_READ_LINES = 500

        /** Maximum number of search result matches to return. */
        private const val MAX_SEARCH_RESULTS = 50

        /** Maximum file size (in bytes) that can be created via create_file. */
        private const val MAX_CREATE_FILE_SIZE = 1_048_576L // 1 MB

        /** Timeout in seconds for shell commands executed via run_terminal. */
        private const val TERMINAL_TIMEOUT_SECONDS = 60L

        /** Maximum characters captured from a terminal command's combined stdout/stderr. */
        private const val MAX_TERMINAL_OUTPUT_CHARS = 8_000

        /** Maximum Python inline code length accepted by python_runner. */
        private const val MAX_PYTHON_CODE_CHARS = 20_000
    }

    // ──────────────────────────────────────────────
    //  Tool Definitions (for AI function-calling schema)
    // ──────────────────────────────────────────────

    override fun getToolDefinitions(): List<ToolDefinition> = buildList {
        addAll(listOf(
        ToolDefinition(
            name = "read_file_lines",
            description = "Read specific lines from a file. Use this instead of reading entire files to save tokens. " +
                "Returns the content between startLine and endLine (1-indexed, inclusive).",
            parameters = listOf(
                ToolParameter("filePath", "string", "Absolute path to the file to read.", required = true),
                ToolParameter("startLine", "integer", "First line to read (1-indexed).", required = true),
                ToolParameter("endLine", "integer", "Last line to read (1-indexed, inclusive).", required = true)
            )
        ),
        ToolDefinition(
            name = "search_codebase",
            description = "Search for a regex pattern across all files in a directory tree. " +
                "Returns matching file paths and line-level snippets with line numbers.",
            parameters = listOf(
                ToolParameter("directory", "string", "Absolute directory path to search in.", required = true),
                ToolParameter("regexPattern", "string", "Java-compatible regex pattern to match.", required = true)
            )
        ),
        ToolDefinition(
            name = "patch_file_content",
            description = "Modify a file by replacing a specific snippet with new content. " +
                "The searchSnippet must match exactly one location in the file.",
            parameters = listOf(
                ToolParameter("filePath", "string", "Absolute path to the file to modify.", required = true),
                ToolParameter("searchSnippet", "string", "Exact text to find in the file.", required = true),
                ToolParameter("replaceSnippet", "string", "Replacement text.", required = true)
            )
        ),
        ToolDefinition(
            name = "create_file",
            description = "Create a new file with the specified content. Fails if the file already exists.",
            parameters = listOf(
                ToolParameter("filePath", "string", "Absolute path for the new file.", required = true),
                ToolParameter("content", "string", "Content to write to the file.", required = true)
            )
        ),
        ToolDefinition(
            name = "delete_file",
            description = "Delete a file. Fails if the path points to a directory.",
            parameters = listOf(
                ToolParameter("filePath", "string", "Absolute path to the file to delete.", required = true)
            )
        ),
        ToolDefinition(
            name = "run_terminal",
            description = "Execute a shell command inside the project's Target Context directory. " +
                "Returns combined stdout and stderr with the exit code. " +
                "Use for builds (./gradlew assembleDebug), tests (./gradlew test), " +
                "file listing (ls -la), or git operations (git status, git log --oneline -5).",
            parameters = listOf(
                ToolParameter(
                    name = "command",
                    type = "string",
                    description = "Shell command to execute (e.g., './gradlew build', 'ls -la', 'git status').",
                    required = true
                )
            )
        ),
        ToolDefinition(
            name = "web_search",
            description = "Search the web using DuckDuckGo and return the top results with titles, URLs, " +
                "and snippets. Use this to look up documentation, error messages, library usage, or " +
                "any information not available in the local codebase.",
            parameters = listOf(
                ToolParameter(
                    name = "query",
                    type = "string",
                    description = "The search query (e.g., 'Kotlin coroutines StateFlow example').",
                    required = true
                )
            )
        ),
        ToolDefinition(
            name = "python_runner",
            description = "Run Python code or execute a Python file inside the current Target Context. " +
                "Use mode='inline' with `code`, or mode='file' with `filePath`. " +
                "Optional `args` are passed to the script in file mode.",
            parameters = listOf(
                ToolParameter("mode", "string", "Execution mode: 'inline' or 'file'.", required = true),
                ToolParameter("code", "string", "Python source code for mode='inline'.", required = false),
                ToolParameter("filePath", "string", "Path to Python file for mode='file'.", required = false),
                ToolParameter("args", "string", "Optional command-line args for mode='file'.", required = false)
            )
        )
        ))

        // ── God Mode extended file operations ────────────────────────────────
        // Only exposed when godModeEnabled == true to avoid polluting the agent's
        // tool list under normal operation.
        if (godModeEnabled) {
            addAll(listOf(
                ToolDefinition(
                    name = "god_read_file",
                    description = "[GOD MODE] Read any file on the Android filesystem, including protected " +
                        "paths like /data/data/, /system/, /proc/. Automatically routes through " +
                        "Shizuku/rish/root when required. Returns raw UTF-8 content.",
                    parameters = listOf(
                        ToolParameter("filePath", "string", "Absolute path to the file to read.", required = true)
                    )
                ),
                ToolDefinition(
                    name = "god_write_file",
                    description = "[GOD MODE] Write content to any file on the Android filesystem, including " +
                        "protected paths. Parent directories are created automatically. " +
                        "Automatically escalates through Shizuku/rish/root when needed.",
                    parameters = listOf(
                        ToolParameter("filePath", "string", "Absolute path to the target file.", required = true),
                        ToolParameter("content", "string", "UTF-8 text content to write.", required = true),
                        ToolParameter("append", "string", "Set to 'true' to append instead of overwrite.", required = false)
                    )
                ),
                ToolDefinition(
                    name = "god_delete_file",
                    description = "[GOD MODE] Delete any file or directory on the filesystem, including " +
                        "protected paths. Escalates through Shizuku/rish/root as needed.",
                    parameters = listOf(
                        ToolParameter("filePath", "string", "Absolute path to the file/directory.", required = true),
                        ToolParameter("recursive", "string", "Set to 'true' for recursive directory delete.", required = false)
                    )
                ),
                ToolDefinition(
                    name = "god_copy_file",
                    description = "[GOD MODE] Copy a file from any source path to any destination path, " +
                        "including privileged paths. Uses 'cp -p' via shell when necessary.",
                    parameters = listOf(
                        ToolParameter("sourcePath", "string", "Absolute path of the source file.", required = true),
                        ToolParameter("destPath", "string", "Absolute path of the destination.", required = true)
                    )
                ),
                ToolDefinition(
                    name = "god_list_directory",
                    description = "[GOD MODE] List directory contents at any path including privileged " +
                        "directories. Uses 'ls -la' via shell when direct listing fails.",
                    parameters = listOf(
                        ToolParameter("path", "string", "Absolute directory path to list.", required = true)
                    )
                ),
                ToolDefinition(
                    name = "god_stat_file",
                    description = "[GOD MODE] Get detailed stat info (permissions, owner, size, timestamps) " +
                        "for any file, including in privileged paths.",
                    parameters = listOf(
                        ToolParameter("path", "string", "Absolute path to stat.", required = true)
                    )
                )
            ))
        }
    }

    // ──────────────────────────────────────────────
    //  Tool Execution Router
    // ──────────────────────────────────────────────

    override suspend fun executeTool(
        name: String,
        arguments: Map<String, String>,
        scopePath: String? // <--- FIX: Added ? to match interface
    ): ToolExecutionResult {
    val safeScopePath = scopePath ?: "" 
        return try {
            when (name) {
                // ── God Mode extended file operations ────────────────────────────
                // These are only surfaced when godModeEnabled == true; otherwise
                // they fall through to the standard file tools below.
                "god_read_file" -> {
                    val path = arguments["filePath"]
                        ?: return ToolExecutionResult("Missing required argument: filePath", isError = true)
                    GodModeFileRouter.readFile(path, godMode = godModeEnabled).toToolResult()
                }
                "god_write_file" -> {
                    val path = arguments["filePath"]
                        ?: return ToolExecutionResult("Missing required argument: filePath", isError = true)
                    val content = arguments["content"]
                        ?: return ToolExecutionResult("Missing required argument: content", isError = true)
                    val append = arguments["append"]?.equals("true", ignoreCase = true) ?: false
                    GodModeFileRouter.writeFile(path, content, append, godMode = godModeEnabled).toToolResult()
                }
                "god_delete_file" -> {
                    val path = arguments["filePath"]
                        ?: return ToolExecutionResult("Missing required argument: filePath", isError = true)
                    val recursive = arguments["recursive"]?.equals("true", ignoreCase = true) ?: false
                    GodModeFileRouter.deleteFile(path, recursive, godMode = godModeEnabled).toToolResult()
                }
                "god_copy_file" -> {
                    val src = arguments["sourcePath"]
                        ?: return ToolExecutionResult("Missing required argument: sourcePath", isError = true)
                    val dst = arguments["destPath"]
                        ?: return ToolExecutionResult("Missing required argument: destPath", isError = true)
                    GodModeFileRouter.copyFile(src, dst, godMode = godModeEnabled).toToolResult()
                }
                "god_list_directory" -> {
                    val path = arguments["path"]
                        ?: return ToolExecutionResult("Missing required argument: path", isError = true)
                    GodModeFileRouter.listDirectory(path, godMode = godModeEnabled).toToolResult()
                }
                "god_stat_file" -> {
                    val path = arguments["path"]
                        ?: return ToolExecutionResult("Missing required argument: path", isError = true)
                    GodModeFileRouter.statFile(path, godMode = godModeEnabled).toToolResult()
                }
                // ─────────────────────────────────────────────────────────────────

                "read_file_lines" -> readFileLines(arguments, scopePath)
                "search_codebase" -> searchCodebase(arguments, scopePath)
                "patch_file_content" -> patchFileContent(arguments, scopePath)
                "create_file" -> createFile(arguments, scopePath)
                "delete_file" -> deleteFile(arguments, scopePath)
                "run_terminal" -> runTerminal(arguments, scopePath)
                "python_runner" -> runPython(arguments, scopePath)
                "web_search" -> webSearch(arguments["query"]
                    ?: return ToolExecutionResult("Missing required argument: query", isError = true)
                )
                else -> ToolExecutionResult(
                    output = "Unknown tool: $name",
                    isError = true
                )
            }
        } catch (e: SecurityException) {
            ToolExecutionResult(output = "SCOPE VIOLATION: ${e.message}", isError = true)
        } catch (e: IOException) {
            ToolExecutionResult(output = "IO Error: ${e.message}", isError = true)
        } catch (e: IllegalArgumentException) {
            ToolExecutionResult(output = "Invalid argument: ${e.message}", isError = true)
        }
    }

    // ──────────────────────────────────────────────
    //  Unified diff generation helper
    // ──────────────────────────────────────────────

    /**
     * Produces a minimal unified-diff string between [oldText] and [newText].
     *
     * The implementation uses a simple LCS-based approach: it finds the first
     * changed line and the last changed line, then emits a single hunk covering
     * that range with 3 lines of context on either side (like `diff -u`).
     *
     * For very large files where only a small section changes this keeps the
     * diff short enough to display in the confirmation dialog without scrolling
     * excessively. If the files are identical the method returns an empty string.
     */
    private fun generateUnifiedDiff(filePath: String, oldText: String, newText: String): String {
        val oldLines = oldText.lines()
        val newLines = newText.lines()

        // Find first and last differing line (0-indexed)
        var firstDiff = -1
        for (i in 0 until minOf(oldLines.size, newLines.size)) {
            if (oldLines[i] != newLines[i]) { firstDiff = i; break }
        }
        if (firstDiff == -1 && oldLines.size == newLines.size) return "" // identical
        if (firstDiff == -1) firstDiff = minOf(oldLines.size, newLines.size)

        var lastDiffOld = oldLines.size - 1
        var lastDiffNew = newLines.size - 1
        while (lastDiffOld > firstDiff && lastDiffNew > firstDiff &&
            oldLines[lastDiffOld] == newLines[lastDiffNew]) {
            lastDiffOld--; lastDiffNew--
        }

        val ctx = 3
        val oldStart = maxOf(0, firstDiff - ctx)
        val oldEnd   = minOf(oldLines.lastIndex, lastDiffOld + ctx)
        val newStart = maxOf(0, firstDiff - ctx)
        val newEnd   = minOf(newLines.lastIndex, lastDiffNew + ctx)

        val oldCount = oldEnd - oldStart + 1
        val newCount = newEnd - newStart + 1

        // Use only the filename for the header (matches standard git diff output)
        val fileName = filePath.substringAfterLast('/').ifEmpty { filePath }

        return buildString {
            appendLine("--- a/$fileName")
            appendLine("+++ b/$fileName")
            appendLine("@@ -${oldStart + 1},$oldCount +${newStart + 1},$newCount @@")

            // Context before
            for (i in oldStart until firstDiff) appendLine(" ${oldLines[i]}")
            // Deletions
            for (i in firstDiff..lastDiffOld) appendLine("-${oldLines[i]}")
            // Additions
            for (i in firstDiff..lastDiffNew) appendLine("+${newLines[i]}")
            // Context after — taken from newLines to show the post-change state
            for (i in (lastDiffNew + 1)..minOf(newLines.lastIndex, lastDiffNew + ctx)) {
                appendLine(" ${newLines[i]}")
            }
        }.trimEnd()
    }

    // ──────────────────────────────────────────────
    //  read_file_lines
    // ──────────────────────────────────────────────

    /**
     * Reads lines [startLine]..[endLine] (1-indexed, inclusive) from a file.
     * Never loads more than [MAX_READ_LINES] at once to protect the context window.
     *
     * When [godModeEnabled] is true and the path is in a privileged namespace
     * (e.g., `/data/data/`, `/system/`), the full file is fetched via
     * [GodModeFileRouter] first, then the requested line range is sliced in-memory.
     *
     * Declared `suspend` to allow internal God Mode coroutine calls.
     */
    private suspend fun readFileLines(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val filePath = normalizePath(requireArg(args, "filePath"), scopePath)
        val startLine = requireArg(args, "startLine").toIntOrNull()
            ?: return ToolExecutionResult("startLine must be an integer.", isError = true)
        val endLine = requireArg(args, "endLine").toIntOrNull()
            ?: return ToolExecutionResult("endLine must be an integer.", isError = true)

        validateScope(filePath, scopePath)

        // ── God Mode privileged read ──────────────────────────────────────────────
        if (godModeEnabled) {
            val godResult = GodModeFileRouter.readFile(filePath, godMode = true)
            if (godResult is GodModeResult.Success) {
                val allLines = godResult.content.lines()
                val effectiveEnd = minOf(endLine, startLine + MAX_READ_LINES - 1, allLines.size)
                val slice = allLines.subList((startLine - 1).coerceAtLeast(0), effectiveEnd)
                    .mapIndexed { idx, line -> "${startLine + idx}: $line" }
                val escalationNote = if (godResult.escalated) "\n[🔓 Read via privileged shell]" else ""
                val header = "--- $filePath (lines $startLine–$effectiveEnd) ---"
                return ToolExecutionResult(
                    output = "$header\n${slice.joinToString("\n")}$escalationNote"
                )
            }
            // If GodModeFileRouter returned a non-Success for a privileged path,
            // fall through to the standard java.io.File path — it may still work
            // (e.g., the app owns the file) or produce a clean error.
        }
        // ─────────────────────────────────────────────────────────────────────────

        val file = File(filePath)
        if (!file.exists()) return ToolExecutionResult("File not found: $filePath", isError = true)
        if (!file.isFile) return ToolExecutionResult("Not a file: $filePath", isError = true)

        if (startLine < 1 || endLine < startLine) {
            return ToolExecutionResult(
                "Invalid line range: $startLine..$endLine (must be 1-indexed, startLine <= endLine).",
                isError = true
            )
        }

        val requestedCount = endLine - startLine + 1
        val effectiveEnd = if (requestedCount > MAX_READ_LINES) startLine + MAX_READ_LINES - 1 else endLine

        val lines = file.useLines { sequence ->
            sequence.drop(startLine - 1)
                .take(effectiveEnd - startLine + 1)
                .mapIndexed { idx, line -> "${startLine + idx}: $line" }
                .toList()
        }

        if (lines.isEmpty()) {
            return ToolExecutionResult("No lines found in range $startLine..$effectiveEnd (file may be shorter).")
        }

        val truncated = requestedCount > MAX_READ_LINES
        val header = "--- $filePath (lines $startLine–${startLine + lines.size - 1}) ---"
        val footer = if (truncated) "\n[TRUNCATED: Showing $MAX_READ_LINES of $requestedCount requested lines]" else ""

        return ToolExecutionResult(
            output = "$header\n${lines.joinToString("\n")}$footer",
            truncated = truncated
        )
    }

    // ──────────────────────────────────────────────
    //  search_codebase
    // ──────────────────────────────────────────────

    /**
     * Recursively searches [directory] for lines matching [regexPattern].
     * Returns at most [MAX_SEARCH_RESULTS] matches with file path, line number, and snippet.
     */
    private fun searchCodebase(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val directory = normalizePath(requireArg(args, "directory"), scopePath)
        val pattern = requireArg(args, "regexPattern")

        validateScope(directory, scopePath)

        val dir = File(directory)
        if (!dir.exists() || !dir.isDirectory) {
            return ToolExecutionResult("Not a valid directory: $directory", isError = true)
        }

        val regex = try {
            Regex(pattern)
        } catch (e: Exception) {
            return ToolExecutionResult("Invalid regex pattern: ${e.message}", isError = true)
        }

        val results = mutableListOf<String>()
        dir.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .forEach { file ->
                if (results.size >= MAX_SEARCH_RESULTS) return@forEach
                try {
                    file.useLines { lines ->
                        lines.forEachIndexed { idx, line ->
                            if (results.size < MAX_SEARCH_RESULTS && regex.containsMatchIn(line)) {
                                val relativePath = file.relativeTo(dir).path
                                val truncatedLine = if (line.length > 200) line.take(200) + "..." else line
                                results.add("$relativePath:${idx + 1}: $truncatedLine")
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Skip binary or unreadable files silently
                }
            }

        if (results.isEmpty()) {
            return ToolExecutionResult("No matches found for pattern: $pattern")
        }

        val header = "--- Search results for /$pattern/ in $directory (${results.size} matches) ---"
        val truncationNote = if (results.size >= MAX_SEARCH_RESULTS) "\n[TRUNCATED at $MAX_SEARCH_RESULTS results]" else ""

        return ToolExecutionResult(
            output = "$header\n${results.joinToString("\n")}$truncationNote",
            truncated = results.size >= MAX_SEARCH_RESULTS
        )
    }

    // ──────────────────────────────────────────────
    //  patch_file_content
    // ──────────────────────────────────────────────

    /**
     * Replaces exactly one occurrence of [searchSnippet] in the file with [replaceSnippet].
     * Fails if zero or multiple occurrences are found to prevent ambiguous edits.
     *
     * When [confirmationGate] is set, a unified diff is generated and the gate is
     * suspended until the user approves or denies the change.
     *
     * When [godModeEnabled] is true, the file content is obtained (and later written back)
     * via [GodModeFileRouter] so that privileged paths are handled transparently.
     */
    private suspend fun patchFileContent(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val filePath = normalizePath(requireArg(args, "filePath"), scopePath)
        val searchSnippet = requireArg(args, "searchSnippet")
        val replaceSnippet = requireArg(args, "replaceSnippet")

        validateScope(filePath, scopePath)

        // ── God Mode: read via router so privileged files are accessible ──────────
        val content: String
        if (godModeEnabled) {
            val readResult = GodModeFileRouter.readFile(filePath, godMode = true)
            when (readResult) {
                is GodModeResult.Success -> content = readResult.content
                is GodModeResult.Failure -> return ToolExecutionResult(
                    output = "God Mode read failed before patch: ${readResult.reason}", isError = true)
                is GodModeResult.GodModeDisabled -> {
                    // Fall through to standard read below
                    val file = File(filePath)
                    if (!file.exists()) return ToolExecutionResult("File not found: $filePath", isError = true)
                    if (!file.isFile) return ToolExecutionResult("Not a file: $filePath", isError = true)
                    content = file.readText()
                }
            }
        } else {
            val file = File(filePath)
            if (!file.exists()) return ToolExecutionResult("File not found: $filePath", isError = true)
            if (!file.isFile) return ToolExecutionResult("Not a file: $filePath", isError = true)
            content = file.readText()
        }
        // ─────────────────────────────────────────────────────────────────────────

        // Legacy file reference kept for the non-God-Mode write path below.
        val file = File(filePath)
        val occurrences = content.windowed(searchSnippet.length, 1)
            .count { it == searchSnippet }

        return when {
            occurrences == 0 -> ToolExecutionResult(
                output = "Search snippet not found in $filePath. Verify the exact text.",
                isError = true
            )
            occurrences > 1 -> ToolExecutionResult(
                output = "Ambiguous: Found $occurrences occurrences of the snippet in $filePath. " +
                    "Provide a more unique search snippet.",
                isError = true
            )
            else -> {
                val newContent = content.replaceFirst(searchSnippet, replaceSnippet)

                // Ask for confirmation via diff viewer if a gate is registered
                val gate = confirmationGate
                if (gate != null) {
                    val diff = generateUnifiedDiff(filePath, content, newContent)
                    val preview = "File: $filePath"
                    val approved = gate.invoke(preview, diff.ifEmpty { null })
                    if (!approved) {
                        return ToolExecutionResult(
                            output = "User cancelled the file modification for $filePath.",
                            isError = true
                        )
                    }
                }

                // ── God Mode: write via router so privileged files can be patched ──
                if (godModeEnabled) {
                    val writeResult = GodModeFileRouter.writeFile(
                        path = filePath, content = newContent,
                        append = false, godMode = true
                    )
                    if (writeResult is GodModeResult.Failure) {
                        return ToolExecutionResult(
                            output = "God Mode write failed after patch: ${writeResult.reason}",
                            isError = true
                        )
                    }
                } else {
                    file.writeText(newContent)
                }
                // ─────────────────────────────────────────────────────────────────

                // Calculate affected line range for the response
                val linesBefore = content.substring(0, content.indexOf(searchSnippet)).count { it == '\n' } + 1
                val linesAffected = searchSnippet.count { it == '\n' } + 1
                val godNote = if (godModeEnabled) " [🔓 God Mode]" else ""
                ToolExecutionResult(
                    output = "✅ Patched $filePath — replaced $linesAffected line(s) starting at line $linesBefore.$godNote"
                )
            }
        }
    }

    // ──────────────────────────────────────────────
    //  create_file
    // ──────────────────────────────────────────────

    /**
     * Creates a new file with the specified content. Parent directories are created if needed.
     *
     * When [confirmationGate] is set, a diff-style preview of the new file content is shown
     * (all lines prefixed with `+`) and the gate suspends until the user approves.
     *
     * When [godModeEnabled] is true, the write is routed through [GodModeFileRouter] so
     * files in privileged namespaces can be created via the shell backend.
     */
    private suspend fun createFile(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val filePath = normalizePath(requireArg(args, "filePath"), scopePath)
        val content = requireArg(args, "content")

        validateScope(filePath, scopePath)

        if (content.length > MAX_CREATE_FILE_SIZE) {
            return ToolExecutionResult(
                output = "Content exceeds maximum file size of ${MAX_CREATE_FILE_SIZE / 1024}KB.",
                isError = true
            )
        }

        // Existence check — use God Mode router to stat privileged paths too.
        val fileExists = if (godModeEnabled) {
            val stat = GodModeFileRouter.statFile(filePath, godMode = true)
            stat is GodModeResult.Success && !stat.content.contains("No such file", ignoreCase = true)
        } else {
            File(filePath).exists()
        }

        if (fileExists) {
            return ToolExecutionResult(
                "File already exists: $filePath. Use patch_file_content to modify.",
                isError = true
            )
        }

        // Ask for confirmation via diff viewer if a gate is registered
        val gate = confirmationGate
        if (gate != null) {
            val diffPreview = buildString {
                appendLine("--- /dev/null")
                appendLine("+++ b/${filePath.substringAfterLast('/')}")
                appendLine("@@ -0,0 +1,${content.lines().size} @@")
                content.lines().forEach { line -> appendLine("+$line") }
            }.trimEnd()
            val preview = "New file: $filePath"
            val approved = gate.invoke(preview, diffPreview)
            if (!approved) {
                return ToolExecutionResult(
                    output = "User cancelled the file creation for $filePath.",
                    isError = true
                )
            }
        }

        // ── God Mode: write via router ────────────────────────────────────────────
        if (godModeEnabled) {
            return GodModeFileRouter.writeFile(
                path = filePath, content = content,
                append = false, godMode = true
            ).toToolResult()
        }
        // ─────────────────────────────────────────────────────────────────────────

        File(filePath).also { f ->
            f.parentFile?.mkdirs()
            f.writeText(content)
        }
        return ToolExecutionResult("✅ Created file: $filePath (${content.length} bytes)")
    }

    // ──────────────────────────────────────────────
    //  delete_file
    // ──────────────────────────────────────────────

    /**
     * Deletes a single file. Refuses to delete directories for safety.
     *
     * When [confirmationGate] is set, a diff-style preview showing all lines being removed
     * (prefixed with `-`) is shown and the gate suspends until the user approves.
     *
     * When [godModeEnabled] is true, deletion is routed through [GodModeFileRouter] so
     * privileged files can be removed via the shell backend.
     */
    private suspend fun deleteFile(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val filePath = normalizePath(requireArg(args, "filePath"), scopePath)

        validateScope(filePath, scopePath)

        val file = File(filePath)

        // For non-God-Mode, validate file existence and type via standard Java I/O.
        if (!godModeEnabled) {
            if (!file.exists()) return ToolExecutionResult("File not found: $filePath", isError = true)
            if (file.isDirectory) return ToolExecutionResult("Cannot delete directory: $filePath", isError = true)
        }

        // Ask for confirmation if a gate is registered
        val gate = confirmationGate
        if (gate != null) {
            val existingContent = if (godModeEnabled) {
                when (val r = GodModeFileRouter.readFile(filePath, godMode = true)) {
                    is GodModeResult.Success -> r.content
                    else -> ""
                }
            } else {
                try { file.readText() } catch (_: Exception) { "" }
            }
            val diffPreview = buildString {
                appendLine("--- a/${filePath.substringAfterLast('/')}")
                appendLine("+++ /dev/null")
                appendLine("@@ -1,${existingContent.lines().size} +0,0 @@")
                existingContent.lines().forEach { line -> appendLine("-$line") }
            }.trimEnd()
            val preview = "Delete file: $filePath"
            val approved = gate.invoke(preview, diffPreview)
            if (!approved) {
                return ToolExecutionResult(
                    output = "User cancelled the file deletion for $filePath.",
                    isError = true
                )
            }
        }

        // ── God Mode: delete via router ───────────────────────────────────────────
        if (godModeEnabled) {
            return GodModeFileRouter.deleteFile(
                path = filePath, recursive = false, godMode = true
            ).toToolResult()
        }
        // ─────────────────────────────────────────────────────────────────────────

        val deleted = file.delete()
        return if (deleted) {
            ToolExecutionResult("✅ Deleted file: $filePath")
        } else {
            ToolExecutionResult("Failed to delete file: $filePath", isError = true)
        }
    }

    // ──────────────────────────────────────────────
    //  run_terminal
    // ──────────────────────────────────────────────

    /**
     * Executes a shell command inside the user's active [scopePath] directory.
     *
     * Uses [ProcessBuilder] with the working directory pinned to [scopePath] so the AI
     * agent operates inside the correct project folder. stdout and stderr are merged and
     * captured. The process is killed if it exceeds [TERMINAL_TIMEOUT_SECONDS].
     *
     * Security note: the user explicitly set the Target Context, so executing commands
     * there is the intended use-case. The working directory is always forced to scopePath
     * regardless of any `cd` the command contains.
     */
    private suspend fun runTerminal(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val command = requireArg(args, "command")

        // When God Mode is enabled, allow execution from any accessible directory.
        // Fall back to /data/local/tmp if scopePath is missing or invalid.
        val workDir = if (godModeEnabled) {
            val requested = File(scopePath)
            if (requested.exists() && requested.isDirectory) requested
            else {
                // Attempt to create the standard temp dir. If it fails, the existing
                // `!workDir.exists()` check below will return an informative error.
                val fallback = File("/data/local/tmp")
                fallback.mkdirs() // result intentionally ignored — guarded below
                fallback
            }
        } else {
            File(scopePath)
        }

        if (!workDir.exists() || !workDir.isDirectory) {
            return ToolExecutionResult(
                output = "Target Context directory not found: $scopePath",
                isError = true
            )
        }

        return try {
            withContext(Dispatchers.IO) {
                // /bin/sh is always available on Android — intentional for this Android-only app.
                val process = ProcessBuilder("/bin/sh", "-c", command)
                    .directory(workDir)
                    .start() // separate stdout and stderr for structured output

                // Read stdout on a dedicated thread to prevent pipe-buffer deadlock.
                val stdoutBuffer = StringBuffer()
                val stderrBuffer = StringBuffer()
                val stdoutThread = Thread {
                    try {
                        process.inputStream.bufferedReader().use { reader ->
                            reader.lineSequence().forEach { line ->
                                if (stdoutBuffer.length < MAX_TERMINAL_OUTPUT_CHARS) {
                                    stdoutBuffer.appendLine(line)
                                }
                            }
                        }
                    } catch (_: Exception) { /* process killed — exit gracefully */ }
                }
                val stderrThread = Thread {
                    try {
                        process.errorStream.bufferedReader().use { reader ->
                            reader.lineSequence().forEach { line ->
                                if (stderrBuffer.length < MAX_TERMINAL_OUTPUT_CHARS) {
                                    stderrBuffer.appendLine(line)
                                }
                            }
                        }
                    } catch (_: Exception) { /* process killed — exit gracefully */ }
                }
                stdoutThread.start()
                stderrThread.start()

                // API-24-compatible timeout: run process.waitFor() on a wait thread,
                // then join() with a timeout (Thread.join(millis) has been API 1 since day 1).
                val waitThread = Thread {
                    try { process.waitFor() } catch (_: InterruptedException) { /* interrupted during timeout handling */ }
                }
                waitThread.start()
                waitThread.join(TERMINAL_TIMEOUT_SECONDS * 1000L)

                val completed = !waitThread.isAlive
                if (!completed) {
                    process.destroy() // SIGTERM — process.destroyForcibly() requires API 26
                    stdoutThread.interrupt()
                    stderrThread.interrupt()
                    return@withContext ToolExecutionResult(
                        output = "⏱ Command timed out after ${TERMINAL_TIMEOUT_SECONDS}s: $command",
                        isError = true
                    )
                }

                stdoutThread.join(2_000L) // wait for reader to drain (max 2s)
                stderrThread.join(2_000L)

                val exitCode = process.exitValue()
                val stdout = stdoutBuffer.toString().trimEnd()
                val stderr = stderrBuffer.toString().trimEnd()
                val stdoutTruncated = stdoutBuffer.length >= MAX_TERMINAL_OUTPUT_CHARS
                val stderrTruncated = stderrBuffer.length >= MAX_TERMINAL_OUTPUT_CHARS

                // Structured output: exitCode + stdout + stderr for self-verification
                val resultText = buildString {
                    appendLine("$ $command")
                    appendLine("[exit_code: $exitCode]")
                    appendLine("[stdout]")
                    if (stdout.isNotEmpty()) appendLine(stdout) else appendLine("(empty)")
                    if (stdoutTruncated) appendLine("[STDOUT TRUNCATED]")
                    appendLine("[stderr]")
                    if (stderr.isNotEmpty()) appendLine(stderr) else appendLine("(empty)")
                    if (stderrTruncated) appendLine("[STDERR TRUNCATED]")
                }

                ToolExecutionResult(output = resultText, isError = exitCode != 0)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult(
                output = "Failed to execute command '$command': ${e.message}",
                isError = true
            )
        }
    }

    // ──────────────────────────────────────────────
    //  python_runner
    // ──────────────────────────────────────────────

    /**
     * Executes Python using either inline code (`python -c`) or a Python file path.
     */
    private suspend fun runPython(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val mode = requireArg(args, "mode").lowercase()
        val workDir = File(scopePath)
        if (!workDir.exists() || !workDir.isDirectory) {
            return ToolExecutionResult(
                output = "Target Context directory not found: $scopePath",
                isError = true
            )
        }

        val pythonBin = detectPythonInterpreter(workDir)
            ?: return ToolExecutionResult(
                output = "Python interpreter not found. Install python3/python in the environment first.",
                isError = true
            )

        val command = when (mode) {
            "inline" -> {
                val code = args["code"]
                    ?: return ToolExecutionResult("Missing required argument: code", isError = true)
                if (code.length > MAX_PYTHON_CODE_CHARS) {
                    return ToolExecutionResult(
                        output = "Python code is too large (${code.length} chars). Max: $MAX_PYTHON_CODE_CHARS.",
                        isError = true
                    )
                }
                listOf(pythonBin, "-c", code)
            }
            "file" -> {
                val filePath = normalizePath(
                    args["filePath"] ?: return ToolExecutionResult("Missing required argument: filePath", isError = true),
                    scopePath
                )
                validateScope(filePath, scopePath)
                val scriptFile = File(filePath)
                if (!scriptFile.exists() || !scriptFile.isFile) {
                    return ToolExecutionResult("Python file not found: $filePath", isError = true)
                }
                val cliArgs = parseCommandLineArgs(args["args"].orEmpty())
                listOf(pythonBin, scriptFile.absolutePath) + cliArgs
            }
            else -> return ToolExecutionResult(
                output = "Invalid mode '$mode'. Use 'inline' or 'file'.",
                isError = true
            )
        }

        return executeProcess(command = command, workingDir = workDir)
    }

    private suspend fun detectPythonInterpreter(workDir: File): String? = withContext(Dispatchers.IO) {
        val candidates = listOf("python3", "python")
        candidates.firstOrNull { candidate ->
            try {
                val process = ProcessBuilder(candidate, "--version")
                    .directory(workDir)
                    .redirectErrorStream(true)
                    .start()
                val waiter = Thread { try { process.waitFor() } catch (_: InterruptedException) {} }
                waiter.start()
                waiter.join(2_000L)
                if (waiter.isAlive) {
                    process.destroy()
                    false
                } else {
                    process.exitValue() == 0
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    private suspend fun executeProcess(command: List<String>, workingDir: File): ToolExecutionResult {
        return try {
            withContext(Dispatchers.IO) {
                val process = ProcessBuilder(command)
                    .directory(workingDir)
                    .start()

                val stdoutBuffer = StringBuffer()
                val stderrBuffer = StringBuffer()

                val stdoutThread = Thread {
                    try {
                        process.inputStream.bufferedReader().use { reader ->
                            reader.lineSequence().forEach { line ->
                                if (stdoutBuffer.length < MAX_TERMINAL_OUTPUT_CHARS) {
                                    stdoutBuffer.appendLine(line)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
                val stderrThread = Thread {
                    try {
                        process.errorStream.bufferedReader().use { reader ->
                            reader.lineSequence().forEach { line ->
                                if (stderrBuffer.length < MAX_TERMINAL_OUTPUT_CHARS) {
                                    stderrBuffer.appendLine(line)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
                stdoutThread.start()
                stderrThread.start()

                val waitThread = Thread {
                    try { process.waitFor() } catch (_: InterruptedException) {}
                }
                waitThread.start()
                waitThread.join(TERMINAL_TIMEOUT_SECONDS * 1000L)

                if (waitThread.isAlive) {
                    process.destroy()
                    return@withContext ToolExecutionResult(
                        output = "⏱ Python command timed out after ${TERMINAL_TIMEOUT_SECONDS}s.",
                        isError = true
                    )
                }

                stdoutThread.join(2_000L)
                stderrThread.join(2_000L)

                val exitCode = process.exitValue()
                val stdout = stdoutBuffer.toString().trimEnd().ifEmpty { "(empty)" }
                val stderr = stderrBuffer.toString().trimEnd().ifEmpty { "(empty)" }
                ToolExecutionResult(
                    output = buildString {
                        appendLine("$ ${command.joinToString(" ")}")
                        appendLine("[exit_code: $exitCode]")
                        appendLine("[stdout]")
                        appendLine(stdout)
                        appendLine("[stderr]")
                        append(stderr)
                    },
                    isError = exitCode != 0
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult(output = "Failed to run python: ${e.message}", isError = true)
        }
    }

    private fun parseCommandLineArgs(rawArgs: String): List<String> {
        if (rawArgs.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaping = false

        for (ch in rawArgs.trim()) {
            if (escaping) {
                current.append(ch)
                escaping = false
                continue
            }
            if (ch == '\\') {
                escaping = true
                continue
            }
            if (quote != null && ch == quote) {
                quote = null
                continue
            }
            if (quote == null && (ch == '"' || ch == '\'')) {
                quote = ch
                continue
            }
            if (quote == null && ch.isWhitespace()) {
                if (current.isNotEmpty()) {
                    result += current.toString()
                    current.clear()
                }
                continue
            }
            current.append(ch)
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    // ──────────────────────────────────────────────
    //  web_search
    // ──────────────────────────────────────────────

    /**
     * Searches the web via DuckDuckGo Lite and returns the top results.
     *
     * Uses the text-only `lite.duckduckgo.com` endpoint — no JavaScript required.
     * Parses HTML with regex to extract result titles, redirect URLs (decoded from the
     * `uddg=` parameter), and snippet text.
     */
    private suspend fun webSearch(query: String): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = URL("https://lite.duckduckgo.com/lite/?q=$encoded&kl=us-en")

                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout    = 15_000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android; OmniDevWorkspace)")
                    setRequestProperty("Accept", "text/html")
                }

                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    conn.disconnect()
                    return@withContext ToolExecutionResult(
                        output = "Search request failed (HTTP $responseCode).",
                        isError = true
                    )
                }

                val html = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                conn.disconnect()

                val results = parseSearchResults(html, query)
                ToolExecutionResult(output = results)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolExecutionResult(
                    output = "Web search failed: ${e.message}",
                    isError = true
                )
            }
        }

    /**
     * Parses DuckDuckGo Lite HTML to extract the top search results.
     *
     * The lite page structure contains:
     *  - `<a class="result-link" href="//duckduckgo.com/l/?uddg=URL_ENCODED...">Title</a>`
     *  - `<td class="result-snippet">Snippet text</td>`
     *
     * **Note:** Regex-based HTML parsing is inherently fragile. If DuckDuckGo changes their
     * page structure, this method may return fewer results or empty results. The tool will
     * still succeed (non-error), returning a "No results found" message in that case.
     */
    private fun parseSearchResults(html: String, query: String): String {
        val linkPattern = Regex(
            """<a[^>]+class="result-link"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val snippetPattern = Regex(
            """<td[^>]+class="result-snippet"[^>]*>(.*?)</td>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val uddgPattern = Regex("""uddg=([^&"]+)""")

        val links    = linkPattern.findAll(html).toList()
        val snippets = snippetPattern.findAll(html).toList()

        if (links.isEmpty()) {
            return "No results found for: $query"
        }

        return buildString {
            appendLine("Web search results for: \"$query\"\n")
            links.take(5).forEachIndexed { i, match ->
                val rawHref = match.groupValues[1]
                val title = match.groupValues[2]
                    .replace(Regex("<[^>]+>"), "")  // strip any inner HTML tags
                    .replace("&amp;", "&")
                    .trim()

                // Decode the actual destination URL from DuckDuckGo's redirect wrapper.
                // If the pattern isn't found (e.g., structure changed), surface the raw href
                // with the DDG redirect prefix stripped so the agent still gets something useful.
                val destUrl = uddgPattern.find(rawHref)?.groupValues?.get(1)
                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    ?: rawHref.substringAfter("uddg=").ifEmpty { rawHref }
                val snippet = snippets.getOrNull(i)?.groupValues?.get(1)
                    ?.replace(Regex("<[^>]+>"), "")
                    ?.replace("&amp;", "&")
                    ?.trim() ?: ""

                appendLine("${i + 1}. **$title**")
                appendLine("   $destUrl")
                if (snippet.isNotEmpty()) appendLine("   $snippet")
                appendLine()
            }
        }.trimEnd()
    }

    // ──────────────────────────────────────────────
    //  Path Normalization & Scope Validation
    // ──────────────────────────────────────────────

    /**
     * Normalizes a file path argument received from the AI agent.
     *
     * The AI is told the scope root absolute path in the system prompt, so it should
     * use full absolute paths. However, if the AI passes a bare relative path (no
     * leading `/`, e.g. `app/src/Main.kt`), it is prepended with [scopePath] for
     * convenience. Paths that already start with [scopePath] are used as-is.
     * All other paths (absolute paths outside [scopePath]) are left unchanged and
     * will be rejected by [validateScope].
     */
    private fun normalizePath(filePath: String, scopePath: String): String {
        return when {
            filePath.startsWith(scopePath) -> filePath  // already absolute and in-scope
            !filePath.startsWith("/")      -> "$scopePath/$filePath"  // bare relative path
            else                           -> filePath  // absolute path, validateScope will verify
        }
    }

    /**
     * Validates that a resolved [filePath] falls within the allowed [scopePath].
     * Uses canonical path resolution to prevent traversal attacks (e.g., ../../etc/passwd).
     *
     * When [godModeEnabled] is true the check is skipped entirely, allowing the agent to
     * operate on any path. This must only ever be activated after user confirmation via
     * [com.omnidev.workspace.ui.chat.ConfirmationGate].
     *
     * @throws SecurityException if the file path escapes the scope (when God Mode is off).
     */
    private fun validateScope(filePath: String, scopePath: String) {
        if (godModeEnabled) return  // God Mode: skip scope enforcement

        val canonicalFile = File(filePath).canonicalPath
        val canonicalScope = File(scopePath).canonicalPath

        if (!canonicalFile.startsWith(canonicalScope)) {
            throw SecurityException(
                "Path '$filePath' resolves to '$canonicalFile' which is outside the " +
                    "allowed scope '$canonicalScope'. All file operations must stay within " +
                    "the user's active Target Context."
            )
        }
    }

    /**
     * Retrieves a required argument, failing with a clear message if absent.
     */
    private fun requireArg(args: Map<String, String>, name: String): String =
        args[name] ?: throw IllegalArgumentException("Missing required argument: $name")
}
