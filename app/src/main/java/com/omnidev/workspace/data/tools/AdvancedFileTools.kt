package com.omnidev.workspace.data.tools

import android.util.Base64
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Advanced root-level file operation tools executed via Shizuku/SU shell.
 *
 * UPGRADES v2:
 * 1. Base64 Script Injection: Prevents regex and path quoting hell.
 * 2. Toybox Bypasses: Android's native `grep` and `du` lack standard GNU flags
 *    (like --include or --max-depth). Rewritten using POSIX-compliant pipelines
 *    (find + xargs) to work flawlessly on native Android.
 * 3. Busybox Auto-Detection: Automatically utilizes busybox if installed for superior tools.
 * 4. NEW: file_info — Full stat+type+magic analysis of any file or directory.
 * 5. NEW: hex_dump  — Inspect binary files byte-by-byte (first N bytes).
 * 6. NEW: diff_files — Compare two files or directories recursively.
 * 7. NEW: symlink_manager — Create, inspect, or remove symbolic links.
 * 8. NEW: copy_move — Copy or move files/dirs with privilege and progress feedback.
 * 9. NEW: secure_delete — Shred sensitive files so they cannot be recovered.
 */
object AdvancedFileTools {

    private const val MAX_OUTPUT = 12_000

    // ─────────────────────────────────────────────────────────────────────
    // Tool Definitions
    // ─────────────────────────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "grep_search",
            description = """
                Recursive grep search across any path using root/Shizuku access.
                Searches file contents for a regex pattern. 
                - Works around Android Toybox `grep` lack of --include via find+xargs pipeline.
                - Supports regex (ERE), case-insensitive search, file-name filtering, and result count limiting.
                - Perfect for finding hardcoded secrets, API keys, config values, tokens, or crash logs.
            """.trimIndent(),
            parameters = listOf(
                ToolParameter("directory", "string", "Directory to search (e.g., /data/data/com.app)", required = true),
                ToolParameter("pattern", "string", "Search pattern (extended regex supported)", required = true),
                ToolParameter("file_filter", "string", "Optional file name glob filter (e.g., '*.xml', '*.json')", required = false),
                ToolParameter("ignore_case", "string", "Set to 'true' for case-insensitive search", required = false),
                ToolParameter("max_results", "string", "Maximum lines to return (default: 50, max: 500)", required = false),
                ToolParameter("context_lines", "string", "Lines of context around each match (e.g., '2')", required = false)
            )
        ),
        ToolDefinition(
            name = "find_files",
            description = """
                Find files by name pattern, type, size, modification time, or permissions.
                - Supports POSIX `find` predicates: -name, -type, -size, -mtime, -perm, -empty.
                - Can output full `ls -la` details for each match.
                - Use this to discover databases, config files, APKs, or world-writable paths.
            """.trimIndent(),
            parameters = listOf(
                ToolParameter("directory", "string", "Directory to search in", required = true),
                ToolParameter("name_pattern", "string", "File name pattern (e.g., '*.db', '*.apk')", required = false),
                ToolParameter("type", "string", "File type: 'f' (file), 'd' (dir), 'l' (symlink)", required = false),
                ToolParameter("min_size", "string", "Minimum size (e.g., '10k', '1M')", required = false),
                ToolParameter("max_size", "string", "Maximum size (e.g., '100M')", required = false),
                ToolParameter("max_depth", "string", "Max depth (default: 5)", required = false),
                ToolParameter("newer_than_days", "string", "Modified within N days", required = false),
                ToolParameter("perm", "string", "Permission mask to match (e.g., '-o+w' for world-writable)", required = false),
                ToolParameter("show_details", "string", "Set to 'true' to output `ls -la` for each result", required = false)
            )
        ),
        ToolDefinition(
            name = "file_permissions",
            description = "Change file permissions (chmod) or ownership (chown) using root access. Supports recursive mode.",
            parameters = listOf(
                ToolParameter("path", "string", "Path to file or directory", required = true),
                ToolParameter("action", "string", "Action: 'chmod' or 'chown'", required = true),
                ToolParameter("value", "string", "e.g., '755', 'u+x', or 'system:system'", required = true),
                ToolParameter("recursive", "string", "Set to 'true' for recursive", required = false)
            )
        ),
        ToolDefinition(
            name = "disk_usage",
            description = "Analyze disk usage of directories to find storage hogs. Sorted by size descending.",
            parameters = listOf(
                ToolParameter("path", "string", "Directory path to analyze", required = true),
                ToolParameter("max_depth", "string", "Max subdirectory depth to display (default: 2)", required = false)
            )
        ),
        ToolDefinition(
            name = "archive_tool",
            description = "Create or extract archives (tar.gz, tgz, tar, zip) using root access. Auto-detects format from extension.",
            parameters = listOf(
                ToolParameter("action", "string", "'create' or 'extract'", required = true),
                ToolParameter("archive_path", "string", "Path to the archive file", required = true),
                ToolParameter("target_path", "string", "For create: directory to archive. For extract: destination.", required = true),
                ToolParameter("format", "string", "tar.gz / tgz / tar / zip (auto-detected from extension if omitted)", required = false)
            )
        ),
        ToolDefinition(
            name = "file_info",
            description = """
                Deep inspection of a file or directory using stat, file-type detection, and checksum.
                Returns: type, size, permissions (octal + symbolic), owner/group, timestamps (atime/mtime/ctime),
                MD5/SHA256 checksums (for files ≤100 MB), symlink target, and inode number.
                Use this before editing sensitive system files or after suspicious file changes.
            """.trimIndent(),
            parameters = listOf(
                ToolParameter("path", "string", "Absolute path to the file or directory", required = true),
                ToolParameter("checksum", "string", "Set to 'true' to compute MD5 and SHA256 checksums (slightly slower)", required = false)
            )
        ),
        ToolDefinition(
            name = "hex_dump",
            description = """
                Read and display the raw binary content of any file as a hex+ASCII dump.
                Useful for inspecting SQLite databases, compiled XML, ELF headers, magic bytes, and encrypted blobs.
                Automatically limits output to avoid flooding the context.
            """.trimIndent(),
            parameters = listOf(
                ToolParameter("path", "string", "Absolute path to the file", required = true),
                ToolParameter("bytes", "string", "Number of bytes to dump (default: 512, max: 8192)", required = false),
                ToolParameter("offset", "string", "Byte offset to start reading from (default: 0)", required = false)
            )
        ),
        ToolDefinition(
            name = "diff_files",
            description = """
                Compare two files or directories recursively.
                - File diff: Shows line-by-line differences (unified diff format).
                - Directory diff: Lists files that exist in one but not the other, plus changed files.
                - Use 'ignore_whitespace=true' to ignore indentation and blank-line differences.
            """.trimIndent(),
            parameters = listOf(
                ToolParameter("path_a", "string", "First file or directory path", required = true),
                ToolParameter("path_b", "string", "Second file or directory path", required = true),
                ToolParameter("ignore_whitespace", "string", "Set to 'true' to ignore whitespace differences", required = false),
                ToolParameter("max_lines", "string", "Max diff lines to return (default: 200)", required = false)
            )
        ),
        ToolDefinition(
            name = "symlink_manager",
            description = "Create, inspect, or remove symbolic links using root access.",
            parameters = listOf(
                ToolParameter("action", "string", "'create', 'inspect', or 'remove'", required = true),
                ToolParameter("link_path", "string", "Path of the symlink to create/inspect/remove", required = true),
                ToolParameter("target_path", "string", "Target the symlink should point to (required for 'create')", required = false)
            )
        ),
        ToolDefinition(
            name = "copy_move",
            description = "Copy or move files and directories using root access. Shows progress for large transfers.",
            parameters = listOf(
                ToolParameter("action", "string", "'copy' or 'move'", required = true),
                ToolParameter("source", "string", "Source file or directory path", required = true),
                ToolParameter("destination", "string", "Destination file or directory path", required = true),
                ToolParameter("overwrite", "string", "Set to 'true' to overwrite existing destination (default: false)", required = false)
            )
        ),
        ToolDefinition(
            name = "secure_delete",
            description = """
                Securely overwrite and delete a file to prevent forensic recovery.
                Uses 3-pass shred (random, zeros, random). Falls back to simple `rm` if shred is unavailable.
                WARNING: This is irreversible. Always confirm the path before executing.
            """.trimIndent(),
            parameters = listOf(
                ToolParameter("path", "string", "Absolute path to the file to shred and delete", required = true),
                ToolParameter("confirm", "string", "Must be set to 'yes' to execute (safety guard)", required = true)
            )
        )
    )

    // ─────────────────────────────────────────────────────────────────────
    // Tool Router
    // ─────────────────────────────────────────────────────────────────────

    suspend fun executeTool(name: String, arguments: Map<String, String>): ToolExecutionResult {
        return when (name) {
            "grep_search"      -> grepSearch(arguments)
            "find_files"       -> findFiles(arguments)
            "file_permissions" -> filePermissions(arguments)
            "disk_usage"       -> diskUsage(arguments)
            "archive_tool"     -> archiveTool(arguments)
            "file_info"        -> fileInfo(arguments)
            "hex_dump"         -> hexDump(arguments)
            "diff_files"       -> diffFiles(arguments)
            "symlink_manager"  -> symlinkManager(arguments)
            "copy_move"        -> copyMove(arguments)
            "secure_delete"    -> secureDelete(arguments)
            else               -> ToolExecutionResult("Unknown advanced file tool: '$name'", isError = true)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Existing Tool Implementations
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun grepSearch(args: Map<String, String>): ToolExecutionResult {
        val directory    = args["directory"]     ?: return missingArg("directory")
        val pattern      = args["pattern"]       ?: return missingArg("pattern")
        val fileFilter   = args["file_filter"]
        val ignoreCase   = args["ignore_case"]?.equals("true", ignoreCase = true) ?: false
        val maxResults   = (args["max_results"]?.toIntOrNull() ?: 50).coerceIn(1, 500)
        val contextLines = args["context_lines"]?.toIntOrNull()?.coerceIn(0, 5)

        // Android Toybox grep lacks --include. We use `find` piped to `xargs grep`.
        // /dev/null forces grep to always print the filename even for single-file inputs.
        val script = buildString {
            appendLine("DIR=${sanitizeShellArg(directory)}")
            appendLine("PATTERN=${sanitizeShellArg(pattern)}")

            val grepFlags = buildString {
                append("-nE")
                if (ignoreCase) append("i")
                if (contextLines != null && contextLines > 0) append(" -C $contextLines")
            }
            val findNameFilter = if (fileFilter != null) "-name ${sanitizeShellArg(fileFilter)}" else ""

            appendLine("MATCH_COUNT=0")
            appendLine("find \"\$DIR\" -type f $findNameFilter -print0 2>/dev/null \\")
            appendLine("  | xargs -0 grep $grepFlags \"\$PATTERN\" /dev/null 2>/dev/null \\")
            appendLine("  | head -n $maxResults")
            appendLine("echo \"\"")
            appendLine("echo \"[grep_search complete — max_results=$maxResults, pattern=\$PATTERN]\"")
        }

        return executePrivilegedScript(script, headTruncate = true)
    }

    private suspend fun findFiles(args: Map<String, String>): ToolExecutionResult {
        val directory    = args["directory"]      ?: return missingArg("directory")
        val namePattern  = args["name_pattern"]
        val type         = args["type"]
        val minSize      = args["min_size"]
        val maxSize      = args["max_size"]
        val maxDepth     = args["max_depth"]?.toIntOrNull() ?: 5
        val newerThanDays = args["newer_than_days"]?.toIntOrNull()
        val perm         = args["perm"]
        val showDetails  = args["show_details"]?.equals("true", ignoreCase = true) ?: false

        val script = buildString {
            append("find ${sanitizeShellArg(directory)} -maxdepth $maxDepth")
            if (namePattern != null) append(" -name ${sanitizeShellArg(namePattern)}")
            if (type != null)        append(" -type ${sanitizeShellArg(type)}")
            if (minSize != null)     append(" -size +${sanitizeShellArg(minSize)}")
            if (maxSize != null)     append(" -size -${sanitizeShellArg(maxSize)}")
            if (newerThanDays != null) append(" -mtime -$newerThanDays")
            if (perm != null)        append(" -perm ${sanitizeShellArg(perm)}")
            append(" 2>/dev/null")
            if (showDetails) {
                append(" -exec ls -la {} \\;")
            } else {
                append(" | head -n 200")
            }
        }

        return executePrivilegedScript(script, headTruncate = true)
    }

    private suspend fun filePermissions(args: Map<String, String>): ToolExecutionResult {
        val path      = args["path"]      ?: return missingArg("path")
        val action    = args["action"]    ?: return missingArg("action")
        val value     = args["value"]     ?: return missingArg("value")
        val recursive = args["recursive"]?.equals("true", ignoreCase = true) ?: false
        val recFlag   = if (recursive) "-R" else ""

        val script = when (action.lowercase()) {
            "chmod" -> "chmod $recFlag ${sanitizeShellArg(value)} ${sanitizeShellArg(path)} && echo '✅ Permissions changed' && ls -ld ${sanitizeShellArg(path)}"
            "chown" -> "chown $recFlag ${sanitizeShellArg(value)} ${sanitizeShellArg(path)} && echo '✅ Ownership changed' && ls -ld ${sanitizeShellArg(path)}"
            else    -> return ToolExecutionResult("Unknown action: '$action'. Use 'chmod' or 'chown'.", isError = true)
        }

        return executePrivilegedScript(script)
    }

    private suspend fun diskUsage(args: Map<String, String>): ToolExecutionResult {
        val path     = args["path"]      ?: return missingArg("path")
        val maxDepth = args["max_depth"]?.toIntOrNull() ?: 2

        // Toybox du uses `-d` not `--max-depth`. Sort by human-readable size descending.
        val script = buildString {
            appendLine("TARGET=${sanitizeShellArg(path)}")
            appendLine("echo \"═══ Disk Usage: \$TARGET ═══\"")
            appendLine("df -h \"\$TARGET\" 2>/dev/null | tail -n1 | awk '{print \"Filesystem: \" \$1 \" | Used: \" \$3 \" | Avail: \" \$4 \" | Use%: \" \$5}'")
            appendLine("echo \"\"")
            appendLine("du -h -d $maxDepth \"\$TARGET\" 2>/dev/null | sort -rh | head -n 60")
        }
        return executePrivilegedScript(script, headTruncate = true)
    }

    private suspend fun archiveTool(args: Map<String, String>): ToolExecutionResult {
        val action      = args["action"]       ?: return missingArg("action")
        val archivePath = args["archive_path"] ?: return missingArg("archive_path")
        val targetPath  = args["target_path"]  ?: return missingArg("target_path")
        val format      = (args["format"] ?: inferArchiveFormat(archivePath)).lowercase()

        val script = buildString {
            appendLine("ARCHIVE=${sanitizeShellArg(archivePath)}")
            appendLine("TARGET=${sanitizeShellArg(targetPath)}")
            appendLine("BB=\"\"; if command -v busybox >/dev/null 2>&1; then BB=\"busybox \"; fi")

            when {
                action == "create" && (format == "tar.gz" || format == "tgz" || format == "tar") -> {
                    val zFlag = if (format == "tar") "" else "z"
                    appendLine("cd \"\$TARGET\" && tar -c${zFlag}f \"\$ARCHIVE\" . && echo '✅ Archive created' && ls -lh \"\$ARCHIVE\"")
                }
                action == "create" && format == "zip" -> {
                    appendLine("if ! command -v zip >/dev/null 2>&1; then echo '❌ zip not found. Use tar.gz instead.'; exit 1; fi")
                    appendLine("cd \"\$TARGET\" && zip -r \"\$ARCHIVE\" . && echo '✅ Zip created' && ls -lh \"\$ARCHIVE\"")
                }
                action == "extract" && (format == "tar.gz" || format == "tgz" || format == "tar") -> {
                    val zFlag = if (format == "tar") "" else "z"
                    appendLine("mkdir -p \"\$TARGET\" && tar -x${zFlag}f \"\$ARCHIVE\" -C \"\$TARGET\" && echo '✅ Extracted' && ls \"\$TARGET\" | head -n 10")
                }
                action == "extract" && format == "zip" -> {
                    appendLine("mkdir -p \"\$TARGET\" && \$BB unzip -o \"\$ARCHIVE\" -d \"\$TARGET\" && echo '✅ Extracted' && ls \"\$TARGET\" | head -n 10")
                }
                else -> {
                    appendLine("echo '❌ Unsupported format ($format) or action ($action). Supported: tar.gz, tar, zip.'")
                    appendLine("exit 1")
                }
            }
        }

        return executePrivilegedScript(script, headTruncate = false)
    }

    // ─────────────────────────────────────────────────────────────────────
    // New Power Tools
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Deep file/directory inspection: stat + type + optional checksums.
     */
    private suspend fun fileInfo(args: Map<String, String>): ToolExecutionResult {
        val path     = args["path"]      ?: return missingArg("path")
        val doCheck  = args["checksum"]?.equals("true", ignoreCase = true) ?: false

        val script = buildString {
            appendLine("P=${sanitizeShellArg(path)}")
            appendLine("echo \"═══ File Info: \$P ═══\"")
            appendLine("if [ ! -e \"\$P\" ] && [ ! -L \"\$P\" ]; then echo '❌ Path does not exist.'; exit 1; fi")
            appendLine("ls -ladn \"\$P\" 2>/dev/null")
            appendLine("echo \"\"")
            // stat output (toybox-compatible subset)
            appendLine("stat \"\$P\" 2>/dev/null || echo '[stat not available]'")
            appendLine("echo \"\"")
            // File type magic
            appendLine("if command -v file >/dev/null 2>&1; then echo \"Type: \$(file -b \"\$P\" 2>/dev/null)\"; fi")
            // Symlink target
            appendLine("if [ -L \"\$P\" ]; then echo \"Symlink → \$(readlink -f \"\$P\" 2>/dev/null)\"; fi")
            // Checksums (only for regular files under 100 MB)
            if (doCheck) {
                appendLine("if [ -f \"\$P\" ]; then")
                appendLine("  SZ=\$(stat -c %s \"\$P\" 2>/dev/null || stat -f %z \"\$P\" 2>/dev/null)")
                appendLine("  if [ -n \"\$SZ\" ] && [ \"\$SZ\" -lt 104857600 ]; then")
                appendLine("    echo \"MD5   : \$(md5sum \"\$P\" 2>/dev/null | awk '{print \$1}')\"")
                appendLine("    echo \"SHA256: \$(sha256sum \"\$P\" 2>/dev/null | awk '{print \$1}')\"")
                appendLine("  else")
                appendLine("    echo '⚠️  File >100 MB — checksum skipped.'")
                appendLine("  fi")
                appendLine("fi")
            }
        }

        return executePrivilegedScript(script)
    }

    /**
     * Hex + ASCII dump of binary file contents.
     */
    private suspend fun hexDump(args: Map<String, String>): ToolExecutionResult {
        val path   = args["path"]   ?: return missingArg("path")
        val bytes  = (args["bytes"]?.toIntOrNull()  ?: 512).coerceIn(1, 8192)
        val offset = (args["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)

        val script = buildString {
            appendLine("P=${sanitizeShellArg(path)}")
            appendLine("if [ ! -f \"\$P\" ]; then echo '❌ File not found or not a regular file.'; exit 1; fi")
            appendLine("echo \"═══ Hex Dump: \$P (offset=$offset, bytes=$bytes) ═══\"")
            // Use xxd if available, fall back to od (which is always present on Android)
            appendLine("if command -v xxd >/dev/null 2>&1; then")
            appendLine("  xxd -s $offset -l $bytes \"\$P\" 2>/dev/null")
            appendLine("elif command -v od >/dev/null 2>&1; then")
            appendLine("  od -A x -t x1z -j $offset -N $bytes \"\$P\" 2>/dev/null")
            appendLine("else")
            appendLine("  dd if=\"\$P\" bs=1 skip=$offset count=$bytes 2>/dev/null | cat -v")
            appendLine("fi")
        }

        return executePrivilegedScript(script, headTruncate = true)
    }

    /**
     * Unified diff between two files or recursive diff between two directories.
     */
    private suspend fun diffFiles(args: Map<String, String>): ToolExecutionResult {
        val pathA           = args["path_a"]           ?: return missingArg("path_a")
        val pathB           = args["path_b"]           ?: return missingArg("path_b")
        val ignoreWhitespace = args["ignore_whitespace"]?.equals("true", ignoreCase = true) ?: false
        val maxLines        = (args["max_lines"]?.toIntOrNull() ?: 200).coerceIn(10, 2000)

        val script = buildString {
            appendLine("A=${sanitizeShellArg(pathA)}")
            appendLine("B=${sanitizeShellArg(pathB)}")
            appendLine("if [ ! -e \"\$A\" ]; then echo '❌ path_a does not exist.'; exit 1; fi")
            appendLine("if [ ! -e \"\$B\" ]; then echo '❌ path_b does not exist.'; exit 1; fi")
            appendLine("echo \"═══ Diff: \$A  ↔  \$B ═══\"")
            val wFlag = if (ignoreWhitespace) "-w" else ""
            // diff exit code 1 = differences found (normal), 2 = error. Use `|| true` to not break the script.
            appendLine("diff -u $wFlag \"\$A\" \"\$B\" 2>&1 | head -n $maxLines || true")
            appendLine("echo \"[diff complete — max_lines=$maxLines]\"")
        }

        return executePrivilegedScript(script, headTruncate = true)
    }

    /**
     * Create, inspect, or remove symbolic links.
     */
    private suspend fun symlinkManager(args: Map<String, String>): ToolExecutionResult {
        val action   = args["action"]    ?: return missingArg("action")
        val linkPath = args["link_path"] ?: return missingArg("link_path")

        val script = when (action.lowercase()) {
            "create" -> {
                val target = args["target_path"] ?: return missingArg("target_path")
                buildString {
                    appendLine("LNK=${sanitizeShellArg(linkPath)}")
                    appendLine("TGT=${sanitizeShellArg(target)}")
                    appendLine("if [ -e \"\$LNK\" ] || [ -L \"\$LNK\" ]; then")
                    appendLine("  echo '⚠️  Link path already exists. Remove it first with action=remove.'")
                    appendLine("  exit 1")
                    appendLine("fi")
                    appendLine("ln -s \"\$TGT\" \"\$LNK\" && echo '✅ Symlink created.' && ls -la \"\$LNK\"")
                }
            }
            "inspect" -> {
                buildString {
                    appendLine("LNK=${sanitizeShellArg(linkPath)}")
                    appendLine("if [ ! -L \"\$LNK\" ]; then echo '❌ Not a symlink (or does not exist).'; exit 1; fi")
                    appendLine("echo \"Symlink    : \$LNK\"")
                    appendLine("echo \"Points to  : \$(readlink \"\$LNK\")\"")
                    appendLine("echo \"Resolved   : \$(readlink -f \"\$LNK\" 2>/dev/null || echo '(target missing)')\"")
                    appendLine("ls -la \"\$LNK\"")
                }
            }
            "remove" -> {
                buildString {
                    appendLine("LNK=${sanitizeShellArg(linkPath)}")
                    appendLine("if [ ! -L \"\$LNK\" ]; then echo '❌ Not a symlink.'; exit 1; fi")
                    appendLine("rm \"\$LNK\" && echo '✅ Symlink removed.'")
                }
            }
            else -> return ToolExecutionResult("Unknown action '$action'. Use 'create', 'inspect', or 'remove'.", isError = true)
        }

        return executePrivilegedScript(script)
    }

    /**
     * Copy or move files/directories with root access.
     */
    private suspend fun copyMove(args: Map<String, String>): ToolExecutionResult {
        val action      = args["action"]      ?: return missingArg("action")
        val source      = args["source"]      ?: return missingArg("source")
        val destination = args["destination"] ?: return missingArg("destination")
        val overwrite   = args["overwrite"]?.equals("true", ignoreCase = true) ?: false

        val script = buildString {
            appendLine("SRC=${sanitizeShellArg(source)}")
            appendLine("DST=${sanitizeShellArg(destination)}")
            appendLine("if [ ! -e \"\$SRC\" ]; then echo '❌ Source does not exist.'; exit 1; fi")

            if (!overwrite) {
                appendLine("if [ -e \"\$DST\" ]; then echo '❌ Destination already exists. Set overwrite=true to force.'; exit 1; fi")
            }

            when (action.lowercase()) {
                "copy" -> {
                    appendLine("cp -rp \"\$SRC\" \"\$DST\" && echo '✅ Copy complete.' && ls -lah \"\$DST\"")
                }
                "move" -> {
                    appendLine("mv \"\$SRC\" \"\$DST\" && echo '✅ Move complete.' && ls -lah \"\$DST\"")
                }
                else -> {
                    appendLine("echo '❌ Unknown action. Use copy or move.'")
                    appendLine("exit 1")
                }
            }
        }

        return executePrivilegedScript(script)
    }

    /**
     * Securely shred and delete a file.
     */
    private suspend fun secureDelete(args: Map<String, String>): ToolExecutionResult {
        val path    = args["path"]    ?: return missingArg("path")
        val confirm = args["confirm"]?.lowercase()?.trim()

        if (confirm != "yes") {
            return ToolExecutionResult(
                "🛑 Safety guard: You must pass confirm='yes' to execute secure_delete on: $path",
                isError = true
            )
        }

        val script = buildString {
            appendLine("P=${sanitizeShellArg(path)}")
            appendLine("if [ ! -f \"\$P\" ]; then echo '❌ Path does not exist or is not a regular file.'; exit 1; fi")
            appendLine("echo \"⚠️  Securely deleting: \$P\"")
            appendLine("if command -v shred >/dev/null 2>&1; then")
            appendLine("  shred -vzn 3 \"\$P\" && rm -f \"\$P\" && echo '✅ File securely shredded (3-pass).'")
            appendLine("else")
            appendLine("  # Fallback: overwrite with zeros then remove")
            appendLine("  SZ=\$(wc -c < \"\$P\")")
            appendLine("  dd if=/dev/zero of=\"\$P\" bs=1 count=\$SZ conv=notrunc 2>/dev/null")
            appendLine("  rm -f \"\$P\" && echo '✅ File zeroed and deleted (shred not available).'")
            appendLine("fi")
        }

        return executePrivilegedScript(script)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Execution Engine
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Executes a shell script via Base64 injection to completely bypass
     * shell quoting and string interpolation issues common on Android/toybox.
     *
     * Flow: encode script → write via `echo | base64 -d` → chmod +x → execute → cleanup.
     */
    private suspend fun executePrivilegedScript(script: String, headTruncate: Boolean = false): ToolExecutionResult =
        withContext(Dispatchers.IO) {

            val tmpPath = "/data/local/tmp/omni_fs_${System.currentTimeMillis()}.sh"
            val b64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

            val writeCmd = "echo ${shellQuote(b64)} | base64 -d > $tmpPath && chmod +x $tmpPath && echo WRITE_OK"
            val writeResult = PrivilegedExecutionManager.executeCommand(writeCmd)

            if (writeResult.isFailure || !writeResult.getOrDefault("").contains("WRITE_OK")) {
                return@withContext ToolExecutionResult(
                    "Engine failed to inject script: ${writeResult.exceptionOrNull()?.message}",
                    isError = true
                )
            }

            val execResult = PrivilegedExecutionManager.executeCommand(
                "sh $tmpPath 2>&1; rc=\$?; rm -f $tmpPath; exit \$rc"
            )

            execResult.fold(
                onSuccess = { rawOutput ->
                    val output = rawOutput.trim().ifBlank { "(no output)" }
                    val isTruncated = output.length > MAX_OUTPUT

                    val finalOutput = if (isTruncated) {
                        if (headTruncate) {
                            output.take(MAX_OUTPUT) + "\n\n...[TRUNCATED — use a narrower filter or reduce max_results]..."
                        } else {
                            "...[TRUNCATED — showing tail]...\n\n" + output.takeLast(MAX_OUTPUT)
                        }
                    } else output

                    ToolExecutionResult(
                        output = finalOutput,
                        truncated = isTruncated,
                        classification = "SUCCESS",
                        backend = "privileged-router"
                    )
                },
                onFailure = { ToolExecutionResult("Execution failed: ${it.message}", isError = true) }
            )
        }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    /** Wraps a shell argument in strong single-quotes, escaping any embedded single-quotes. */
    private fun sanitizeShellArg(arg: String): String = shellQuote(arg)

    /** Single-quote a string for safe injection into POSIX shell. */
    private fun shellQuote(s: String): String = "'${s.replace("'", "'\\''")}'"

    private fun inferArchiveFormat(path: String): String = when {
        path.endsWith(".tar.gz", ignoreCase = true) || path.endsWith(".tgz", ignoreCase = true) -> "tar.gz"
        path.endsWith(".zip", ignoreCase = true)  -> "zip"
        path.endsWith(".tar", ignoreCase = true)  -> "tar"
        else -> "tar.gz"
    }

    private fun missingArg(name: String) =
        ToolExecutionResult("Missing required argument: '$name'", isError = true)
}
