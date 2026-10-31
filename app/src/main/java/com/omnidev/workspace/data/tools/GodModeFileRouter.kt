package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Extended Android filesystem router for God Mode.
 *
 * Direct app access is attempted first. When it is unavailable, the router uses the
 * configured Android shell backend (normally Shizuku) for a second attempt. That backend
 * still runs with its actual UID and remains subject to SELinux, app-data isolation,
 * encryption state and filesystem mount flags. This router does not bypass Android's
 * sandbox and does not turn read-only system partitions writable.
 *
 * The privileged path list is a routing hint, not proof of access. The actual command
 * result is authoritative and permission failures are returned to the agent and user.
 * God Mode must be explicitly enabled before any operation is attempted.
 *
 * All shell paths are single-quoted and text written through the shell is base64-encoded
 * to preserve special characters safely.
 */
object GodModeFileRouter {

    // ──────────────────────────────────────────────
    //  Privileged path prefixes
    // ──────────────────────────────────────────────

    /**
     * Paths commonly requiring the Android shell identity instead of the app UID.
     * This list routes an attempt; it does not promise that SELinux or mount policy permits it.
     */
    /** Maximum bytes to read via shell (to guard context window). */
    private const val MAX_READ_BYTES = 524_288L // 512 KB

    // ──────────────────────────────────────────────
    //  Public API
    // ──────────────────────────────────────────────

    /**
     * Read the full content of a file at [path].
     *
     * Strategy (God Mode ON):
     * 1. If the path is directly readable via [java.io.File], return it immediately.
     * 2. Otherwise escalate to `cat <path>` via the privileged shell backend.
     *
     * @param path    Absolute file path.
     * @param godMode Must be `true`; call is a no-op otherwise.
     */
    suspend fun readFile(path: String, godMode: Boolean): GodModeResult {
        if (!godMode) return GodModeResult.GodModeDisabled

        val canonical = resolveCanonical(path)
        val file = File(canonical)

        // Fast path — process can read it directly.
        val isExternalStorage = canonical.startsWith("/storage/emulated/0/")

        if (!requiresEscalation(canonical) && file.exists() && file.canRead()) {
            return runCatching {
                val bytes = file.inputStream().use { stream ->
                    val buffer = ByteArray(MAX_READ_BYTES.toInt() + 1)
                    var count = 0
                    while (count < buffer.size) {
                        val read = stream.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        count += read
                    }
                    buffer.copyOf(count)
                }
                if (bytes.size > MAX_READ_BYTES) {
                    val preview = bytes.take(MAX_READ_BYTES.toInt()).toByteArray().decodeToString()
                    GodModeResult.Success(
                        content = "$preview\n\n[TRUNCATED: file exceeds ${MAX_READ_BYTES / 1024}KB, showing first ${MAX_READ_BYTES / 1024}KB]",
                        escalated = false
                    )
                } else {
                    GodModeResult.Success(content = bytes.decodeToString(), escalated = false)
                }
            }.getOrElse { ex ->
                if (isPermissionDenied(ex) || ex is SecurityException || isExternalStorage) {
                    // Silently escalate — the direct attempt failed on permissions, or it's external storage.
                    readViaShell(canonical)
                } else {
                    GodModeResult.Failure("Direct read failed: ${ex.message}")
                }
            }
        }

        // Escalation path — privileged namespace or not directly accessible.
        return readViaShell(canonical)
    }

    /**
     * Write [content] (UTF-8 text) to a file at [path].
     *
     * Strategy (God Mode ON):
     * 1. Ensure parent directory exists (create via shell if needed).
     * 2. If the directory is writable by the process, write directly.
     * 3. Otherwise escalate via `echo -n <b64_content> | base64 -d > <path>`.
     *
     * @param path      Absolute target path.
     * @param content   UTF-8 text to write.
     * @param append    If true, appends to the file; otherwise overwrites.
     * @param godMode   Must be `true`; call is a no-op otherwise.
     */
    suspend fun writeFile(
        path: String,
        content: String,
        append: Boolean = false,
        godMode: Boolean
    ): GodModeResult {
        if (!godMode) return GodModeResult.GodModeDisabled

        val canonical = resolveCanonical(path)
        val file = File(canonical)
        val parent = file.parentFile

        val isExternalStorage = canonical.startsWith("/storage/emulated/0/")

        // Fast path — try direct write if parent is accessible.
        if (!requiresEscalation(canonical) && (!isExternalStorage || file.canWrite() || (parent?.canWrite() == true))) {
            val directResult = runCatching {
                parent?.mkdirs()
                if (append) file.appendText(content, Charsets.UTF_8)
                else         file.writeText(content, Charsets.UTF_8)
                GodModeResult.Success(
                    content = "Wrote ${content.length} chars to $canonical",
                    escalated = false
                )
            }.getOrNull()
            if (directResult != null) return directResult
        }

        // Escalation path.
        return writeViaShell(canonical, content, append)
    }

    /**
     * Delete a file or directory at [path] using God Mode.
     *
     * Uses `rm -rf` via the privileged shell backend when direct deletion fails.
     *
     * @param path      Absolute path to the file or directory.
     * @param recursive If true, recursively deletes directories.
     * @param godMode   Must be `true`; call is a no-op otherwise.
     */
    suspend fun deleteFile(
        path: String,
        recursive: Boolean = false,
        godMode: Boolean
    ): GodModeResult {
        if (!godMode) return GodModeResult.GodModeDisabled

        val canonical = resolveCanonical(path)
        val file = File(canonical)

        val isExternalStorage = canonical.startsWith("/storage/emulated/0/")

        // Fast path.
        if (!requiresEscalation(canonical) && file.exists() && file.canWrite()) {
            return runCatching {
                val deleted = if (recursive) file.deleteRecursively() else file.delete()
                if (deleted) {
                    GodModeResult.Success("Deleted $canonical", escalated = false)
                } else {
                    // A false return proves the app-UID deletion did not complete. Try the
                    // configured privileged backend once rather than reporting a false dead end.
                    deleteViaShell(canonical, recursive)
                }
            }.getOrElse { ex ->
                if (isPermissionDenied(ex) || ex is SecurityException || isExternalStorage) {
                    deleteViaShell(canonical, recursive)
                } else {
                    GodModeResult.Failure("Direct delete failed: ${ex.message}")
                }
            }
        }

        return deleteViaShell(canonical, recursive)
    }

    /**
     * Copy a file from [sourcePath] to [destPath] using God Mode.
     *
     * Tries a direct JVM copy first; escalates to `cp -p <src> <dest>` via shell.
     *
     * @param godMode Must be `true`; call is a no-op otherwise.
     */
    suspend fun copyFile(
        sourcePath: String,
        destPath: String,
        godMode: Boolean
    ): GodModeResult {
        if (!godMode) return GodModeResult.GodModeDisabled

        val src = resolveCanonical(sourcePath)
        val dst = resolveCanonical(destPath)

        val isSrcExternal = src.startsWith("/storage/emulated/0/")
        val isDstExternal = dst.startsWith("/storage/emulated/0/")

        val srcFile = File(src)
        val dstFile = File(dst)

        if (!requiresEscalation(src) && !requiresEscalation(dst) &&
            (!isSrcExternal || srcFile.canRead()) &&
            (!isDstExternal || dstFile.canWrite() || dstFile.parentFile?.canWrite() == true)) {
            val result = runCatching {
                dstFile.parentFile?.mkdirs()
                srcFile.copyTo(dstFile, overwrite = true)
                GodModeResult.Success("Copied $src → $dst", escalated = false)
            }.getOrNull()
            if (result != null) return result
        }

        return executeShell("cp -p ${shellQuote(src)} ${shellQuote(dst)} && echo 'OK'",
            successMsg = "Copied $src → $dst")
    }

    /**
     * List the contents of a directory at [path] using God Mode.
     *
     * Tries [File.listFiles] first; escalates to `ls -la` via shell for privileged dirs.
     *
     * @param godMode Must be `true`; call is a no-op otherwise.
     */
    suspend fun listDirectory(path: String, godMode: Boolean): GodModeResult {
        if (!godMode) return GodModeResult.GodModeDisabled

        val canonical = resolveCanonical(path)
        val dir = File(canonical)

        val isExternalStorage = canonical.startsWith("/storage/emulated/0/")

        if (!requiresEscalation(canonical) && dir.isDirectory && dir.canRead()) {
            // File.listFiles() returns null (rather than throwing) on some permission and
            // filesystem failures. Null is not an empty directory: fall through to Shizuku.
            val listed = runCatching { dir.listFiles() }.getOrNull()
            if (listed != null) {
                val entries = listed.joinToString("\n") { child ->
                    val type = when { child.isDirectory -> "d"; child.isFile -> "f"; else -> "?" }
                    val size = if (child.isFile) "${child.length()}B" else "-"
                    "[$type] ${child.name} ($size)"
                }.ifEmpty { "(empty)" }
                return GodModeResult.Success("Contents of $canonical:\n$entries", escalated = false)
            }
        }

        return executeShell("ls -la ${shellQuote(canonical)} 2>&1",
            successMsg = "Directory listing via Android shell backend (Shizuku first)")
    }

    /**
     * Stat a file — returns permissions, owner, size, and modification time.
     *
     * @param godMode Must be `true`; call is a no-op otherwise.
     */
    suspend fun statFile(path: String, godMode: Boolean): GodModeResult {
        if (!godMode) return GodModeResult.GodModeDisabled
        val canonical = resolveCanonical(path)

        val isExternalStorage = canonical.startsWith("/storage/emulated/0/")
        val file = File(canonical)
        if (isExternalStorage && file.exists() && file.canRead()) {
            return runCatching {
                val size = file.length()
                val lastMod = file.lastModified()
                val perms = buildString {
                    append(if (file.isDirectory) "d" else "-")
                    append(if (file.canRead()) "r" else "-")
                    append(if (file.canWrite()) "w" else "-")
                    append(if (file.canExecute()) "x" else "-")
                }
                GodModeResult.Success(
                    "File: $canonical\nSize: $size\nPermissions: $perms\nLast Modified: $lastMod",
                    escalated = false
                )
            }.getOrElse {
                executeShell(
                    "stat ${shellQuote(canonical)} 2>&1 && echo '---' && " +
                    "ls -la ${shellQuote(canonical)} 2>&1",
                    successMsg = "stat $canonical"
                )
            }
        }

        return executeShell(
            "stat ${shellQuote(canonical)} 2>&1 && echo '---' && " +
            "ls -la ${shellQuote(canonical)} 2>&1",
            successMsg = "stat $canonical"
        )
    }

    // ──────────────────────────────────────────────
    //  Internal — shell escalation helpers
    // ──────────────────────────────────────────────

    private suspend fun readViaShell(canonical: String): GodModeResult {
        // Use `cat` to read the raw bytes and print them to stdout.
        // For binary files this may produce garbled output — callers dealing with
        // binary content should use `base64 <path>` directly via root_shell_tool.
        val cmd = "cat ${shellQuote(canonical)} 2>&1"
        return when (val result = PrivilegedExecutionManager.executeCommand(cmd)) {
            is Result -> {
                result.fold(
                    onSuccess = { output ->
                        val truncated = output.length > MAX_READ_BYTES
                        val content = if (truncated)
                            output.take(MAX_READ_BYTES.toInt()) +
                            "\n\n[TRUNCATED at ${MAX_READ_BYTES / 1024}KB]"
                        else output
                        GodModeResult.Success(content = content, escalated = true,
                            note = "🔓 Read via the configured Android shell backend")
                    },
                    onFailure = { ex ->
                        GodModeResult.Failure(
                            "Privileged read failed for $canonical: ${ex.message}"
                        )
                    }
                )
            }
        }
    }

    private suspend fun writeViaShell(
        canonical: String,
        content: String,
        append: Boolean
    ): GodModeResult {
        // Encode the content as base64 to safely pass arbitrary bytes / special chars
        // through a one-liner shell command without breaking quoting.
        val b64 = android.util.Base64.encodeToString(
            content.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        val redirector = if (append) ">>" else ">"
        val parentDir = File(canonical).parent?.let { shellQuote(it) } ?: ""
        val mkdirCmd = if (parentDir.isNotBlank()) "mkdir -p $parentDir && " else ""
        val cmd = "${mkdirCmd}echo ${shellQuote(b64)} | base64 -d $redirector ${shellQuote(canonical)}" +
                  " && echo 'WRITE_OK'"

        return when (val result = PrivilegedExecutionManager.executeCommand(cmd)) {
            is Result -> {
                result.fold(
                    onSuccess = {
                        GodModeResult.Success(
                            content = "Wrote ${content.length} chars to $canonical",
                            escalated = true,
                            note = "🔓 Written via the configured Android shell backend"
                        )
                    },
                    onFailure = { ex ->
                        GodModeResult.Failure(
                            "Privileged write failed for $canonical: ${ex.message}"
                        )
                    }
                )
            }
        }
    }

    private suspend fun deleteViaShell(canonical: String, recursive: Boolean): GodModeResult {
        val flags = if (recursive) "-rf" else "-f"
        return executeShell("rm $flags ${shellQuote(canonical)} && echo 'DELETE_OK'",
            successMsg = "Deleted $canonical via shell")
    }

    private suspend fun executeShell(cmd: String, successMsg: String): GodModeResult =
        withContext(Dispatchers.IO) {
                PrivilegedExecutionManager.executeCommand(cmd).fold(
                onSuccess = { output ->
                    GodModeResult.Success(
                        content = output.ifBlank { successMsg },
                        escalated = true,
                        note = "🔓 Executed via the configured Android shell backend"
                    )
                },
                onFailure = { ex ->
                    GodModeResult.Failure("Shell execution failed: ${ex.message}")
                }
            )
        }

    // ──────────────────────────────────────────────
    //  Internal — helpers
    // ──────────────────────────────────────────────

    /**
     * Returns true when [canonical] belongs to a known protected/system namespace.
     * This only chooses the shell route; the shell UID still cannot bypass SELinux,
     * application-private storage boundaries or read-only mounts.
     */
    private fun requiresEscalation(canonical: String): Boolean =
        AndroidProtectedPathPolicy.requiresShell(canonical)

    /**
     * Resolves a (possibly relative or symlink-containing) path to its canonical form.
     * Falls back to the original path string if [File.canonicalPath] throws.
     */
    private fun resolveCanonical(path: String): String =
        runCatching { File(path).canonicalPath }.getOrDefault(path)

    /**
     * Returns true if [ex] carries a message consistent with a POSIX EACCES (permission
     * denied) or EPERM error. Used to auto-escalate after a failed direct attempt.
     */
    private fun isPermissionDenied(ex: Throwable): Boolean {
        val msg = ex.message?.lowercase() ?: return false
        return msg.contains("permission denied") ||
               msg.contains("eacces") ||
               msg.contains("eperm") ||
               msg.contains("access denied") ||
               msg.contains("operation not permitted")
    }

    /**
     * POSIX single-quote escaping.
     * Wraps [arg] in single quotes and escapes any embedded single quotes with `'\''`.
     */
    private fun shellQuote(arg: String): String =
        "'${arg.replace("'", "'\\''")}'"
}

// ──────────────────────────────────────────────────────────────────────────────
//  Result type
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Sealed result type for all [GodModeFileRouter] operations.
 */
sealed class GodModeResult {

    /**
     * The operation completed successfully.
     *
     * @param content   The output payload — file content for reads, status message for writes/deletes.
     * @param escalated `true` if the operation was routed through the privileged shell backend.
     * @param note      Optional human-readable annotation (e.g., "🔓 via Shizuku").
     */
    data class Success(
        val content: String,
        val escalated: Boolean,
        val note: String? = null
    ) : GodModeResult()

    /** The operation failed. */
    data class Failure(val reason: String) : GodModeResult()

    /** God Mode was not enabled; the operation was not attempted. */
    object GodModeDisabled : GodModeResult()

    /** Convert to a [ToolExecutionResult] for direct use in tool execute() methods. */
    fun toToolResult(): ToolExecutionResult = when (this) {
        is Success -> {
            val prefix = note?.let { "$it\n" } ?: ""
            ToolExecutionResult(output = "$prefix$content")
        }
        is Failure -> ToolExecutionResult(output = "❌ God Mode file operation failed: $reason", isError = true)
        is GodModeDisabled -> ToolExecutionResult(
            output = "God Mode is not enabled. Enable God Mode in Settings to access privileged file paths.",
            isError = true
        )
    }
}
