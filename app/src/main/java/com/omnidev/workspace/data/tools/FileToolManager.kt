package com.omnidev.workspace.data.tools

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
 * - `create_file`: Create a new file with content.
 * - `delete_file`: Delete a file.
 */
class FileToolManager : ToolManager {

    companion object {
        /** Maximum lines returned from a single read to guard context window usage. */
        private const val MAX_READ_LINES = 500

        /** Maximum number of search result matches to return. */
        private const val MAX_SEARCH_RESULTS = 50

        /** Maximum file size (in bytes) that can be created via create_file. */
        private const val MAX_CREATE_FILE_SIZE = 1_048_576L // 1 MB
    }

    // ──────────────────────────────────────────────
    //  Tool Definitions (for AI function-calling schema)
    // ──────────────────────────────────────────────

    override fun getToolDefinitions(): List<ToolDefinition> = listOf(
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
        )
    )

    // ──────────────────────────────────────────────
    //  Tool Execution Router
    // ──────────────────────────────────────────────

    override suspend fun executeTool(
        name: String,
        arguments: Map<String, String>,
        scopePath: String
    ): ToolExecutionResult {
        return try {
            when (name) {
                "read_file_lines" -> readFileLines(arguments, scopePath)
                "search_codebase" -> searchCodebase(arguments, scopePath)
                "patch_file_content" -> patchFileContent(arguments, scopePath)
                "create_file" -> createFile(arguments, scopePath)
                "delete_file" -> deleteFile(arguments, scopePath)
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
    //  read_file_lines
    // ──────────────────────────────────────────────

    /**
     * Reads lines [startLine]..[endLine] (1-indexed, inclusive) from a file.
     * Never loads more than [MAX_READ_LINES] at once to protect the context window.
     */
    private fun readFileLines(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val filePath = requireArg(args, "filePath")
        val startLine = requireArg(args, "startLine").toIntOrNull()
            ?: return ToolExecutionResult("startLine must be an integer.", isError = true)
        val endLine = requireArg(args, "endLine").toIntOrNull()
            ?: return ToolExecutionResult("endLine must be an integer.", isError = true)

        validateScope(filePath, scopePath)

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
        val directory = requireArg(args, "directory")
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
     */
    private fun patchFileContent(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val filePath = requireArg(args, "filePath")
        val searchSnippet = requireArg(args, "searchSnippet")
        val replaceSnippet = requireArg(args, "replaceSnippet")

        validateScope(filePath, scopePath)

        val file = File(filePath)
        if (!file.exists()) return ToolExecutionResult("File not found: $filePath", isError = true)
        if (!file.isFile) return ToolExecutionResult("Not a file: $filePath", isError = true)

        val content = file.readText()
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
                file.writeText(newContent)

                // Calculate affected line range for the response
                val linesBefore = content.substring(0, content.indexOf(searchSnippet)).count { it == '\n' } + 1
                val linesAffected = searchSnippet.count { it == '\n' } + 1
                ToolExecutionResult(
                    output = "✅ Patched $filePath — replaced $linesAffected line(s) starting at line $linesBefore."
                )
            }
        }
    }

    // ──────────────────────────────────────────────
    //  create_file
    // ──────────────────────────────────────────────

    /**
     * Creates a new file with the specified content. Parent directories are created if needed.
     */
    private fun createFile(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val filePath = requireArg(args, "filePath")
        val content = requireArg(args, "content")

        validateScope(filePath, scopePath)

        if (content.length > MAX_CREATE_FILE_SIZE) {
            return ToolExecutionResult(
                output = "Content exceeds maximum file size of ${MAX_CREATE_FILE_SIZE / 1024}KB.",
                isError = true
            )
        }

        val file = File(filePath)
        if (file.exists()) {
            return ToolExecutionResult("File already exists: $filePath. Use patch_file_content to modify.", isError = true)
        }

        file.parentFile?.mkdirs()
        file.writeText(content)

        return ToolExecutionResult("✅ Created file: $filePath (${content.length} bytes)")
    }

    // ──────────────────────────────────────────────
    //  delete_file
    // ──────────────────────────────────────────────

    /**
     * Deletes a single file. Refuses to delete directories for safety.
     */
    private fun deleteFile(args: Map<String, String>, scopePath: String): ToolExecutionResult {
        val filePath = requireArg(args, "filePath")

        validateScope(filePath, scopePath)

        val file = File(filePath)
        if (!file.exists()) return ToolExecutionResult("File not found: $filePath", isError = true)
        if (file.isDirectory) return ToolExecutionResult("Cannot delete directory: $filePath", isError = true)

        val deleted = file.delete()
        return if (deleted) {
            ToolExecutionResult("✅ Deleted file: $filePath")
        } else {
            ToolExecutionResult("Failed to delete file: $filePath", isError = true)
        }
    }

    // ──────────────────────────────────────────────
    //  Scope Validation
    // ──────────────────────────────────────────────

    /**
     * Validates that a resolved [filePath] falls within the allowed [scopePath].
     * Uses canonical path resolution to prevent traversal attacks (e.g., ../../etc/passwd).
     *
     * @throws SecurityException if the file path escapes the scope.
     */
    private fun validateScope(filePath: String, scopePath: String) {
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
