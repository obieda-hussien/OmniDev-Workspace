package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * GodModeFileRouter — True Unrestricted File System Access Engine.
 *
 * ### Design
 * When God Mode is ON, normal `java.io.File` operations are attempted first for speed.
 * If the path is determined to be **privileged** (i.e. it lives under a namespace that
 * a normal app process cannot read or write — `/data/data/`, `/data/user/`, `/system/`,
 * `/proc/`, `/sys/` etc.), or if a plain-Java I/O attempt throws a [SecurityException]
 * or [java.io.IOException] consistent with a permission denial, the router **automatically
 * escalates** the operation to the Shizuku / rish / root backend via
 * [PrivilegedExecutionManager], using raw POSIX shell utilities (`cat`, `echo`, `cp`,
 * `rm`, `ls`, `stat`, etc.) to bypass the Android framework's sandbox.
 *
 * ### Escalation heuristic
 * A path is considered **privileged** when its canonical prefix matches any entry in
 * [PRIVILEGED_PATH_PREFIXES]. File existence / readability is checked first so that
 * we do not escalate unnecessarily for paths the process legitimately owns (e.g.
 * the app's own `/data/data/<this_package>/` files which are app-accessible).
 *
 * ### Security & audit
 * - God Mode must be explicitly enabled by the user (`godModeEnabled = true`).
 * - Every privileged escalation is annotated in the returned [GodModeResult] so the
 *   caller can surface a clear indication (e.g., 🔓 via Shizuku) in the UI console.
 * - Calls without God Mode always short-circuit to [GodModeResult.GodModeDisabled].
 *
 * ### Shell quoting
 * All path arguments are POSIX single-quoted via [shellQuote] to prevent injection.
 * Content written to files is base64-encoded before passing through `sh -c` to handle
 * arbitrary binary payloads and special characters safely.
 */
object GodModeFileRouter {

    // ──────────────────────────────────────────────
    //  Privileged path prefixes
    // ──────────────────────────────────────────────

    /**
     * Path prefixes that almost certainly require elevated privileges.
     * Sorted longest-first so the most specific prefix wins.
     */
    private val PRIVILEGED_PATH_PREFIXES = listOf(
        "/data/data/",
        "/data/user/",
        "/data/user_de/",
        "/data/system/",
        "/data/misc/",
        "/data/local/",
        "/data/app/",
        "/data/dalvik-cache/",
        "/system/",
        "/vendor/",
        "/proc/",
        "/sys/",
        "/dev/",
        "/acct/",
        "/apex/",
        "/odm/",
        "/oem/",
        "/product/"
    )

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
                val bytes = file.readBytes()
                if (bytes.size > MAX_READ_BYTES) {
                    val preview = bytes.take(MAX_READ_BYTES.toInt()).toByteArray().decodeToString()
                    GodModeResult.Success(
                        content = "$preview\n\n[TRUNCATED: file is ${bytes.size / 1024}KB, showing first ${MAX_READ_BYTES / 1024}KB]",
                        escalated = false
                    )
                } else {
                    GodModeResult.Success(content = bytes.decodeToString(), escalated = false)
                }
            }.getOrElse { ex ->
                if (isExternalStorage || (!isPermissionDenied(ex) && ex !is SecurityException)) {
                    GodModeResult.Failure("Direct read failed: ${ex.message}")
                } else {
                    // Silently escalate — the direct attempt failed on permissions.
                    readViaShell(canonical)
                }
            }
        } else if (isExternalStorage && file.exists() && !file.canRead()) {
            return readViaShell(canonical)
        } else if (isExternalStorage && !file.exists()) {
            return GodModeResult.Failure("File not found: $canonical")
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
            }.getOrElse { ex ->
                if (isExternalStorage) GodModeResult.Failure("Direct write failed: ${ex.message}") else null
            }
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
                if (deleted) GodModeResult.Success("Deleted $canonical", escalated = false)
                else GodModeResult.Failure("Direct delete returned false for $canonical")
            }.getOrElse { ex ->
                if (isExternalStorage || (!isPermissionDenied(ex) && ex !is SecurityException)) {
                    GodModeResult.Failure("Direct delete failed: ${ex.message}")
                } else {
                    deleteViaShell(canonical, recursive)
                }
            }
        } else if (isExternalStorage && file.exists() && !file.canWrite()) {
            return deleteViaShell(canonical, recursive)
        } else if (isExternalStorage && !file.exists()) {
            return GodModeResult.Failure("File not found: $canonical")
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
            }.getOrElse { ex ->
                if (isSrcExternal || isDstExternal) GodModeResult.Failure("Direct copy failed: ${ex.message}") else null
            }
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

        if (!requiresEscalation(canonical) && dir.canRead()) {
            return runCatching {
                val entries = dir.listFiles()?.joinToString("\n") { f ->
                    val type = when { f.isDirectory -> "d"; f.isFile -> "f"; else -> "?" }
                    val size = if (f.isFile) "${f.length()}B" else "-"
                    "[$type] ${f.name} ($size)"
                } ?: "(empty)"
                GodModeResult.Success("Contents of $canonical:\n$entries", escalated = false)
            }.getOrElse { ex ->
                if (isExternalStorage) {
                    GodModeResult.Failure("Direct directory listing failed: ${ex.message}")
                } else {
                    executeShell("ls -la ${shellQuote(canonical)} 2>&1",
                        successMsg = "Directory listing via shell")
                }
            }
        } else if (isExternalStorage && !dir.canRead()) {
            return executeShell("ls -la ${shellQuote(canonical)} 2>&1",
                successMsg = "Directory listing via shell")
        }

        return executeShell("ls -la ${shellQuote(canonical)} 2>&1",
            successMsg = "Directory listing via shell (privileged)")
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

    @Volatile
    private var appOpsGranted = false

    private suspend fun ensureAppOpsGranted() {
        if (!appOpsGranted) {
            appOpsGranted = true
            PrivilegedExecutionManager.executeCommand("appops set com.android.shell MANAGE_EXTERNAL_STORAGE allow")
            PrivilegedExecutionManager.executeCommand("appops set com.android.shell READ_EXTERNAL_STORAGE allow")
            PrivilegedExecutionManager.executeCommand("appops set com.android.shell WRITE_EXTERNAL_STORAGE allow")
        }
    }


    private suspend fun readViaShell(canonical: String): GodModeResult {
        ensureAppOpsGranted()
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
                            note = "🔓 Read via privileged shell (Shizuku/rish/root)")
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
        ensureAppOpsGranted()
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
                            note = "🔓 Written via privileged shell (Shizuku/rish/root)"
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
        ensureAppOpsGranted()
        val flags = if (recursive) "-rf" else "-f"
        return executeShell("rm $flags ${shellQuote(canonical)} && echo 'DELETE_OK'",
            successMsg = "Deleted $canonical via shell")
    }

    private suspend fun executeShell(cmd: String, successMsg: String): GodModeResult =
        withContext(Dispatchers.IO) {
            ensureAppOpsGranted()
            PrivilegedExecutionManager.executeCommand(cmd).fold(
                onSuccess = { output ->
                    GodModeResult.Success(
                        content = output.ifBlank { successMsg },
                        escalated = true,
                        note = "🔓 Executed via privileged shell (Shizuku/rish/root)"
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
     * Returns true when [canonical] starts with a prefix known to require
     * elevated privileges on an unmodified Android system.
     *
     * Note: This is a conservative heuristic. Some prefixes (like `/data/local/tmp`)
     * are actually world-writable on some devices; however, we always escalate for
     * safety since the cost of an unnecessary shell call is negligible compared to
     * the cost of a confusing permission-denied error.
     */
    private fun requiresEscalation(canonical: String): Boolean =
        PRIVILEGED_PATH_PREFIXES.any { prefix -> canonical.startsWith(prefix) }

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
