package com.omnidev.workspace.data.tools

import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.lang.reflect.InvocationTargetException

/**
 * Executes privileged shell commands via Shizuku (ADB / root-level access).
 *
 * **Safety contract**: This tool NEVER executes autonomously. The agent pipeline wraps
 * every call through [com.omnidev.workspace.ui.chat.ConfirmationGate], which requires
 * explicit user approval before execution. No command runs without a human tap on "Execute".
 *
 * This is a "God Mode" capability intended exclusively for power-user developer workflows
 * (e.g., installing APKs built by the agent, sending `am start` to test a freshly compiled
 * Activity, querying package states via `pm`).
 */
object ShizukuCommandTool {

    private const val SHIZUKU_CODE = 1001
    const val SHIZUKU_UNAVAILABLE_ERROR: String =
        "ERROR: Shizuku service is not running or authorized. Please use Semantic UI tools or standard Intents instead."

    /**
     * Executes [command] via a Shizuku-brokered shell process.
     * Uses reflection to invoke [Shizuku.newProcess] to handle API accessibility constraints.
     *
     * @return A [ShizukuResult] describing success/failure and captured stdout/stderr.
     */
    suspend fun execute(command: String): ShizukuResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext ShizukuResult.Unavailable(SHIZUKU_UNAVAILABLE_ERROR)
        }

        // If we don't have permission, request it and wait briefly for auto-grant.
        if (!hasPermission()) {
            Shizuku.requestPermission(SHIZUKU_CODE)
            // Wait up to 10 seconds for permission to be granted (polling).
            val start = System.currentTimeMillis()
            while (!hasPermission() && System.currentTimeMillis() - start < 10_000L) {
                try { Thread.sleep(300L) } catch (_: InterruptedException) {}
            }
            if (!hasPermission()) {
                android.util.Log.w("ShizukuCommandTool", "Shizuku permission not granted after wait; returning PermissionRequired.")
                return@withContext ShizukuResult.PermissionRequired(SHIZUKU_UNAVAILABLE_ERROR)
            }
        }

        // Retry loop with exponential backoff for transient Shizuku service issues.
        var attempt = 0
        val maxAttempts = 3
        var backoff = 500L
        while (true) {
            attempt++
            try {
                // Use reflection to invoke Shizuku.newProcess() — bypasses Kotlin's
                // compile-time visibility constraints while remaining safe at runtime.
                val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
                val newProcessMethod = shizukuClass.getMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                val process = newProcessMethod.invoke(
                    null,
                    arrayOf("sh", "-c", command),
                    null,
                    null
                ) as Process

                // Read stdout and stderr CONCURRENTLY before waitFor() to prevent
                // OS pipe-buffer deadlock. A single-threaded sequential read will hang
                // whenever a command writes enough to fill the kernel pipe buffer (~64KB).
                val stdoutBuffer = StringBuffer()
                val stderrBuffer = StringBuffer()

                val stdoutThread = Thread {
                    try {
                        process.inputStream.bufferedReader().use { reader ->
                            reader.lineSequence().forEach { line ->
                                if (stdoutBuffer.length < 6_000) stdoutBuffer.appendLine(line)
                            }
                        }
                    } catch (_: Exception) {}
                }.apply { start() }

                val stderrThread = Thread {
                    try {
                        process.errorStream.bufferedReader().use { reader ->
                            reader.lineSequence().forEach { line ->
                                if (stderrBuffer.length < 2_000) stderrBuffer.appendLine(line)
                            }
                        }
                    } catch (_: Exception) {}
                }.apply { start() }

                // API-24-compatible timeout: join a wait thread rather than calling
                // process.waitFor(long, TimeUnit) which requires API 26.
                val waitThread = Thread {
                    try { process.waitFor() } catch (_: InterruptedException) {}
                }.apply { start() }
                waitThread.join(30_000L)
                if (waitThread.isAlive) {
                    process.destroy()
                    stdoutThread.interrupt()
                    stderrThread.interrupt()
                    return@withContext ShizukuResult.Failure("Shizuku command timed out after 30s")
                }

                stdoutThread.join(2_000L)
                stderrThread.join(2_000L)

                val stdout = stdoutBuffer.toString().trimEnd()
                val stderr = stderrBuffer.toString().trimEnd()
                val exit = process.exitValue()

                // Always prefer stdout. Append stderr as context only when stdout exists.
                val output = when {
                    stdout.isNotBlank() && stderr.isNotBlank() -> "$stdout\n[stderr]: $stderr"
                    stdout.isNotBlank() -> stdout
                    stderr.isNotBlank() -> stderr
                    else -> "(no output)"
                }

                if (exit == 0) {
                    return@withContext ShizukuResult.Success(output.trim().ifBlank { "(no output)" })
                } else {
                    if (stdout.isNotBlank()) {
                        return@withContext ShizukuResult.PartialSuccess(
                            output = output.trim(),
                            exitCode = exit
                        )
                    } else {
                        return@withContext ShizukuResult.Failure(
                            "Command exited with code $exit.\nstdout: $stdout\nstderr: $stderr"
                        )
                    }
                }
            } catch (e: Throwable) {
                // If Shizuku's binder/service is the root cause, retry a few times with backoff.
                if (isShizukuServiceException(e)) {
                    android.util.Log.w("ShizukuCommandTool", "Shizuku service error on attempt $attempt: ${e.message}")
                    if (attempt >= maxAttempts) {
                        return@withContext ShizukuResult.Failure(SHIZUKU_UNAVAILABLE_ERROR)
                    }
                    try { Thread.sleep(backoff) } catch (_: InterruptedException) {}
                    backoff = (backoff * 2).coerceAtMost(5_000L)
                    continue
                }

                // Non-Shizuku-related exceptions are returned to the caller immediately.
                return@withContext ShizukuResult.Failure("Exception executing command: ${e.message}")
            }
        }
    }

    /** Returns true if Shizuku service is alive and we can communicate with it. */
    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** Returns true if the app already holds the Shizuku ADB permission. */
    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * Detects failures indicating the underlying Shizuku binder/service is unavailable or unauthorized.
     *
     * Reflection-based `Shizuku.newProcess()` invocation may wrap root causes in
     * [InvocationTargetException], so we unwrap and inspect the deepest relevant throwable.
     * We also fall back to class-name checks for framework exception types that may not be
     * directly accessible at compile time in this module.
     *
     * NOTE: [IllegalStateException] and [SecurityException] are matched **only** when the
     * exception message references Shizuku. Catching them generically caused real execution
     * errors (e.g. process-creation failures) to be misreported as "service not running".
     */
    fun isShizukuServiceException(error: Throwable): Boolean {
        val root: Throwable = if (error is InvocationTargetException) {
            error.targetException ?: error.cause ?: error
        } else {
            error.cause ?: error
        }
        val name = root::class.java.name
        val message = (root.message ?: error.message).orEmpty()
        return name == "android.os.DeadObjectException" ||
            name == "android.os.RemoteException" ||
            root is NoSuchMethodException ||
            root is ClassNotFoundException ||
            root is NoClassDefFoundError ||
            // Only treat SecurityException / IllegalStateException as a Shizuku-availability
            // issue when the message explicitly references Shizuku — otherwise let the real
            // error propagate so the caller can fall through to the next backend.
            (root is SecurityException && message.contains("shizuku", ignoreCase = true)) ||
            (root is IllegalStateException && message.contains("shizuku", ignoreCase = true)) ||
            // Reflection can fail with method-signature text when Shizuku API/service
            // shape is incompatible at runtime; match the known method token safely.
            message.contains("rikka.shizuku.Shizuku.newProcess", ignoreCase = true)
    }
}

sealed class ShizukuResult {
    data class Success(val output: String) : ShizukuResult()
    /**
     * Command produced stdout output but exited with a non-zero code.
     * This is common for tools like `pkg install`, Python scripts that call `sys.exit(1)`,
     * or shell pipelines where an intermediate command fails but the final output is valid.
     * Callers should treat the [output] as valid result data and log [exitCode] for debugging.
     */
    data class PartialSuccess(val output: String, val exitCode: Int) : ShizukuResult()
    data class Failure(val reason: String) : ShizukuResult()
    data class PermissionRequired(val message: String) : ShizukuResult()
    data class Unavailable(val message: String) : ShizukuResult()

    fun toDisplayString(): String = when (this) {
        is Success        -> output
        is PartialSuccess -> output   // Surface output; exit code is informational only
        is Failure        -> "❌ Error: $reason"
        is PermissionRequired -> "🔐 $message"
        is Unavailable    -> "⚠️ $message"
    }
}
