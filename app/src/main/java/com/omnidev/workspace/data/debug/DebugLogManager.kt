package com.omnidev.workspace.data.debug

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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
    private val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val revision: kotlinx.coroutines.flow.StateFlow<Long> = changes

    private const val LOG_DIR = "debug_logs"
    private const val CRASH_REDIRECT_MARKER_FILE = "pending_crash_redirect.flag"
    private const val DATE_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS"
    private const val FILE_DATE_FORMAT = "yyyyMMdd_HHmmss_SSS"
    private val ioExecutor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(128), ThreadPoolExecutor.DiscardOldestPolicy())
    private val sequence = AtomicLong()
    private val writeLock = Any()
    private const val MAX_LOGS = 100
    private const val MAX_DETAIL_CHARS = 64 * 1024
    private lateinit var logDir: File
    @Volatile private var legacyLogDir: File? = null

    /** Diagnostics must not start services or depend on credential storage. */
    fun init(context: Context) {
        val storage = if (Build.VERSION.SDK_INT >= 24) context.createDeviceProtectedStorageContext() else context
        logDir = File(storage.filesDir, LOG_DIR).apply { mkdirs() }
    }

    fun includeCredentialLogs(context: Context) {
        legacyLogDir = File(context.filesDir, LOG_DIR).takeIf { it != logDir && it.isDirectory }
    }

    private fun directories(): List<File> = if (::logDir.isInitialized) listOfNotNull(logDir, legacyLogDir) else emptyList()

    // ──────────────────────────────────────────────────────────────────────
    //  Public write API
    // ──────────────────────────────────────────────────────────────────────

    /** Write a crash log file synchronously (called from crash handler). */
    fun writeCrash(throwable: Throwable) {
        val ts = timestamp()
        val fileName = "crash_${fileTimestamp()}_${sequence.incrementAndGet()}.log"
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
        if (marker.exists()) return
        try {
            if (!marker.createNewFile()) {
                Log.w(TAG, "Failed to create crash redirect marker: ${marker.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating crash redirect marker", e)
        }
    }

    /**
     * Returns true once after a fatal crash was recorded, then clears the marker.
     * Intended to route the next app launch directly to debug diagnostics.
     */
    fun consumePendingCrashRedirect(): Boolean {
        if (!::logDir.isInitialized) return false
        val markers = directories().map { File(it, CRASH_REDIRECT_MARKER_FILE) }.filter { it.exists() }
        markers.forEach { if (!it.delete()) Log.w(TAG, "Failed to delete crash redirect marker") }
        return markers.isNotEmpty()
    }

    /** Append a caught exception as an error log entry (async). */
    fun appendError(tag: String, throwable: Throwable) {
        ioExecutor.execute {
            safelyWrite { writeFile(
                name = "error_${fileTimestamp()}_${sequence.incrementAndGet()}.log",
                level = "ERROR",
                title = "[$tag] ${throwable.javaClass.simpleName}",
                detail = stackTrace(throwable)
            ) }
        }
    }

    /** Append a custom warning message (async). */
    fun appendWarning(tag: String, message: String) {
        ioExecutor.execute {
            safelyWrite { writeFile(
                name = "warn_${fileTimestamp()}_${sequence.incrementAndGet()}.log",
                level = "WARNING",
                title = "[$tag] $message",
                detail = message
            ) }
        }
    }

    /** Append a custom info message (async). */
    fun appendInfo(tag: String, message: String) {
        ioExecutor.execute {
            safelyWrite { writeFile(
                name = "info_${fileTimestamp()}_${sequence.incrementAndGet()}.log",
                level = "INFO",
                title = "[$tag] $message",
                detail = message
            ) }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Public read API
    // ──────────────────────────────────────────────────────────────────────

    /** Read all log entries sorted newest-first. */
    fun readAll(): List<DebugEntry> {
        if (!::logDir.isInitialized) return emptyList()
        return directories().flatMap { it.listFiles { f -> f.extension == "log" }.orEmpty().toList() }
            .sortedByDescending { it.lastModified() }.take(MAX_LOGS).mapNotNull { parseFile(it) }
    }

    /** Delete all log files. */
    fun clearAll() {
        if (!::logDir.isInitialized) return
        directories().forEach { dir -> dir.listFiles()?.forEach { it.delete() } }
        changes.value += 1
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
            append(detail.take(MAX_DETAIL_CHARS))
        }
        synchronized(writeLock) {
            File(logDir, name).writeText(content, Charsets.UTF_8)
            logDir.listFiles { f -> f.extension == "log" }.orEmpty()
                .sortedByDescending { it.lastModified() }.drop(MAX_LOGS).forEach { it.delete() }
            changes.value += 1
        }
    }

    private fun parseFile(file: File): DebugEntry? {
        return try {
            val text = file.reader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(MAX_DETAIL_CHARS + 8192)
                val count = reader.read(buffer)
                if (count > 0) String(buffer, 0, count) else ""
            }
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

    /** Synchronous stable-id record: exit history can deduplicate without disk preferences on Main. */
    internal fun writeDiagnostic(id: String, level: String, title: String, detail: String) {
        val file = File(logDir, "${level.lowercase()}_$id.log")
        if (!file.exists()) writeFile(file.name, level, title, detail)
    }

    private inline fun safelyWrite(block: () -> Unit) {
        try { block() } catch (error: Exception) { Log.w(TAG, "Diagnostic write failed", error) }
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

private const val TAG = "DebugLogManager"
