package com.omnidev.workspace.data.debug

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A single captured log entry — either a crash report or a manually appended error/warning.
 */
data class DebugEntry(
    /** Unique filename — doubles as stable key in the list. */
    val filename: String,
    /** Human-readable timestamp when this entry was recorded. */
    val timestamp: String,
    /** CRASH | ERROR | WARNING | INFO */
    val level: String,
    /** Short one-line description (exception class or custom tag). */
    val title: String,
    /** Full log text including device info and stack trace. */
    val body: String
)

/**
 * Singleton that manages debug log files stored in the app's private files directory.
 *
 * Log directory: `<filesDir>/debug_logs/`
 *
 * Files:
 *  - `crash_<timestamp>.log`  — written by [CrashHandler] on unhandled exceptions
 *  - `error_<timestamp>.log`  — written by [appendError] for caught exceptions
 *  - `warn_<timestamp>.log`   — written by [appendWarning] for soft warnings
 */
object DebugLogManager {

    private const val LOG_DIR = "debug_logs"
    private const val CRASH_REDIRECT_MARKER_FILE = "pending_crash_redirect.flag"
    private const val DATE_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS"
    private const val FILE_DATE_FORMAT = "yyyyMMdd_HHmmss_SSS"
    // Single-thread executor for async log writes. This executor lives for the entire
    // process lifetime (same as the Application object) and is intentionally not shut
    // down — the OS reclaims all threads when the process exits.
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private lateinit var logDir: File

    fun init(context: Context) {
        logDir = File(context.filesDir, LOG_DIR).apply { mkdirs() }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Public write API
    // ──────────────────────────────────────────────────────────────────────

    /** Write a crash log file synchronously (called from crash handler). */
    fun writeCrash(throwable: Throwable) {
        val ts = timestamp()
        val fileName = "crash_${fileTimestamp()}.log"
        writeFile(
            name = fileName,
            level = "CRASH",
            title = throwable.javaClass.simpleName,
            detail = stackTrace(throwable),
            ts = ts
        )
    }

    /** Mark that the next launch should open the debug console. */
    fun markPendingCrashRedirect() {
        if (!::logDir.isInitialized) return
        val marker = File(logDir, CRASH_REDIRECT_MARKER_FILE)
        if (!marker.exists()) {
            marker.createNewFile()
        }
    }

    /**
     * Returns true once after a fatal crash was recorded, then clears the marker.
     * Intended to route the next app launch directly to debug diagnostics.
     */
    fun consumePendingCrashRedirect(): Boolean {
        if (!::logDir.isInitialized) return false
        val marker = File(logDir, CRASH_REDIRECT_MARKER_FILE)
        if (!marker.exists()) return false
        if (!marker.delete() && marker.exists()) return false
        return true
    }

    /** Append a caught exception as an error log entry (async). */
    fun appendError(tag: String, throwable: Throwable) {
        ioExecutor.execute {
            writeFile(
                name = "error_${fileTimestamp()}.log",
                level = "ERROR",
                title = "[$tag] ${throwable.javaClass.simpleName}",
                detail = stackTrace(throwable)
            )
        }
    }

    /** Append a custom warning message (async). */
    fun appendWarning(tag: String, message: String) {
        ioExecutor.execute {
            writeFile(
                name = "warn_${fileTimestamp()}.log",
                level = "WARNING",
                title = "[$tag] $message",
                detail = message
            )
        }
    }

    /** Append a custom info message (async). */
    fun appendInfo(tag: String, message: String) {
        ioExecutor.execute {
            writeFile(
                name = "info_${fileTimestamp()}.log",
                level = "INFO",
                title = "[$tag] $message",
                detail = message
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Public read API
    // ──────────────────────────────────────────────────────────────────────

    /** Read all log entries sorted newest-first. */
    fun readAll(): List<DebugEntry> {
        if (!::logDir.isInitialized) return emptyList()
        return logDir.listFiles { f -> f.extension == "log" }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { parseFile(it) }
            ?: emptyList()
    }

    /** Delete all log files. */
    fun clearAll() {
        if (!::logDir.isInitialized) return
        logDir.listFiles()?.forEach { it.delete() }
    }

    /** Return the full text of all logs concatenated — for share/export. */
    fun exportAll(): String {
        return readAll().joinToString("\n\n${"-".repeat(80)}\n\n") { it.body }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Device info
    // ──────────────────────────────────────────────────────────────────────

    /** Collect static device and OS information. */
    fun deviceInfo(): String = buildString {
        appendLine("═══ DEVICE INFORMATION ═══")
        appendLine("Manufacturer : ${Build.MANUFACTURER}")
        appendLine("Brand        : ${Build.BRAND}")
        appendLine("Model        : ${Build.MODEL}")
        appendLine("Product      : ${Build.PRODUCT}")
        appendLine("Device       : ${Build.DEVICE}")
        appendLine("Board        : ${Build.BOARD}")
        appendLine("Hardware     : ${Build.HARDWARE}")
        appendLine("ABI          : ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("SDK          : ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
        appendLine("Codename     : ${Build.VERSION.CODENAME}")
        appendLine("Incremental  : ${Build.VERSION.INCREMENTAL}")
        appendLine("Fingerprint  : ${Build.FINGERPRINT}")
        appendLine("Build type   : ${Build.TYPE}")
        appendLine("Tags         : ${Build.TAGS}")
        append("Build ID     : ${Build.ID}")
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Internals
    // ──────────────────────────────────────────────────────────────────────

    private fun writeFile(
        name: String,
        level: String,
        title: String,
        detail: String,
        ts: String = timestamp()
    ) {
        if (!::logDir.isInitialized) return
        val content = buildString {
            appendLine("LEVEL     : $level")
            appendLine("TIMESTAMP : $ts")
            appendLine("TITLE     : $title")
            appendLine()
            appendLine(deviceInfo())
            appendLine()
            appendLine("═══ DETAILS ═══")
            append(detail)
        }
        File(logDir, name).writeText(content, Charsets.UTF_8)
    }

    private fun parseFile(file: File): DebugEntry? {
        return try {
            val text = file.readText(Charsets.UTF_8)
            val level = file.name
                .substringBefore("_")
                .uppercase()
                .let { l ->
                    when {
                        l.startsWith("CRASH") -> "CRASH"
                        l.startsWith("ERROR") -> "ERROR"
                        l.startsWith("WARN") -> "WARNING"
                        else -> "INFO"
                    }
                }
            val titleLine = text.lineSequence()
                .firstOrNull { it.startsWith("TITLE") }
                ?.substringAfter(": ")?.trim()
                ?: file.name
            val tsLine = text.lineSequence()
                .firstOrNull { it.startsWith("TIMESTAMP") }
                ?.substringAfter(": ")?.trim()
                ?: "--"
            DebugEntry(
                filename = file.name,
                timestamp = tsLine,
                level = level,
                title = titleLine,
                body = text
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat(DATE_FORMAT, Locale.US).format(Date())

    private fun fileTimestamp(): String =
        SimpleDateFormat(FILE_DATE_FORMAT, Locale.US).format(Date())

    private fun stackTrace(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}
