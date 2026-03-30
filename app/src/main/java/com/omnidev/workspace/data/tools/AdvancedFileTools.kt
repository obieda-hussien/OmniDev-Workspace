package com.omnidev.workspace.data.tools

import android.util.Base64
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Advanced root-level file operation tools executed via Shizuku/SU shell.
 *
 * * HACKER UPGRADES:
 * 1. Base64 Script Injection: Prevents regex and path quoting hell.
 * 2. Toybox Bypasses: Android's native `grep` and `du` lack standard GNU flags 
 * (like --include or --max-depth). These commands are rewritten using advanced 
 * POSIX-compliant pipelines (find + xargs) to work flawlessly on native Android.
 * 3. Busybox Auto-Detection: Automatically utilizes busybox if installed for superior tools.
 */
object AdvancedFileTools {

    private const val MAX_OUTPUT = 12_000

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "grep_search",
            description = "Recursive grep search across any directory using root/Shizuku access. " +
                "Searches file contents for a regex pattern. Perfect for finding hardcoded secrets, " +
                "config values, or logs.",
            parameters = listOf(
                ToolParameter("directory", "string", "Directory to search (e.g., /data/data/com.app)", required = true),
                ToolParameter("pattern", "string", "Search pattern (regex supported)", required = true),
                ToolParameter("file_filter", "string", "Optional file name filter (e.g., '*.xml')", required = false),
                ToolParameter("ignore_case", "string", "Set to 'true' for case-insensitive", required = false),
                ToolParameter("max_results", "string", "Maximum lines to return (default: 50)", required = false)
            )
        ),
        ToolDefinition(
            name = "find_files",
            description = "Find files by name pattern, type, size, or modification time using root access.",
            parameters = listOf(
                ToolParameter("directory", "string", "Directory to search in", required = true),
                ToolParameter("name_pattern", "string", "File name pattern (e.g., '*.apk')", required = false),
                ToolParameter("type", "string", "File type: 'f' (file), 'd' (dir), 'l' (symlink)", required = false),
                ToolParameter("min_size", "string", "Minimum size (e.g., '10M', '1G')", required = false),
                ToolParameter("max_depth", "string", "Max depth (default: 5)", required = false),
                ToolParameter("newer_than_days", "string", "Modified within N days", required = false)
            )
        ),
        ToolDefinition(
            name = "file_permissions",
            description = "Change file permissions (chmod) or ownership (chown) using root access.",
            parameters = listOf(
                ToolParameter("path", "string", "Path to file or directory", required = true),
                ToolParameter("action", "string", "Action: 'chmod' or 'chown'", required = true),
                ToolParameter("value", "string", "e.g., '755' or 'system:system'", required = true),
                ToolParameter("recursive", "string", "Set to 'true' for recursive", required = false)
            )
        ),
        ToolDefinition(
            name = "disk_usage",
            description = "Analyze disk usage of directories to find storage hogs.",
            parameters = listOf(
                ToolParameter("path", "string", "Directory path", required = true),
                ToolParameter("max_depth", "string", "Max depth (default: 2)", required = false)
            )
        ),
        ToolDefinition(
            name = "archive_tool",
            description = "Create or extract archives (tar.gz, zip) using root access.",
            parameters = listOf(
                ToolParameter("action", "string", "'create' or 'extract'", required = true),
                ToolParameter("archive_path", "string", "Path to the archive file", required = true),
                ToolParameter("target_path", "string", "For create: dir to archive. For extract: destination dir.", required = true),
                ToolParameter("format", "string", "tar.gz, tgz, zip. (Note: zip creation requires busybox/termux)", required = false)
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

    // ─────────────────────────────────────────────────────────────────────
    // Action Implementations
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun grepSearch(args: Map<String, String>): ToolExecutionResult {
        val directory = args["directory"] ?: return missingArg("directory")
        val pattern = args["pattern"] ?: return missingArg("pattern")
        val fileFilter = args["file_filter"]
        val ignoreCase = args["ignore_case"]?.equals("true", ignoreCase = true) ?: false
        val maxResults = args["max_results"]?.toIntOrNull() ?: 50

        // HACK: Android Toybox grep lacks --include. We use `find` piped to `xargs grep`.
        // /dev/null is passed to grep to force it to print the filename even if only 1 file is passed.
        val script = buildString {
            appendLine("DIR=${sanitizeShellArg(directory)}")
            appendLine("PATTERN=${sanitizeShellArg(pattern)}")
            
            val grepFlags = if (ignoreCase) "-inE" else "-nE"
            val findNameFilter = if (fileFilter != null) "-name ${sanitizeShellArg(fileFilter)}" else ""
            
            appendLine("find \"\$DIR\" -type f $findNameFilter -print0 2>/dev/null | xargs -0 grep $grepFlags \"\$PATTERN\" /dev/null 2>/dev/null | head -n $maxResults")
        }

        return executePrivilegedScript(script, headTruncate = true)
    }

    private suspend fun findFiles(args: Map<String, String>): ToolExecutionResult {
        val directory = args["directory"] ?: return missingArg("directory")
        val namePattern = args["name_pattern"]
        val type = args["type"]
        val minSize = args["min_size"]
        val maxDepth = args["max_depth"]?.toIntOrNull() ?: 5
        val newerThanDays = args["newer_than_days"]?.toIntOrNull()

        val script = buildString {
            append("find ${sanitizeShellArg(directory)} -maxdepth $maxDepth")
            if (namePattern != null) append(" -name ${sanitizeShellArg(namePattern)}")
            if (type != null) append(" -type ${sanitizeShellArg(type)}")
            if (minSize != null) append(" -size +${sanitizeShellArg(minSize)}")
            if (newerThanDays != null) append(" -mtime -$newerThanDays")
            append(" 2>/dev/null | head -n 150")
        }

        return executePrivilegedScript(script, headTruncate = true)
    }

    private suspend fun filePermissions(args: Map<String, String>): ToolExecutionResult {
        val path = args["path"] ?: return missingArg("path")
        val action = args["action"] ?: return missingArg("action")
        val value = args["value"] ?: return missingArg("value")
        val recursive = args["recursive"]?.equals("true", ignoreCase = true) ?: false

        val recFlag = if (recursive) "-R" else ""
        
        val script = when (action.lowercase()) {
            "chmod" -> "chmod $recFlag ${sanitizeShellArg(value)} ${sanitizeShellArg(path)} && echo '✅ Permissions changed' && ls -ld ${sanitizeShellArg(path)}"
            "chown" -> "chown $recFlag ${sanitizeShellArg(value)} ${sanitizeShellArg(path)} && echo '✅ Ownership changed' && ls -ld ${sanitizeShellArg(path)}"
            else -> return ToolExecutionResult("Unknown action: $action. Use 'chmod' or 'chown'.", isError = true)
        }

        return executePrivilegedScript(script)
    }

    private suspend fun diskUsage(args: Map<String, String>): ToolExecutionResult {
        val path = args["path"] ?: return missingArg("path")
        val maxDepth = args["max_depth"]?.toIntOrNull() ?: 2

        // FIX: Toybox du uses `-d` not `--max-depth`
        val script = "du -h -d $maxDepth ${sanitizeShellArg(path)} 2>/dev/null | sort -rh | head -n 50"
        return executePrivilegedScript(script, headTruncate = true)
    }

    private suspend fun archiveTool(args: Map<String, String>): ToolExecutionResult {
        val action = args["action"] ?: return missingArg("action")
        val archivePath = args["archive_path"] ?: return missingArg("archive_path")
        val targetPath = args["target_path"] ?: return missingArg("target_path")
        val format = (args["format"] ?: inferArchiveFormat(archivePath)).lowercase()

        val script = buildString {
            appendLine("ARCHIVE=${sanitizeShellArg(archivePath)}")
            appendLine("TARGET=${sanitizeShellArg(targetPath)}")
            
            // Prefer busybox if available for missing native tools (like zip creation)
            appendLine("BB=\"\"; if command -v busybox >/dev/null 2>&1; then BB=\"busybox \"; fi")

            when {
                action == "create" && (format == "tar.gz" || format == "tgz" || format == "tar") -> {
                    val zFlag = if (format == "tar") "" else "z"
                    appendLine("cd \"\$TARGET\" && tar -c${zFlag}f \"\$ARCHIVE\" . && echo '✅ Archive created' && ls -lh \"\$ARCHIVE\"")
                }
                action == "create" && format == "zip" -> {
                    appendLine("if ! command -v zip >/dev/null 2>&1; then echo '❌ zip binary not found. Use tar.gz instead.'; exit 1; fi")
                    appendLine("cd \"\$TARGET\" && zip -r \"\$ARCHIVE\" . && echo '✅ Zip created' && ls -lh \"\$ARCHIVE\"")
                }
                action == "extract" && (format == "tar.gz" || format == "tgz" || format == "tar") -> {
                    val zFlag = if (format == "tar") "" else "z"
                    appendLine("mkdir -p \"\$TARGET\" && tar -x${zFlag}f \"\$ARCHIVE\" -C \"\$TARGET\" && echo '✅ Extracted' && ls -lh \"\$TARGET\" | head -n 5")
                }
                action == "extract" && format == "zip" -> {
                    appendLine("mkdir -p \"\$TARGET\" && \$BB unzip -o \"\$ARCHIVE\" -d \"\$TARGET\" && echo '✅ Extracted' && ls -lh \"\$TARGET\" | head -n 5")
                }
                else -> {
                    appendLine("echo '❌ Unsupported format ($format) or action ($action). Use tar.gz or zip.'")
                    appendLine("exit 1")
                }
            }
        }

        return executePrivilegedScript(script, headTruncate = false)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Execution Engine
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Executes a complex script via Base64 injection to completely bypass
     * shell quoting and string interpolation issues.
     */
    private suspend fun executePrivilegedScript(script: String, headTruncate: Boolean = false): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            
            val tmpPath = "/data/local/tmp/omni_fs_${System.currentTimeMillis()}.sh"
            val b64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            
            val writeCmd = "echo ${shellQuote(b64)} | base64 -d > $tmpPath && chmod +x $tmpPath && echo WRITE_OK"
            val writeResult = PrivilegedExecutionManager.executeCommand(writeCmd)
            
            if (writeResult.isFailure || !writeResult.getOrDefault("").contains("WRITE_OK")) {
                return@withContext ToolExecutionResult(
                    "Engine failed to inject file script: ${writeResult.exceptionOrNull()?.message}",
                    isError = true
                )
            }

            val execResult = PrivilegedExecutionManager.executeCommand("sh $tmpPath 2>&1; rm -f $tmpPath")
            
            execResult.fold(
                onSuccess = { rawOutput ->
                    val output = rawOutput.trim().ifBlank { "(no output)" }
                    val isTruncated = output.length > MAX_OUTPUT
                    
                    val finalOutput = if (isTruncated) {
                        if (headTruncate) {
                            // Keep the start (e.g., first 50 grep results)
                            output.take(MAX_OUTPUT) + "\n\n...[TRUNCATED to save tokens]..."
                        } else {
                            // Keep the end (e.g., archive errors)
                            "...[TRUNCATED]...\n\n" + output.takeLast(MAX_OUTPUT)
                        }
                    } else output
                    
                    ToolExecutionResult(finalOutput, truncated = isTruncated)
                },
                onFailure = { ToolExecutionResult("Execution failed: ${it.message}", isError = true) }
            )
        }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun sanitizeShellArg(arg: String): String {
        return "'${arg.replace("'", "'\\''")}'"
    }

    private fun inferArchiveFormat(path: String): String = when {
        path.endsWith(".tar.gz", ignoreCase = true) || path.endsWith(".tgz", ignoreCase = true) -> "tar.gz"
        path.endsWith(".zip", ignoreCase = true) -> "zip"
        path.endsWith(".tar", ignoreCase = true) -> "tar"
        else -> "tar.gz" // Default to native supported
    }

    private fun missingArg(name: String) =
        ToolExecutionResult("Missing required argument: $name", isError = true)
}
