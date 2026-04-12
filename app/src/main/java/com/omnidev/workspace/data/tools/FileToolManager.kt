package com.omnidev.workspace.data.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import java.io.File
import java.io.IOException

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
 * - `multi_read`: Read multiple files in one call with optional line range.
 * - `multi_patch_file_content`: Apply multiple patch operations in one request.
 * - `delete_text`: Remove text occurrences from a file (all/first/last), optionally on one line.
 * - `clear_file`: Truncate all content from a file (file is preserved, just emptied).
 * - `delete_lines`: Delete a contiguous range of lines from a file.
 * - `insert_lines`: Insert text before a specific line number.
 * - `replace_lines`: Replace a line range with new content.
 * - `append_to_file`: Append text to the end of an existing file.
 * - `create_file`: Create a new file with content.
 * - `delete_file`: Delete a file.
 * - `run_terminal`: Execute a shell command in the Target Context directory.
 * - `python_runner`: Run inline Python code or execute a Python file.
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

        /** Maximum file size (in bytes) that can be read via read_file. */
        private const val MAX_FILE_READ_SIZE = 2_097_152L // 2 MB

        /** Maximum number of files allowed in multi_read operations. */
        private const val MAX_MULTI_READ_FILES = 20

        /** Maximum number of patch operations allowed in multi_patch_file_content. */
        private const val MAX_MULTI_PATCH_OPERATIONS = 20

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
                name = "multi_read",
                description = "Read multiple files in one call. filePaths is a JSON array of absolute or relative paths. " +
                    "Optionally specify startLine and endLine to read a line range from each file.",
                parameters = listOf(
                    ToolParameter("filePaths", "string", "JSON array of file paths to read.", required = true),
                    ToolParameter("startLine", "integer", "Optional first line to read (1-indexed).", required = false),
                    ToolParameter("endLine", "integer", "Optional last line to read (1-indexed, inclusive).", required = false)
                )
            ),
            ToolDefinition(
                name = "multi_patch_file_content",
                description = "Apply multiple patch operations in one request. operations is a JSON array of objects with filePath, searchSnippet, and replaceSnippet.",
                parameters = listOf(
                    ToolParameter("operations", "string", "JSON array of patch operations.", required = true)
                )
            ),
            ToolDefinition(
                name = "delete_text",
                description = "Remove occurrences of a text snippet from a file. " +
                    "Use occurrences='all' (default) to remove every match, 'first' to remove only the first, " +
                    "or 'last' to remove only the last. " +
                    "Provide lineNumber to restrict removal to a single line.",
                parameters = listOf(
                    ToolParameter("filePath", "string", "Absolute path to the file.", required = true),
                    ToolParameter("text", "string", "Exact text to remove.", required = true),
                    ToolParameter("occurrences", "string", "Which occurrences to remove: 'all' (default), 'first', or 'last'.", required = false),
                    ToolParameter("lineNumber", "integer", "Optional 1-indexed line number. When set, only removes from that line.", required = false)
                )
            ),
            ToolDefinition(
                name = "clear_file",
                description = "Clear (truncate) all content from a file, leaving it empty. The file itself is preserved.",
                parameters = listOf(
                    ToolParameter("filePath", "string", "Absolute path to the file to clear.", required = true)
                )
            ),
            ToolDefinition(
                name = "delete_lines",
                description = "Delete a contiguous range of lines from a file. Lines after the deleted range shift up. " +
                    "If endLine is omitted, only startLine is deleted.",
                parameters = listOf(
                    ToolParameter("filePath", "string", "Absolute path to the file.", required = true),
                    ToolParameter("startLine", "integer", "First line to delete (1-indexed).", required = true),
                    ToolParameter("endLine", "integer", "Last line to delete (1-indexed, inclusive). Defaults to startLine.", required = false)
                )
            ),
            ToolDefinition(
                name = "insert_lines",
                description = "Insert text before a specific line in a file. Existing lines at and after lineNumber are shifted down. " +
                    "Use lineNumber=1 to prepend; use a number greater than the last line to append.",
                parameters = listOf(
                    ToolParameter("filePath", "string", "Absolute path to the file.", required = true),
                    ToolParameter("content", "string", "Text to insert.", required = true),
                    ToolParameter("lineNumber", "integer", "1-indexed line before which to insert content.", required = true)
                )
            ),
            ToolDefinition(
                name = "replace_lines",
                description = "Replace a range of lines (startLine..endLine, inclusive) with new content. " +
                    "The replacement may have a different number of lines than the deleted range.",
                parameters = listOf(
                    ToolParameter("filePath", "string", "Absolute path to the file.", required = true),
                    ToolParameter("startLine", "integer", "First line to replace (1-indexed).", required = true),
                    ToolParameter("endLine", "integer", "Last line to replace (1-indexed, inclusive).", required = true),
                    ToolParameter("content", "string", "New content to replace the specified lines.", required = true)
                )
            ),
            ToolDefinition(
                name = "append_to_file",
                description = "Append text to the end of an existing file. " +
                    "Set addNewline='true' (default) to ensure a newline separates existing content from appended text.",
                parameters = listOf(
                    ToolParameter("filePath", "string", "Absolute path to the file.", required = true),
                    ToolParameter("content", "string", "Text to append.", required = true),
                    ToolParameter("addNewline", "string", "Prepend a newline before appended text if the file is non-empty. Default: 'true'.", required = false)
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
        scopePath: String?
    ): ToolExecutionResult {
        // Fallback to a safe temp directory if scopePath is not provided by the context
        val safeScopePath = if (scopePath.isNullOrBlank()) "/data/local/tmp" else scopePath 
        
        return try {
            when (name) {
                // ── God Mode extended file operations ────────────────────────────
                "god_read_file" -> {
                    val path = arguments["filePath"] ?: return ToolExecutionResult("Missing required argument: filePath", isError = true)
                    GodModeFileRouter.readFile(path, godMode = godModeEnabled).toToolResult()
                }
                "god_write_file" -> {
                    val path = arguments["filePath"] ?: return ToolExecutionResult("Missing required argument: filePath", isError = true)
                    val content = arguments["content"] ?: return ToolExecutionResult("Missing required argument: content", isError = true)
                    val append = arguments["append"]?.equals("true", ignoreCase = true) ?: false
                    GodModeFileRouter.writeFile(path, content, append, godMode = godModeEnabled).toToolResult()
                }
                "god_delete_file" -> {
                    val path = arguments["filePath"] ?: return ToolExecutionResult("Missing required argument: filePath", isError = true)
                    val recursive = arguments["recursive"]?.equals("true", ignoreCase = true) ?: false
                    GodModeFileRouter.deleteFile(path, recursive, godMode = godModeEnabled).toToolResult()
                }
                "god_copy_file" -> {
                    val src = arguments["sourcePath"] ?: return ToolExecutionResult("Missing required argument: sourcePath", isError = true)
                    val dst = arguments["destPath"] ?: return ToolExecutionResult("Missing required argument: destPath", isError = true)
                    GodModeFileRouter.copyFile(src, dst, godMode = godModeEnabled).toToolResult()
                }
                "god_list_directory" -> {
                    val path = arguments["path"] ?: return ToolExecutionResult("Missing required argument: path", isError = true)
                    GodModeFileRouter.listDirectory(path, godMode = godModeEnabled).toToolResult()
                }
                "god_stat_file" -> {
                    val path = arguments["path"] ?: return ToolExecutionResult("Missing required argument: path", isError = true)
                    GodModeFileRouter.statFile(path, godMode = godModeEnabled).toToolResult()
                }
                // ─────────────────────────────────────────────────────────────────

                "read_file_lines" -> readFileLines(arguments, safeScopePath)
                "search_codebase" -> searchCodebase(arguments, safeScopePath)
                "patch_file_content" -> patchFileContent(arguments, safeScopePath)
                "multi_read" -> multiRead(arguments, safeScopePath)
                "multi_patch_file_content" -> multiPatchFileContent(arguments, safeScopePath)
                "delete_text" -> deleteText(arguments, safeScopePath)
                "clear_file" -> clearFile(arguments, safeScopePath)
                "delete_lines" -> deleteLines(arguments, safeScopePath)
                "insert_lines" -> insertLines(arguments, safeScopePath)
                "replace_lines" -> replaceLines(arguments, safeScopePath)
                "append_to_file" -> appendToFile(arguments, safeScopePath)
                "create_file" -> createFile(arguments, safeScopePath)
                "delete_file" -> deleteFile(arguments, safeScopePath)
                "run_terminal" -> runTerminal(arguments, safeScopePath)
                "python_runner" -> runPython(arguments, safeScopePath)
                else -> ToolExecutionResult(output = "Unknown file tool: $name", isError = true)
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

    private fun generateUnifiedDiff(filePath: String, oldText: String, newText: String): String {
        val oldLines = oldText.lines()
        val newLines = newText.lines()

        var firstDiff = -1
        for (i in 0 until minOf(oldLines.size, newLines.size)) {
            if (oldLines[i] != newLines[i]) { firstDiff = i; break }
        }
        if (firstDiff == -1 && oldLines.size == newLines.size) return "" 
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

        val fileName = filePath.substringAfterLast('/').ifEmpty { filePath }

        return buildString {
            appendLine("--- a/$fileName")
            appendLine("+++ b/$fileName")
            appendLine("@@ -${oldStart + 1},$oldCount +${newStart + 1},$newCount @@")

            for (i in oldStart until firstDiff) appendLine(" ${oldLines[i]}")
            for (i in firstDiff..lastDiffOld) appendLine("-${oldLines[i]}")
            for (i in firstDiff..lastDiffNew) appendLine("+${newLines[i]}")
            for (i in (lastDiffNew + 1)..minOf(newLines.lastIndex, lastDiffNew + ctx)) {
                appendLine(" ${newLines[i]}")
            }
        }.trimEnd()
    }

    // ──────────────────────────────────────────────
    //  read_file_lines
    // ──────────────────────────────────────────────

    private suspend fun readFileLines(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val filePath = normalizePath(requireArg(args, "filePath"), scopePath)
        val startLine = requireArg(args, "startLine").toIntOrNull()
            ?: return ToolExecutionResult("startLine must be an integer.", isError = true)
        val endLine = requireArg(args, "endLine").toIntOrNull()
            ?: return ToolExecutionResult("endLine must be an integer.", isError = true)

        validateScope(filePath, scopePath)

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
        }

        val file = File(filePath)
        if (!file.exists()) return ToolExecutionResult("File not found: $filePath", isError = true)
        if (!file.isFile) return ToolExecutionResult("Not a file: $filePath", isError = true)

        if (startLine < 1 || endLine < startLine) {
            return ToolExecutionResult("Invalid line range: $startLine..$endLine (must be 1-indexed, startLine <= endLine).", isError = true)
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

        return ToolExecutionResult(output = "$header\n${lines.joinToString("\n")}$footer", truncated = truncated)
    }

    // ──────────────────────────────────────────────
    //  search_codebase
    // ──────────────────────────────────────────────

    private fun searchCodebase(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val directory = normalizePath(requireArg(args, "directory"), scopePath)
        val pattern = requireArg(args, "regexPattern")

        validateScope(directory, scopePath)

        val dir = File(directory)
        if (!dir.exists() || !dir.isDirectory) {
            return ToolExecutionResult("Not a valid directory: $directory", isError = true)
        }

        val regex = try { Regex(pattern) } catch (e: Exception) { return ToolExecutionResult("Invalid regex pattern: ${e.message}", isError = true) }

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
                } catch (_: Exception) {}
            }

        if (results.isEmpty()) return ToolExecutionResult("No matches found for pattern: $pattern")

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

    private suspend fun patchFileContent(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val filePath = normalizePath(requireArg(args, "filePath"), scopePath)
        val searchSnippet = requireArg(args, "searchSnippet")
        val replaceSnippet = requireArg(args, "replaceSnippet")

        validateScope(filePath, scopePath)

        val content: String
        if (godModeEnabled) {
            val readResult = GodModeFileRouter.readFile(filePath, godMode = true)
            when (readResult) {
                is GodModeResult.Success -> content = readResult.content
                is GodModeResult.Failure -> return ToolExecutionResult("God Mode read failed before patch: ${readResult.reason}", isError = true)
                is GodModeResult.GodModeDisabled -> {
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

        val file = File(filePath)
        val occurrences = content.windowed(searchSnippet.length, 1).count { it == searchSnippet }

        return when {
            occurrences == 0 -> ToolExecutionResult("Search snippet not found in $filePath. Verify the exact text.", isError = true)
            occurrences > 1 -> ToolExecutionResult("Ambiguous: Found $occurrences occurrences of the snippet in $filePath. Provide a more unique search snippet.", isError = true)
            else -> {
                val newContent = content.replaceFirst(searchSnippet, replaceSnippet)

                val gate = confirmationGate
                if (gate != null) {
                    val diff = generateUnifiedDiff(filePath, content, newContent)
                    val approved = gate.invoke("File: $filePath", diff.ifEmpty { null })
                    if (!approved) return ToolExecutionResult("User cancelled the file modification for $filePath.", isError = true)
                }

                if (godModeEnabled) {
                    val writeResult = GodModeFileRouter.writeFile(filePath, newContent, false, godMode = true)
                    if (writeResult is GodModeResult.Failure) {
                        return ToolExecutionResult("God Mode write failed after patch: ${writeResult.reason}", isError = true)
                    }
                } else {
                    file.writeText(newContent)
                }

                val linesBefore = content.substring(0, content.indexOf(searchSnippet)).count { it == '\n' } + 1
                val linesAffected = searchSnippet.count { it == '\n' } + 1
                val godNote = if (godModeEnabled) " [🔓 God Mode]" else ""
                ToolExecutionResult("✅ Patched $filePath — replaced $linesAffected line(s) starting at line $linesBefore.$godNote")
            }
        }
    }

    // ──────────────────────────────────────────────
    //  create_file
    // ──────────────────────────────────────────────

    private suspend fun createFile(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val filePath = normalizePath(requireArg(args, "filePath"), scopePath)
        val content = requireArg(args, "content")

        validateScope(filePath, scopePath)

        if (content.length > MAX_CREATE_FILE_SIZE) {
            return ToolExecutionResult("Content exceeds maximum file size of ${MAX_CREATE_FILE_SIZE / 1024}KB.", isError = true)
        }

        val fileExists = if (godModeEnabled) {
            val stat = GodModeFileRouter.statFile(filePath, godMode = true)
            stat is GodModeResult.Success && !stat.content.contains("No such file", ignoreCase = true)
        } else {
            File(filePath).exists()
        }

        if (fileExists) return ToolExecutionResult("File already exists: $filePath. Use patch_file_content to modify.", isError = true)

        val gate = confirmationGate
        if (gate != null) {
            val diffPreview = buildString {
                appendLine("--- /dev/null")
                appendLine("+++ b/${filePath.substringAfterLast('/')}")
                appendLine("@@ -0,0 +1,${content.lines().size} @@")
                content.lines().forEach { line -> appendLine("+$line") }
            }.trimEnd()
            val approved = gate.invoke("New file: $filePath", diffPreview)
            if (!approved) return ToolExecutionResult("User cancelled the file creation for $filePath.", isError = true)
        }

        if (godModeEnabled) {
            return GodModeFileRouter.writeFile(filePath, content, false, godMode = true).toToolResult()
        }

        File(filePath).also { f ->
            f.parentFile?.mkdirs()
            f.writeText(content)
        }
        return ToolExecutionResult("✅ Created file: $filePath (${content.length} bytes)")
    }

    // ──────────────────────────────────────────────
    //  delete_file
    // ──────────────────────────────────────────────

    private suspend fun deleteFile(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val filePath = normalizePath(requireArg(args, "filePath"), scopePath)

        validateScope(filePath, scopePath)

        val file = File(filePath)

        if (!godModeEnabled) {
            if (!file.exists()) return ToolExecutionResult("File not found: $filePath", isError = true)
            if (file.isDirectory) return ToolExecutionResult("Cannot delete directory: $filePath", isError = true)
        }

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
            val approved = gate.invoke("Delete file: $filePath", diffPreview)
            if (!approved) return ToolExecutionResult("User cancelled the file deletion for $filePath.", isError = true)
        }

        if (godModeEnabled) {
            return GodModeFileRouter.deleteFile(filePath, false, godMode = true).toToolResult()
        }

        val deleted = file.delete()
        return if (deleted) ToolExecutionResult("✅ Deleted file: $filePath")
        else ToolExecutionResult("Failed to delete file: $filePath", isError = true)
    }

    // ──────────────────────────────────────────────
    //  multi_read
    // ──────────────────────────────────────────────

    private suspend fun multiRead(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val filePathsJson = requireArg(args, "filePaths")
        val startLine = args["startLine"]?.toIntOrNull()
        val endLine = args["endLine"]?.toIntOrNull()

        val fileArray = try { JSONArray(filePathsJson) }
        catch (e: JSONException) { return ToolExecutionResult("filePaths must be a valid JSON array: ${e.message}", isError = true) }

        if (fileArray.length() > MAX_MULTI_READ_FILES) {
            return ToolExecutionResult("Too many files in multi_read: ${fileArray.length()}. Maximum is $MAX_MULTI_READ_FILES.", isError = true)
        }

        val results = mutableListOf<String>()
        for (index in 0 until fileArray.length()) {
            val rawPath = fileArray.optString(index, null)
            if (rawPath == null) {
                results.add("[${index + 1}] Invalid path at index $index: expected a string.")
                continue
            }

            val filePath = normalizePath(rawPath, scopePath)
            try {
                validateScope(filePath, scopePath)
            } catch (e: Exception) {
                results.add("[${index + 1}] Scope validation failed for $filePath: ${e.message}")
                continue
            }

            val header = "--- [${index + 1}] $filePath ---"
            val contentResult = if (godModeEnabled) {
                when (val godResult = GodModeFileRouter.readFile(filePath, godMode = true)) {
                    is GodModeResult.Success -> {
                        val allLines = godResult.content.lines()
                        val effectiveStart = startLine ?: 1
                        val effectiveEnd = when {
                            endLine != null -> endLine
                            startLine != null -> minOf(startLine + MAX_READ_LINES - 1, allLines.size)
                            else -> minOf(MAX_READ_LINES, allLines.size)
                        }
                        if (effectiveStart < 1 || effectiveEnd < effectiveStart) {
                            ToolExecutionResult("Invalid line range: $effectiveStart..$effectiveEnd", isError = true)
                        } else {
                            val slice = allLines.subList((effectiveStart - 1).coerceAtLeast(0), minOf(effectiveEnd, allLines.size))
                                .mapIndexed { idx, line -> "${effectiveStart + idx}: $line" }
                            val truncatedNote = if (endLine == null && startLine == null && allLines.size > MAX_READ_LINES) "\n[TRUNCATED: Showing $MAX_READ_LINES lines]" else ""
                            ToolExecutionResult(output = "$header\n${slice.joinToString("\n")}$truncatedNote")
                        }
                    }
                    is GodModeResult.Failure -> ToolExecutionResult("God Mode read failed for $filePath: ${godResult.reason}", isError = true)
                    is GodModeResult.GodModeDisabled -> ToolExecutionResult("God Mode disabled while reading $filePath", isError = true)
                }
            } else {
                val file = File(filePath)
                if (!file.exists()) {
                    ToolExecutionResult("File not found: $filePath", isError = true)
                } else if (!file.isFile) {
                    ToolExecutionResult("Not a file: $filePath", isError = true)
                } else {
                    val lines = file.useLines { sequence ->
                        val dropped = if (startLine != null) sequence.drop(startLine - 1) else sequence
                        val taken = if (endLine != null && startLine != null) dropped.take(endLine - startLine + 1)
                            else if (endLine != null) sequence.take(endLine)
                            else dropped.take(MAX_READ_LINES)
                        taken.mapIndexed { idx, line -> "${(startLine ?: 1) + idx}: $line" }.toList()
                    }
                    if (lines.isEmpty()) {
                        ToolExecutionResult("No lines found in range for $filePath")
                    } else {
                        val truncated = (endLine == null && startLine == null && lines.size >= MAX_READ_LINES)
                        val truncatedNote = if (truncated) "\n[TRUNCATED: Showing $MAX_READ_LINES lines]" else ""
                        ToolExecutionResult(output = "$header\n${lines.joinToString("\n")}$truncatedNote")
                    }
                }
            }

            results.add(contentResult.output)
        }

        if (results.isEmpty()) {
            return ToolExecutionResult("No valid file paths provided.", isError = true)
        }

        return ToolExecutionResult(output = results.joinToString("\n\n"))
    }

    // ──────────────────────────────────────────────
    //  multi_patch_file_content
    // ──────────────────────────────────────────────

    private suspend fun multiPatchFileContent(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val operationsJson = requireArg(args, "operations")
        val operationsArray = try { JSONArray(operationsJson) }
        catch (e: JSONException) { return ToolExecutionResult("operations must be a valid JSON array: ${e.message}", isError = true) }

        if (operationsArray.length() > MAX_MULTI_PATCH_OPERATIONS) {
            return ToolExecutionResult("Too many patch operations: ${operationsArray.length()}. Maximum is $MAX_MULTI_PATCH_OPERATIONS.", isError = true)
        }

        val outputs = mutableListOf<String>()

        for (index in 0 until operationsArray.length()) {
            val opObj = operationsArray.optJSONObject(index)
            if (opObj == null) {
                return ToolExecutionResult("Invalid operation at index $index: expected an object.", isError = true)
            }

            val filePath = opObj.optString("filePath", null)
            val searchSnippet = opObj.optString("searchSnippet", null)
            val replaceSnippet = opObj.optString("replaceSnippet", null)

            if (filePath == null || searchSnippet == null || replaceSnippet == null) {
                return ToolExecutionResult("Operation at index $index must include filePath, searchSnippet, and replaceSnippet.", isError = true)
            }

            val patchArgs = mapOf(
                "filePath" to filePath,
                "searchSnippet" to searchSnippet,
                "replaceSnippet" to replaceSnippet
            )

            val result = patchFileContent(patchArgs, scopePath)
            if (result.isError) {
                return ToolExecutionResult("Patch operation ${index + 1} failed: ${result.output}", isError = true)
            }
            outputs.add(result.output)
        }

        return ToolExecutionResult(output = outputs.joinToString("\n"))
    }

    // ──────────────────────────────────────────────
    //  Shared helpers for new text-manipulation tools
    // ──────────────────────────────────────────────

    /**
     * Read file content handling both normal and God Mode paths.
     * Returns null if the file does not exist or cannot be read.
     */
    private suspend fun readFileContentOrNull(filePath: String): String? {
        return if (godModeEnabled) {
            when (val r = GodModeFileRouter.readFile(filePath, godMode = true)) {
                is GodModeResult.Success -> r.content
                else -> null
            }
        } else {
            val f = File(filePath)
            if (f.exists() && f.isFile) f.readText() else null
        }
    }

    /**
     * Write file content handling both normal and God Mode paths.
     * Returns an error message string on failure, or null on success.
     */
    private suspend fun writeFileContentOrError(filePath: String, content: String): String? {
        return if (godModeEnabled) {
            when (val r = GodModeFileRouter.writeFile(filePath, content, false, godMode = true)) {
                is GodModeResult.Failure -> "God Mode write failed: ${r.reason}"
                else -> null
            }
        } else {
            try { File(filePath).writeText(content); null }
            catch (e: Exception) { "Write failed: ${e.message}" }
        }
    }

    // ──────────────────────────────────────────────
    //  delete_text
    // ──────────────────────────────────────────────

    private suspend fun deleteText(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val filePath = normalizePath(requireArg(args, "filePath"), scopePath)
        val text = requireArg(args, "text")
        val occurrences = args["occurrences"]?.lowercase() ?: "all"
        val lineNumber = args["lineNumber"]?.toIntOrNull()

        if (occurrences !in listOf("all", "first", "last")) {
            return ToolExecutionResult("occurrences must be 'all', 'first', or 'last'.", isError = true)
        }

        validateScope(filePath, scopePath)

        val content = readFileContentOrNull(filePath)
            ?: return ToolExecutionResult("File not found: $filePath", isError = true)

        val newContent: String = if (lineNumber != null) {
            val lines = content.lines().toMutableList()
            if (lineNumber < 1 || lineNumber > lines.size) {
                return ToolExecutionResult(
                    "lineNumber $lineNumber is out of range (file has ${lines.size} lines).",
                    isError = true
                )
            }
            val idx = lineNumber - 1
            val oldLine = lines[idx]
            if (!oldLine.contains(text)) {
                return ToolExecutionResult(
                    "Text not found on line $lineNumber of $filePath.",
                    isError = true
                )
            }
            lines[idx] = when (occurrences) {
                "first" -> oldLine.replaceFirst(text, "")
                "last"  -> {
                    val lastIdx = oldLine.lastIndexOf(text)
                    oldLine.substring(0, lastIdx) + oldLine.substring(lastIdx + text.length)
                }
                else    -> oldLine.replace(text, "")
            }
            lines.joinToString("\n")
        } else {
            if (!content.contains(text)) {
                return ToolExecutionResult("Text not found in $filePath.", isError = true)
            }
            when (occurrences) {
                "first" -> content.replaceFirst(text, "")
                "last"  -> {
                    val lastIdx = content.lastIndexOf(text)
                    content.substring(0, lastIdx) + content.substring(lastIdx + text.length)
                }
                else    -> content.replace(text, "")
            }
        }

        val gate = confirmationGate
        if (gate != null) {
            val diff = generateUnifiedDiff(filePath, content, newContent)
            val approved = gate.invoke("Delete text in: $filePath", diff.ifEmpty { null })
            if (!approved) return ToolExecutionResult("User cancelled delete_text for $filePath.", isError = true)
        }

        val err = writeFileContentOrError(filePath, newContent)
        if (err != null) return ToolExecutionResult(err, isError = true)

        val scope = if (lineNumber != null) " on line $lineNumber" else ""
        val godNote = if (godModeEnabled) " [🔓 God Mode]" else ""
        return ToolExecutionResult("✅ Deleted text ($occurrences)$scope from $filePath.$godNote")
    }

    // ──────────────────────────────────────────────
    //  clear_file
    // ──────────────────────────────────────────────

    private suspend fun clearFile(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val filePath = normalizePath(requireArg(args, "filePath"), scopePath)

        validateScope(filePath, scopePath)

        val exists = if (godModeEnabled) {
            when (GodModeFileRouter.statFile(filePath, godMode = true)) {
                is GodModeResult.Success -> true
                else -> false
            }
        } else {
            File(filePath).let { it.exists() && it.isFile }
        }

        if (!exists) return ToolExecutionResult("File not found: $filePath", isError = true)

        val gate = confirmationGate
        if (gate != null) {
            val approved = gate.invoke("Clear all content from: $filePath", null)
            if (!approved) return ToolExecutionResult("User cancelled clear_file for $filePath.", isError = true)
        }

        val err = writeFileContentOrError(filePath, "")
        if (err != null) return ToolExecutionResult(err, isError = true)

        val godNote = if (godModeEnabled) " [🔓 God Mode]" else ""
        return ToolExecutionResult("✅ Cleared all content from $filePath.$godNote")
    }

    // ──────────────────────────────────────────────
    //  delete_lines
    // ──────────────────────────────────────────────

    private suspend fun deleteLines(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val filePath = normalizePath(requireArg(args, "filePath"), scopePath)
        val startLine = requireArg(args, "startLine").toIntOrNull()
            ?: return ToolExecutionResult("startLine must be an integer.", isError = true)
        val endLine = args["endLine"]?.toIntOrNull() ?: startLine

        if (startLine < 1) return ToolExecutionResult("startLine must be ≥ 1.", isError = true)
        if (endLine < startLine) return ToolExecutionResult("endLine must be ≥ startLine.", isError = true)

        validateScope(filePath, scopePath)

        val content = readFileContentOrNull(filePath)
            ?: return ToolExecutionResult("File not found: $filePath", isError = true)

        val lines = content.lines()
        if (startLine > lines.size) {
            return ToolExecutionResult(
                "startLine $startLine is beyond end of file (${lines.size} lines).",
                isError = true
            )
        }

        val effectiveEnd = minOf(endLine, lines.size)
        // Keep lines outside [startLine-1, effectiveEnd-1] (0-indexed, inclusive)
        val newContent = lines
            .filterIndexed { idx, _ -> idx < startLine - 1 || idx >= effectiveEnd }
            .joinToString("\n")

        val gate = confirmationGate
        if (gate != null) {
            val diff = generateUnifiedDiff(filePath, content, newContent)
            val approved = gate.invoke("Delete lines $startLine–$effectiveEnd in: $filePath", diff.ifEmpty { null })
            if (!approved) return ToolExecutionResult("User cancelled delete_lines for $filePath.", isError = true)
        }

        val err = writeFileContentOrError(filePath, newContent)
        if (err != null) return ToolExecutionResult(err, isError = true)

        val deleted = effectiveEnd - startLine + 1
        val godNote = if (godModeEnabled) " [🔓 God Mode]" else ""
        return ToolExecutionResult("✅ Deleted $deleted line(s) ($startLine–$effectiveEnd) from $filePath.$godNote")
    }

    // ──────────────────────────────────────────────
    //  insert_lines
    // ──────────────────────────────────────────────

    private suspend fun insertLines(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val filePath = normalizePath(requireArg(args, "filePath"), scopePath)
        val insertContent = requireArg(args, "content")
        val lineNumber = requireArg(args, "lineNumber").toIntOrNull()
            ?: return ToolExecutionResult("lineNumber must be an integer.", isError = true)

        if (lineNumber < 1) return ToolExecutionResult("lineNumber must be ≥ 1.", isError = true)

        validateScope(filePath, scopePath)

        val content = readFileContentOrNull(filePath)
            ?: return ToolExecutionResult("File not found: $filePath", isError = true)

        val lines = content.lines().toMutableList()
        // Insert before lineNumber (1-indexed); clamp to end if lineNumber > list size
        val insertIdx = (lineNumber - 1).coerceIn(0, lines.size)
        val insertLines = insertContent.lines()
        lines.addAll(insertIdx, insertLines)
        val newContent = lines.joinToString("\n")

        val gate = confirmationGate
        if (gate != null) {
            val diff = generateUnifiedDiff(filePath, content, newContent)
            val approved = gate.invoke("Insert ${insertLines.size} line(s) before line $lineNumber in: $filePath", diff.ifEmpty { null })
            if (!approved) return ToolExecutionResult("User cancelled insert_lines for $filePath.", isError = true)
        }

        val err = writeFileContentOrError(filePath, newContent)
        if (err != null) return ToolExecutionResult(err, isError = true)

        val godNote = if (godModeEnabled) " [🔓 God Mode]" else ""
        return ToolExecutionResult(
            "✅ Inserted ${insertLines.size} line(s) before line $lineNumber in $filePath.$godNote"
        )
    }

    // ──────────────────────────────────────────────
    //  replace_lines
    // ──────────────────────────────────────────────

    private suspend fun replaceLines(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val filePath = normalizePath(requireArg(args, "filePath"), scopePath)
        val startLine = requireArg(args, "startLine").toIntOrNull()
            ?: return ToolExecutionResult("startLine must be an integer.", isError = true)
        val endLine = requireArg(args, "endLine").toIntOrNull()
            ?: return ToolExecutionResult("endLine must be an integer.", isError = true)
        val replaceContent = requireArg(args, "content")

        if (startLine < 1) return ToolExecutionResult("startLine must be ≥ 1.", isError = true)
        if (endLine < startLine) return ToolExecutionResult("endLine must be ≥ startLine.", isError = true)

        validateScope(filePath, scopePath)

        val content = readFileContentOrNull(filePath)
            ?: return ToolExecutionResult("File not found: $filePath", isError = true)

        val lines = content.lines().toMutableList()
        if (startLine > lines.size) {
            return ToolExecutionResult(
                "startLine $startLine is beyond end of file (${lines.size} lines).",
                isError = true
            )
        }

        val effectiveEnd = minOf(endLine, lines.size)
        val replacementLines = replaceContent.lines()

        // Remove the old lines and insert the new ones
        val head = lines.subList(0, startLine - 1).toList()
        val tail = lines.subList(effectiveEnd, lines.size).toList()
        val newContent = (head + replacementLines + tail).joinToString("\n")

        val gate = confirmationGate
        if (gate != null) {
            val diff = generateUnifiedDiff(filePath, content, newContent)
            val approved = gate.invoke(
                "Replace lines $startLine–$effectiveEnd (${effectiveEnd - startLine + 1} lines → ${replacementLines.size} lines) in: $filePath",
                diff.ifEmpty { null }
            )
            if (!approved) return ToolExecutionResult("User cancelled replace_lines for $filePath.", isError = true)
        }

        val err = writeFileContentOrError(filePath, newContent)
        if (err != null) return ToolExecutionResult(err, isError = true)

        val godNote = if (godModeEnabled) " [🔓 God Mode]" else ""
        return ToolExecutionResult(
            "✅ Replaced ${effectiveEnd - startLine + 1} line(s) ($startLine–$effectiveEnd) with " +
                "${replacementLines.size} new line(s) in $filePath.$godNote"
        )
    }

    // ──────────────────────────────────────────────
    //  append_to_file
    // ──────────────────────────────────────────────

    private suspend fun appendToFile(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val filePath = normalizePath(requireArg(args, "filePath"), scopePath)
        val appendContent = requireArg(args, "content")
        val addNewline = args["addNewline"]?.lowercase() != "false" // default true

        validateScope(filePath, scopePath)

        val existing = readFileContentOrNull(filePath)
            ?: return ToolExecutionResult("File not found: $filePath", isError = true)

        val separator = if (addNewline && existing.isNotEmpty() && !existing.endsWith("\n")) "\n" else ""
        val newContent = existing + separator + appendContent

        val gate = confirmationGate
        if (gate != null) {
            val diff = generateUnifiedDiff(filePath, existing, newContent)
            val approved = gate.invoke("Append to: $filePath", diff.ifEmpty { null })
            if (!approved) return ToolExecutionResult("User cancelled append_to_file for $filePath.", isError = true)
        }

        val err = writeFileContentOrError(filePath, newContent)
        if (err != null) return ToolExecutionResult(err, isError = true)

        val godNote = if (godModeEnabled) " [🔓 God Mode]" else ""
        return ToolExecutionResult(
            "✅ Appended ${appendContent.length} character(s) to $filePath.$godNote"
        )
    }

    // ──────────────────────────────────────────────
    //  run_terminal
    // ──────────────────────────────────────────────

    private suspend fun runTerminal(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val command = requireArg(args, "command")

        val workDir = if (godModeEnabled) {
            val requested = File(scopePath)
            if (requested.exists() && requested.isDirectory) requested
            else {
                val fallback = File("/data/local/tmp")
                fallback.mkdirs() 
                fallback
            }
        } else {
            File(scopePath)
        }

        if (!workDir.exists() || !workDir.isDirectory) {
            return ToolExecutionResult("Target Context directory not found: $scopePath", isError = true)
        }

        return try {
            withContext(Dispatchers.IO) {
                val process = ProcessBuilder("/bin/sh", "-c", command)
                    .directory(workDir)
                    .start() 

                val stdoutBuffer = StringBuffer()
                val stderrBuffer = StringBuffer()
                
                val stdoutThread = Thread {
                    try {
                        process.inputStream.bufferedReader().use { reader ->
                            reader.lineSequence().forEach { line ->
                                if (stdoutBuffer.length < MAX_TERMINAL_OUTPUT_CHARS) stdoutBuffer.appendLine(line)
                            }
                        }
                    } catch (_: Exception) {}
                }
                val stderrThread = Thread {
                    try {
                        process.errorStream.bufferedReader().use { reader ->
                            reader.lineSequence().forEach { line ->
                                if (stderrBuffer.length < MAX_TERMINAL_OUTPUT_CHARS) stderrBuffer.appendLine(line)
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

                val completed = !waitThread.isAlive
                if (!completed) {
                    process.destroy() 
                    stdoutThread.interrupt()
                    stderrThread.interrupt()
                    return@withContext ToolExecutionResult("⏱ Command timed out after ${TERMINAL_TIMEOUT_SECONDS}s: $command", isError = true)
                }

                stdoutThread.join(2_000L) 
                stderrThread.join(2_000L)

                val exitCode = process.exitValue()
                val stdout = stdoutBuffer.toString().trimEnd()
                val stderr = stderrBuffer.toString().trimEnd()
                val stdoutTruncated = stdoutBuffer.length >= MAX_TERMINAL_OUTPUT_CHARS
                val stderrTruncated = stderrBuffer.length >= MAX_TERMINAL_OUTPUT_CHARS

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
            ToolExecutionResult("Failed to execute command '$command': ${e.message}", isError = true)
        }
    }

    // ──────────────────────────────────────────────
    //  python_runner
    // ──────────────────────────────────────────────

    private suspend fun runPython(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val mode = requireArg(args, "mode").lowercase()
        val workDir = File(scopePath)
        
        if (!workDir.exists() || !workDir.isDirectory) {
            return ToolExecutionResult("Target Context directory not found: $scopePath", isError = true)
        }

        val pythonBin = detectPythonInterpreter(workDir)
            ?: return ToolExecutionResult("Python interpreter not found. Install python3/python in the environment first.", isError = true)

        val command = when (mode) {
            "inline" -> {
                val code = args["code"] ?: return ToolExecutionResult("Missing required argument: code", isError = true)
                if (code.length > MAX_PYTHON_CODE_CHARS) {
                    return ToolExecutionResult("Python code is too large (${code.length} chars). Max: $MAX_PYTHON_CODE_CHARS.", isError = true)
                }
                listOf(pythonBin, "-c", code)
            }
            "file" -> {
                val filePath = normalizePath(args["filePath"] ?: return ToolExecutionResult("Missing required argument: filePath", isError = true), scopePath)
                validateScope(filePath, scopePath)
                val scriptFile = File(filePath)
                if (!scriptFile.exists() || !scriptFile.isFile) return ToolExecutionResult("Python file not found: $filePath", isError = true)
                
                val cliArgs = parseCommandLineArgs(args["args"].orEmpty())
                listOf(pythonBin, scriptFile.absolutePath) + cliArgs
            }
            else -> return ToolExecutionResult("Invalid mode '$mode'. Use 'inline' or 'file'.", isError = true)
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
                                if (stdoutBuffer.length < MAX_TERMINAL_OUTPUT_CHARS) stdoutBuffer.appendLine(line)
                            }
                        }
                    } catch (_: Exception) {}
                }
                val stderrThread = Thread {
                    try {
                        process.errorStream.bufferedReader().use { reader ->
                            reader.lineSequence().forEach { line ->
                                if (stderrBuffer.length < MAX_TERMINAL_OUTPUT_CHARS) stderrBuffer.appendLine(line)
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
                    return@withContext ToolExecutionResult("⏱ Python command timed out after ${TERMINAL_TIMEOUT_SECONDS}s.", isError = true)
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
    //  Path Normalization & Scope Validation
    // ──────────────────────────────────────────────

    private fun normalizePath(filePath: String, scopePath: String): String {
        return when {
            filePath.startsWith(scopePath) -> filePath  
            !filePath.startsWith("/")      -> "$scopePath/$filePath"  
            else                           -> filePath  
        }
    }

    private fun validateScope(filePath: String, scopePath: String) {
        if (godModeEnabled) return 

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

    private fun requireArg(args: Map<String, String>, name: String): String =
        args[name] ?: throw IllegalArgumentException("Missing required argument: $name")
}
