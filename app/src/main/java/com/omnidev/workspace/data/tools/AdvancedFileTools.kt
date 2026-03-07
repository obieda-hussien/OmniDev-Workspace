package com.omnidev.workspace.data.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Advanced root-level file operation tools executed via Shizuku shell.
 *
 * These go beyond the scope-restricted [FileToolManager] and provide
 * Termux-grade file operations for power users with God Mode enabled:
 * - `grep_search`: Recursive grep across any directory
 * - `find_files`: Find files by name pattern, size, or modification time
 * - `file_permissions`: Change file permissions (chmod/chown)
 * - `disk_usage`: Analyze disk usage of directories
 * - `archive_tool`: Create/extract tar/zip archives
 */
object AdvancedFileTools {

    /** Maximum output characters. */
    private const val MAX_OUTPUT = 8_000

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "grep_search",
            description = "Recursive grep search across any directory using root access. " +
                "Searches file contents for a pattern and returns matching lines with file paths. " +
                "Supports regex patterns. Use for finding code patterns, config values, or log entries " +
                "across the entire filesystem.",
            parameters = listOf(
                ToolParameter("directory", "string", "Directory to search in (e.g., /sdcard, /data/data/com.app)", required = true),
                ToolParameter("pattern", "string", "Search pattern (regex supported)", required = true),
                ToolParameter("file_filter", "string", "Optional file name filter (e.g., '*.kt', '*.xml')", required = false),
                ToolParameter("ignore_case", "string", "Set to 'true' for case-insensitive search", required = false),
                ToolParameter("max_results", "string", "Maximum number of matching lines to return (default: 50)", required = false)
            )
        ),
        ToolDefinition(
            name = "find_files",
            description = "Find files by name pattern, type, size, or modification time using root access. " +
                "Use for discovering files across the filesystem, finding large files, or locating " +
                "recently modified files.",
            parameters = listOf(
                ToolParameter("directory", "string", "Directory to search in", required = true),
                ToolParameter("name_pattern", "string", "File name pattern (e.g., '*.apk', 'config*')", required = false),
                ToolParameter("type", "string", "File type: 'f' (file), 'd' (directory), 'l' (symlink)", required = false),
                ToolParameter("min_size", "string", "Minimum file size (e.g., '10M', '1G')", required = false),
                ToolParameter("max_depth", "string", "Maximum directory depth to search (default: 5)", required = false),
                ToolParameter("newer_than_days", "string", "Only files modified within this many days", required = false)
            )
        ),
        ToolDefinition(
            name = "file_permissions",
            description = "Change file permissions or ownership using root access. " +
                "Supports chmod (permissions) and chown (ownership) operations.",
            parameters = listOf(
                ToolParameter("path", "string", "Path to file or directory", required = true),
                ToolParameter("action", "string", "Action: 'chmod' or 'chown'", required = true),
                ToolParameter("value", "string", "For chmod: permission mode (e.g., '755'). For chown: 'user:group'", required = true),
                ToolParameter("recursive", "string", "Set to 'true' for recursive operation", required = false)
            )
        ),
        ToolDefinition(
            name = "disk_usage",
            description = "Analyze disk usage of directories using root access. " +
                "Shows size of directories sorted by size. Use for finding what's consuming storage.",
            parameters = listOf(
                ToolParameter("path", "string", "Directory path to analyze", required = true),
                ToolParameter("max_depth", "string", "Maximum depth for du report (default: 2)", required = false),
                ToolParameter("human_readable", "string", "Set to 'true' for human-readable sizes (default: true)", required = false)
            )
        ),
        ToolDefinition(
            name = "archive_tool",
            description = "Create or extract tar/zip archives using root access. " +
                "Supports tar.gz, tar.bz2, and zip formats.",
            parameters = listOf(
                ToolParameter("action", "string", "Action: 'create' or 'extract'", required = true),
                ToolParameter("archive_path", "string", "Path to the archive file", required = true),
                ToolParameter("target_path", "string", "For create: directory to archive. For extract: destination directory.", required = true),
                ToolParameter("format", "string", "Archive format: 'tar.gz' (default), 'tar.bz2', 'zip'", required = false)
            )
        )
    )

    suspend fun executeTool(name: String, arguments: Map<String, String>): ToolExecutionResult {
        return when (name) {
            "grep_search" -> grepSearch(arguments)
            "find_files" -> findFiles(arguments)
            "file_permissions" -> filePermissions(arguments)
            "disk_usage" -> diskUsage(arguments)
            "archive_tool" -> archiveTool(arguments)
            else -> ToolExecutionResult("Unknown advanced file tool: $name", isError = true)
        }
    }

    private suspend fun grepSearch(args: Map<String, String>): ToolExecutionResult {
        val directory = args["directory"] ?: return missingArg("directory")
        val pattern = args["pattern"] ?: return missingArg("pattern")
        val fileFilter = args["file_filter"]
        val ignoreCase = args["ignore_case"]?.equals("true", ignoreCase = true) ?: false
        val maxResults = args["max_results"]?.toIntOrNull() ?: 50

        val shellPattern = sanitizeShellArg(pattern)
        val shellDir = sanitizeShellArg(directory)

        val cmd = buildString {
            append("grep -rn")
            if (ignoreCase) append("i")
            append(" --color=never")
            append(" -m $maxResults")
            if (fileFilter != null) {
                append(" --include=${sanitizeShellArg(fileFilter)}")
            }
            append(" -- $shellPattern $shellDir")
            append(" 2>&1 | head -n $maxResults")
        }

        return executeShellCommand(cmd)
    }

    private suspend fun findFiles(args: Map<String, String>): ToolExecutionResult {
        val directory = args["directory"] ?: return missingArg("directory")
        val namePattern = args["name_pattern"]
        val type = args["type"]
        val minSize = args["min_size"]
        val maxDepth = args["max_depth"]?.toIntOrNull() ?: 5
        val newerThanDays = args["newer_than_days"]?.toIntOrNull()

        val shellDir = sanitizeShellArg(directory)

        val cmd = buildString {
            append("find $shellDir -maxdepth $maxDepth")
            if (namePattern != null) append(" -name ${sanitizeShellArg(namePattern)}")
            if (type != null) append(" -type ${sanitizeShellArg(type)}")
            if (minSize != null) append(" -size +${sanitizeShellArg(minSize)}")
            if (newerThanDays != null) append(" -mtime -$newerThanDays")
            append(" 2>/dev/null | head -n 100")
        }

        return executeShellCommand(cmd)
    }

    private suspend fun filePermissions(args: Map<String, String>): ToolExecutionResult {
        val path = args["path"] ?: return missingArg("path")
        val action = args["action"] ?: return missingArg("action")
        val value = args["value"] ?: return missingArg("value")
        val recursive = args["recursive"]?.equals("true", ignoreCase = true) ?: false

        val shellPath = sanitizeShellArg(path)
        val shellValue = sanitizeShellArg(value)

        val cmd = when (action) {
            "chmod" -> "chmod ${if (recursive) "-R " else ""}$shellValue $shellPath && echo 'OK: permissions changed' && ls -la $shellPath"
            "chown" -> "chown ${if (recursive) "-R " else ""}$shellValue $shellPath && echo 'OK: ownership changed' && ls -la $shellPath"
            else -> return ToolExecutionResult("Unknown action: $action. Use 'chmod' or 'chown'.", isError = true)
        }

        return executeShellCommand(cmd)
    }

    private suspend fun diskUsage(args: Map<String, String>): ToolExecutionResult {
        val path = args["path"] ?: return missingArg("path")
        val maxDepth = args["max_depth"]?.toIntOrNull() ?: 2
        val humanReadable = args["human_readable"]?.equals("false", ignoreCase = true)?.not() ?: true

        val shellPath = sanitizeShellArg(path)
        val flags = if (humanReadable) "-h" else ""

        val cmd = "du $flags --max-depth=$maxDepth $shellPath 2>/dev/null | sort -rh | head -n 30"

        return executeShellCommand(cmd)
    }

    private suspend fun archiveTool(args: Map<String, String>): ToolExecutionResult {
        val action = args["action"] ?: return missingArg("action")
        val archivePath = args["archive_path"] ?: return missingArg("archive_path")
        val targetPath = args["target_path"] ?: return missingArg("target_path")
        val format = args["format"] ?: "tar.gz"

        val shellArchive = sanitizeShellArg(archivePath)
        val shellTarget = sanitizeShellArg(targetPath)

        val cmd = when {
            action == "create" && format == "tar.gz" ->
                "tar -czf $shellArchive -C $shellTarget . && echo 'OK: archive created' && ls -la $shellArchive"
            action == "create" && format == "tar.bz2" ->
                "tar -cjf $shellArchive -C $shellTarget . && echo 'OK: archive created' && ls -la $shellArchive"
            action == "create" && format == "zip" ->
                "cd $shellTarget && zip -r $shellArchive . && echo 'OK: archive created' && ls -la $shellArchive"
            action == "extract" && (format == "tar.gz" || archivePath.endsWith(".tar.gz") || archivePath.endsWith(".tgz")) ->
                "mkdir -p $shellTarget && tar -xzf $shellArchive -C $shellTarget && echo 'OK: extracted' && ls -la $shellTarget"
            action == "extract" && (format == "tar.bz2" || archivePath.endsWith(".tar.bz2")) ->
                "mkdir -p $shellTarget && tar -xjf $shellArchive -C $shellTarget && echo 'OK: extracted' && ls -la $shellTarget"
            action == "extract" && (format == "zip" || archivePath.endsWith(".zip")) ->
                "mkdir -p $shellTarget && unzip -o $shellArchive -d $shellTarget && echo 'OK: extracted' && ls -la $shellTarget"
            else -> return ToolExecutionResult(
                "Unknown combination: action=$action, format=$format. " +
                "Supported: create/extract with tar.gz, tar.bz2, zip",
                isError = true
            )
        }

        return executeShellCommand(cmd)
    }

    private suspend fun executeShellCommand(command: String): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val result = ShizukuCommandTool.execute(command)
            when (result) {
                is ShizukuResult.Success -> {
                    val output = result.output
                    val truncated = output.length > MAX_OUTPUT
                    ToolExecutionResult(
                        output = if (truncated) output.take(MAX_OUTPUT) + "\n[TRUNCATED]" else output,
                        truncated = truncated
                    )
                }
                is ShizukuResult.Failure -> ToolExecutionResult(result.reason, isError = true)
                is ShizukuResult.PermissionRequired -> ToolExecutionResult(result.message, isError = true)
                is ShizukuResult.Unavailable -> ToolExecutionResult(result.message, isError = true)
            }
        }

    /**
     * Escapes shell metacharacters in an argument to prevent injection.
     */
    private fun sanitizeShellArg(arg: String): String {
        // Use single quotes to prevent shell expansion; escape any existing single quotes
        return "'${arg.replace("'", "'\\''")}'"
    }

    private fun missingArg(name: String) =
        ToolExecutionResult("Missing required argument: $name", isError = true)
}
