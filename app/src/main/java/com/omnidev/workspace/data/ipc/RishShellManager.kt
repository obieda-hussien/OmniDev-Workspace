package com.omnidev.workspace.data.ipc

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * RishShellManager — executes commands via Shizuku's `rish` (Remote Interactive Shell).
 *
 * ### What rish is
 * `rish` invokes:
 * ```
 * CLASSPATH=<rish_shizuku.dex> /system/bin/app_process /system/bin rikka.shizuku.Shell --sh -c "<cmd>"
 * ```
 * This spawns a full ADB-level shell (UID=`shell`) that is indistinguishable from `adb shell`.
 * Compared to `Shizuku.newProcess()`, rish provides:
 * - Full PATH and environment variable inheritance
 * - True subshell semantics (pipes, redirects, `exec`, `source`, compound commands)
 * - Support for long-running sessions and interactive programs
 * - ADB-equivalent permissions including `READ_LOGS`, `DUMP`, `PACKAGE_USAGE_STATS`, etc.
 *
 * ### DEX file discovery (tried in order)
 * 1. App files dir: `<filesDir>/rish_shizuku.dex` (copied on first successful locate)
 * 2. Standard Shizuku export paths (`/data/local/tmp/`, user_de dir)
 * 3. Extracted from the installed Shizuku APK's assets
 *
 * ### Android 14+ compatibility
 * Since Android 14 (SDK 34), `app_process` refuses to load a writable DEX file.
 * [ensureReadOnlyOnApi34] automatically calls `chmod 400` on the DEX when needed.
 *
 * ### `rish` script
 * [ensureRishScript] writes a portable `rish` shell script to the app's files directory.
 * This script can be invoked directly from a terminal emulator (Termux, ADB, etc.) to
 * open an interactive Shizuku shell session.
 */
class RishShellManager(private val context: Context) {

    companion object {
        private const val DEX_NAME = "rish_shizuku.dex"
        private const val RISH_SCRIPT_NAME = "rish"
        private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
        private const val APP_PROCESS = "/system/bin/app_process"
        private const val SHELL_CLASS = "rikka.shizuku.Shell"

        /** Timeout for individual rish command execution. */
        private const val TIMEOUT_MS = 30_000L

        /** Maximum characters captured from stdout/stderr. */
        private const val MAX_OUTPUT = 8_000

        /**
         * Standard paths where Shizuku exports `rish_shizuku.dex` after the user
         * enables "Use Shizuku in terminal apps" in the Shizuku app settings.
         */
        private val SHIZUKU_EXPORT_PATHS = listOf(
            "/data/local/tmp/rish_shizuku.dex",
            "/data/user_de/0/moe.shizuku.privileged.api/files/rish_shizuku.dex",
            "/data/user/0/moe.shizuku.privileged.api/files/rish_shizuku.dex"
        )

        /**
         * Extra environment variables injected into every rish session for broad
         * ADB-level access. These replicate what `adb shell` typically exports.
         */
        private val RISH_ENV = arrayOf(
            "CLASSPATH=__DEX__",         // replaced dynamically per-invocation
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system",
            "PATH=/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin:/data/local/tmp"
        )
    }

    /** Local copy of the DEX file inside the app's files dir. */
    private val localDex: File get() = File(context.filesDir, DEX_NAME)

    /** Local copy of the `rish` shell script inside the app's files dir. */
    private val localRish: File get() = File(context.filesDir, RISH_SCRIPT_NAME)

    // ─────────────────────────────────────────────────────────────────────
    // Availability
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns true if rish can be used:
     * - `/system/bin/app_process` exists
     * - `rish_shizuku.dex` is locatable (copied locally, at a Shizuku export path, or in Shizuku APK)
     */
    fun isAvailable(): Boolean = runCatching {
        File(APP_PROCESS).exists() && locateDex() != null
    }.getOrDefault(false)

    // ─────────────────────────────────────────────────────────────────────
    // Command execution
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Execute [command] in a rish shell session.
     *
     * @return [Result.success] with trimmed stdout (max [MAX_OUTPUT] chars), or
     *         [Result.failure] with a descriptive exception.
     */
    suspend fun execute(command: String): Result<String> = withContext(Dispatchers.IO) {
        val dex = prepareLocalDex()
            ?: return@withContext Result.failure(
                IllegalStateException(
                    "rish_shizuku.dex not found. " +
                    "Open Shizuku app → Menu → 'Use Shizuku in terminal apps' to export it, " +
                    "then try again. Alternatively, Shizuku v13+ auto-exports to /data/local/tmp/."
                )
            )
        ensureReadOnlyOnApi34(dex)
        return@withContext runCommand(dex, command)
    }

    /**
     * Execute a multi-line shell script via rish.
     *
     * Writes [scriptContent] to a temp file, then runs it. Ensures the file is
     * cleaned up even if the command fails.
     *
     * @return [Result.success] with combined output, or [Result.failure].
     */
    suspend fun executeScript(scriptContent: String): Result<String> = withContext(Dispatchers.IO) {
        val dex = prepareLocalDex()
            ?: return@withContext Result.failure(
                IllegalStateException("rish_shizuku.dex not available.")
            )
        ensureReadOnlyOnApi34(dex)

        val tmpScript = File(context.cacheDir, "rish_script_${System.currentTimeMillis()}.sh")
        return@withContext try {
            tmpScript.writeText("#!/system/bin/sh\n$scriptContent")
            tmpScript.setExecutable(true, false)
            runCommand(dex, "sh ${tmpScript.absolutePath}")
        } finally {
            tmpScript.delete()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Setup helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Ensure the `rish_shizuku.dex` is available in the app's files dir.
     * Copies from Shizuku export paths or extracts from the Shizuku APK.
     *
     * @return The local [File] on success, `null` if the DEX cannot be obtained.
     */
    suspend fun prepareLocalDex(): File? = withContext(Dispatchers.IO) {
        // Already have a local copy?
        if (localDex.exists() && localDex.length() > 0) return@withContext localDex

        // Copy from standard Shizuku export locations
        for (path in SHIZUKU_EXPORT_PATHS) {
            val src = File(path)
            if (src.exists() && src.length() > 0) {
                return@withContext runCatching {
                    src.copyTo(localDex, overwrite = true)
                    localDex
                }.getOrNull()
            }
        }

        // Extract from Shizuku APK assets
        extractFromShizukuApk()
    }

    /**
     * Write (or refresh) the `rish` shell script to the app's files directory.
     *
     * The script is compatible with the official Shizuku `rish` format:
     * it handles Android 14+ write-permission enforcement and accepts any arguments
     * that are passed on to `rikka.shizuku.Shell`.
     *
     * @return The absolute path of the ready-to-run script.
     */
    fun ensureRishScript(): String {
        val dexPath = localDex.absolutePath
        localRish.writeText(buildRishScript(dexPath))
        localRish.setExecutable(true, false)
        return localRish.absolutePath
    }

    /**
     * Status report summarising rish readiness (for the `rish_setup` agent action).
     */
    suspend fun statusReport(): String = withContext(Dispatchers.IO) {
        val appProcessExists = File(APP_PROCESS).exists()
        val localDexExists = localDex.exists() && localDex.length() > 0
        val exportDexPath = SHIZUKU_EXPORT_PATHS.firstOrNull { File(it).exists() }
        val canExtractFromApk = runCatching {
            context.packageManager.getApplicationInfo(SHIZUKU_PKG, 0)
            true
        }.getOrDefault(false)

        buildString {
            appendLine("=== rish status ===")
            appendLine("app_process: ${if (appProcessExists) "✅" else "❌"} $APP_PROCESS")
            appendLine("local dex (${localDex.absolutePath}): ${if (localDexExists) "✅ (${localDex.length() / 1024}KB)" else "❌ not found"}")
            appendLine("Shizuku export dex: ${if (exportDexPath != null) "✅ $exportDexPath" else "❌ not found"}")
            appendLine("Shizuku app installed: ${if (canExtractFromApk) "✅ ($SHIZUKU_PKG)" else "❌"}")
            appendLine("Android 14+ compatibility: ${if (Build.VERSION.SDK_INT >= 34) "⚠️ SDK ${Build.VERSION.SDK_INT} — dex must be chmod 400" else "✅ (SDK < 34)"}")
            appendLine("rish script: ${if (localRish.exists()) "✅ ${localRish.absolutePath}" else "❌ not yet generated (call rish_setup)"}")
            appendLine()
            appendLine("Overall rish availability: ${if (isAvailable()) "✅ READY" else "❌ DEX not found — use rish_setup action to prepare"}")
        }.trimEnd()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Quick DEX locator (no copying)
    // ─────────────────────────────────────────────────────────────────────

    fun locateDex(): File? {
        if (localDex.exists() && localDex.length() > 0) return localDex
        for (path in SHIZUKU_EXPORT_PATHS) {
            val f = File(path)
            if (f.exists() && f.length() > 0) return f
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun extractFromShizukuApk(): File? = runCatching {
        val ai = context.packageManager.getApplicationInfo(SHIZUKU_PKG, 0)
        ZipFile(ai.sourceDir).use { zip ->
            val entry = zip.getEntry("assets/$DEX_NAME")
                ?: zip.getEntry(DEX_NAME)
                ?: return null
            FileOutputStream(localDex).use { out ->
                zip.getInputStream(entry).copyTo(out)
            }
            localDex
        }
    }.getOrNull()

    private fun ensureReadOnlyOnApi34(dex: File) {
        if (Build.VERSION.SDK_INT < 34) return
        if (!dex.canWrite()) return
        // Try Java API first
        dex.setWritable(false, false)
        // If still writable, escalate to chmod
        if (dex.canWrite()) {
            runCatching {
                Runtime.getRuntime().exec(arrayOf("chmod", "400", dex.absolutePath)).waitFor()
            }
        }
    }

    private fun runCommand(dex: File, command: String): Result<String> = runCatching {
        // Build the environment array with the CLASSPATH substituted
        val env = RISH_ENV.map {
            if (it.startsWith("CLASSPATH=")) "CLASSPATH=${dex.absolutePath}" else it
        }.toTypedArray()

        val args = arrayOf(APP_PROCESS, "/system/bin", SHELL_CLASS, "--sh", "-c", command)
        val proc = Runtime.getRuntime().exec(args, env)

        // Read stdout/stderr with size limits BEFORE waitFor() to prevent OS pipe-buffer deadlock.
        // Using bounded reads avoids OOM for commands producing large output.
        val stdout = proc.inputStream.bufferedReader().use { it.readText(MAX_OUTPUT) }
        val stderr = proc.errorStream.bufferedReader().use { it.readText(1_000) }

        val waitThread = Thread { runCatching { proc.waitFor() } }
        waitThread.start()
        waitThread.join(TIMEOUT_MS)
        if (waitThread.isAlive) {
            proc.destroy()
            return Result.failure(RuntimeException("rish command timed out after ${TIMEOUT_MS / 1000}s"))
        }

        val exit = proc.exitValue()
        if (exit == 0) {
            Result.success(stdout.trim().ifBlank { "(no output)" })
        } else {
            Result.failure(RuntimeException("rish exited $exit. stderr: $stderr"))
        }
    }.getOrElse { Result.failure(it) }

    /** Read up to [maxChars] characters from this reader, discarding the rest. */
    private fun java.io.Reader.readText(maxChars: Int): String {
        val sb = StringBuilder(minOf(maxChars, 4096))
        val buf = CharArray(4096)
        var remaining = maxChars
        while (remaining > 0) {
            val read = read(buf, 0, minOf(buf.size, remaining))
            if (read < 0) break
            sb.append(buf, 0, read)
            remaining -= read
        }
        // Drain remaining output to avoid blocking the process on a full pipe buffer
        if (remaining == 0) {
            val drain = CharArray(4096)
            runCatching { while (read(drain) >= 0) { /* discard */ } }
        }
        return sb.toString()
    }

    /**
     * Build the `rish` shell script content, compatible with the official Shizuku rish format.
     * Handles Android 14+ write-permission enforcement.
     */
    private fun buildRishScript(dexPath: String): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("# rish — Shizuku Remote Interactive Shell")
        appendLine("# Generated by OmniDev Workspace — RishShellManager")
        appendLine("# Compatible with official Shizuku rish format")
        appendLine()
        appendLine("""DEX="$dexPath"""")
        appendLine()
        appendLine("""if [ ! -f "${'$'}DEX" ]; then""")
        appendLine("""  echo "Cannot find ${'$'}DEX"""")
        appendLine("""  echo "Please open Shizuku app → 'Use Shizuku in terminal apps' to export it,"""")
        appendLine("""  echo "or ensure Shizuku v13+ is installed (auto-exports to /data/local/tmp/)."""")
        appendLine("""  exit 1""")
        appendLine("fi")
        appendLine()
        appendLine("""SDK=$(getprop ro.build.version.sdk)""")
        appendLine("""if [ "${'$'}SDK" -ge 34 ]; then""")
        appendLine("""  if [ -w "${'$'}DEX" ]; then""")
        appendLine("""    echo "On Android 14+, app_process cannot load a writable dex file."""")
        appendLine("""    echo "Removing write permission from ${'$'}DEX ..."""")
        appendLine("""    chmod 400 "${'$'}DEX"""")
        appendLine("""  fi""")
        appendLine("""  if [ -w "${'$'}DEX" ]; then""")
        appendLine("""    echo "ERROR: Cannot remove write permission from ${'$'}DEX"""")
        appendLine("""    echo "You can copy it to a private directory: cp ${'$'}DEX /data/data/<pkg>/files/rish_shizuku.dex"""")
        appendLine("""    exit 1""")
        appendLine("""  fi""")
        appendLine("fi")
        appendLine()
        appendLine("""CLASSPATH="${'$'}DEX" $APP_PROCESS /system/bin $SHELL_CLASS "${'$'}@"""")
    }
}
