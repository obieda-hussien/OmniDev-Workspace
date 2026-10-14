package com.omnidev.workspace.data.debug

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Diagnose exits and queue stalls without killing, restarting or capturing user messages. */
object RuntimeDiagnostics {
    private val started = AtomicBoolean()
    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "OmniRuntimeDiagnostics").apply { isDaemon = true }
    }

    fun start(context: Context) {
        if (Build.VERSION.SDK_INT >= 28 && Application.getProcessName() != context.packageName) return
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        worker.execute {
            runCatching { importExits(app) }.onFailure { Log.w("RuntimeDiagnostics", "Exit history unavailable", it) }
        }
        val main = Handler(Looper.getMainLooper())
        val tracker = MainThreadStallTracker()
        worker.scheduleWithFixedDelay({
            runCatching {
                val now = SystemClock.uptimeMillis() // Deep sleep is not an ANR.
                if (tracker.shouldReport(now, Debug.isDebuggerConnected())) {
                    val stack = Looper.getMainLooper().thread.stackTrace.take(96).joinToString("\n")
                    DebugLogManager.writeDiagnostic("stall_${System.currentTimeMillis()}", "WARNING",
                        "Main thread unresponsive for at least 10 seconds",
                        "Potential ANR; this is an observed queue stall, not a confirmed OS ANR.\nMain thread:\n$stack")
                }
                if (tracker.enqueue(now)) main.post { tracker.acknowledge() }
            }.onFailure { Log.w("RuntimeDiagnostics", "Heartbeat diagnostics failed", it) }
        }, 5, 5, TimeUnit.SECONDS)
    }

    private fun importExits(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val prefs = context.getSharedPreferences("runtime-exit-diagnostics", Context.MODE_PRIVATE)
        val cursor = prefs.getLong("last_exit", 0)
        val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 16)
            .filter { it.timestamp > cursor }.sortedBy { it.timestamp }
        var latest = cursor
        for (exit in exits) {
            val reason = when (exit.reason) {
                ApplicationExitInfo.REASON_ANR -> "ANR"
                ApplicationExitInfo.REASON_CRASH -> "JAVA_CRASH"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
                else -> "EXIT_${exit.reason}"
            }
            val abnormal = exit.reason in setOf(ApplicationExitInfo.REASON_ANR,
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE)
            // Trace data is plain text only for ANR. Native tombstones may be protobufs.
            val trace = if (exit.reason == ApplicationExitInfo.REASON_ANR) runCatching {
                exit.traceInputStream?.use { input ->
                    val bytes = ByteArray(48 * 1024)
                    var size = 0
                    while (size < bytes.size) {
                        val count = input.read(bytes, size, bytes.size - size)
                        if (count <= 0) break
                        size += count
                    }
                    String(bytes, 0, size, Charsets.UTF_8)
                }
            }.getOrNull() else null
            DebugLogManager.writeDiagnostic("exit_${exit.timestamp}_${exit.pid}",
                if (abnormal) "ERROR" else "INFO", "Process exit: $reason",
                "Process=${exit.processName}\nTime=${exit.timestamp}\nReason=$reason\n" +
                    "Status=${exit.status}\nImportance=${exit.importance}\nPSS=${exit.pss} KiB\nRSS=${exit.rss} KiB\n" +
                    "Description=${exit.description.orEmpty()}\n${trace ?: "No ANR trace supplied by Android."}")
            if (abnormal) DebugLogManager.markPendingCrashRedirect()
            latest = maxOf(latest, exit.timestamp)
        }
        if (latest > cursor) prefs.edit().putLong("last_exit", latest).apply()
    }
}
