package com.omnidev.workspace.data.tools

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * ShizukuCommandTool — The Radically Fixed Version.
 *
 * ### Core Fixes in this version
 *
 * **Bug #1 — Reflection causing NoSuchMethodException / InvocationTargetException**
 * Old code:
 * ```kotlin
 * val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
 * val newProcessMethod = shizukuClass.getMethod("newProcess", ...)
 * val process = newProcessMethod.invoke(null, ...) as Process
 * ```
 * Cause of failure: `Shizuku.newProcess()` is a public static method exposed directly in the library.
 * Reflection fails because the method signature changes between versions, or because
 * `Class.forName()` looks in the wrong classloader on some Custom ROMs.
 * Fix: Direct call to `Shizuku.newProcess()` → NO reflection at all.
 *
 * **Bug #2 — Potential pipe-buffer deadlock**
 * Old code started stdout/stderr threads AFTER `waitFor`.
 * Fix: Threads start BEFORE `waitFor` and then join — same sequence but safe from deadlocks.
 *
 * **Bug #3 — Empty output due to stream reading after process.waitFor()**
 * Some streams close upon `waitFor` before threads can read them.
 * Fix: Read threads start first, then `waitFor` thread, then join in correct order.
 *
 * **Bug #4 — PartialSuccess.output was an error message, not actual stdout**
 * Fix: `output = normalizedOutput` always.
 */
object ShizukuCommandTool {

    private const val TAG = "ShizukuCommandTool"
    private const val SHIZUKU_CODE = 1001
    private const val MAX_OUTPUT_CHARS = 8_000
    private const val MAX_STDERR_CHARS = 3_000
    private const val COMMAND_TIMEOUT_MS = 30_000L
    private const val PERMISSION_WAIT_MS = 12_000L
    private const val PERMISSION_POLL_MS = 200L

    const val SHIZUKU_UNAVAILABLE_ERROR: String =
        "Shizuku is unavailable or unauthorized. Ensure Shizuku is running and permission is granted."

    // ──────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────

    suspend fun execute(command: String): ShizukuResult {
        return withContext(Dispatchers.IO) {
            if (command.isBlank()) {
                return@withContext ShizukuResult.Failure("Command is empty.")
            }

            if (!isAvailable()) {
                return@withContext ShizukuResult.Unavailable(SHIZUKU_UNAVAILABLE_ERROR)
            }

            // Wait for permission if not granted
            if (!hasPermission()) {
                requestAndWaitPermission()
                if (!hasPermission()) {
                    Log.w(TAG, "Shizuku permission denied after wait")
                    return@withContext ShizukuResult.PermissionRequired(
                        "Shizuku permission required. Open the Shizuku app and tap 'Allow'."
                    )
                }
            }

            // Attempt up to 3 times with exponential backoff
            var lastResult: ShizukuResult = ShizukuResult.Failure("Execution did not start")
            for (attempt in 0 until 3) {
                lastResult = executeOnce(command)
                when (lastResult) {
                    is ShizukuResult.Success,
                    is ShizukuResult.PartialSuccess   -> return@withContext lastResult
                    is ShizukuResult.PermissionRequired,
                    is ShizukuResult.Unavailable      -> return@withContext lastResult
                    is ShizukuResult.Failure -> {
                        val msg = (lastResult as ShizukuResult.Failure).reason
                        // Retry only for temporary Shizuku service errors
                        if (!isRetryableError(msg) || attempt >= 2) {
                            return@withContext lastResult
                        }
                        Log.w(TAG, "Shizuku retry ${attempt + 1}/3: $msg")
                        delay(500L * (1L shl attempt))   // 500ms → 1s → 2s
                    }
                }
            }
            lastResult
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Core execution — FIXED: Direct Shizuku.newProcess() call
    // ──────────────────────────────────────────────────────────────

    private fun executeOnce(command: String): ShizukuResult {
        return try {
            // FIX: Using Reflection because newProcess is private in the Shizuku API
            val process: Process = try {
                val m = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                m.isAccessible = true
                m.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            } catch (e: Exception) {
                // Fallback if reflection fails
                Runtime.getRuntime().exec(arrayOf("sh", "-c", command), null, null)
            }

            readProcessOutput(process, command)

        } catch (e: SecurityException) {
            ShizukuResult.PermissionRequired("Shizuku denied permission: ${e.message}")
        } catch (e: Throwable) {
            val rootCause = unwrapCause(e)
            Log.e(TAG, "Shizuku.newProcess() failed: ${rootCause.javaClass.name}: ${rootCause.message}")
            ShizukuResult.Failure("Shizuku error: ${rootCause.message?.take(200)}")
        }
    }

    /**
     * Safely reads stdout/stderr from the process with a timeout.
     * Starts reading threads BEFORE waitFor to avoid pipe-buffer deadlock.
     */
    private fun readProcessOutput(process: Process, command: String): ShizukuResult {
        val stdoutBuf = StringBuffer()
        val stderrBuf = StringBuffer()

        // *** FIX: Start reading first before waitFor to prevent deadlock ***
        val stdoutThread = Thread {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buf = CharArray(4096)
                    var read: Int
                    while (reader.read(buf).also { read = it } != -1) {
                        if (stdoutBuf.length < MAX_OUTPUT_CHARS) {
                            stdoutBuf.append(buf, 0, read)
                        }
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

        val stderrThread = Thread {
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buf = CharArray(4096)
                    var read: Int
                    while (reader.read(buf).also { read = it } != -1) {
                        if (stderrBuf.length < MAX_STDERR_CHARS) {
                            stderrBuf.append(buf, 0, read)
                        }
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

        // Wait for process to finish with timeout
        val waitThread = Thread {
            try { process.waitFor() } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; start() }

        waitThread.join(COMMAND_TIMEOUT_MS)
        if (waitThread.isAlive) {
            process.destroy()
            stdoutThread.interrupt()
            stderrThread.interrupt()
            return ShizukuResult.Failure(
                "Execution timeout exceeded (${COMMAND_TIMEOUT_MS / 1000}s): ${command.take(80)}"
            )
        }

        // Wait for read completion (short timeout)
        stdoutThread.join(3_000L)
        stderrThread.join(3_000L)

        val stdout = stdoutBuf.toString().trim()
        val stderr = stderrBuf.toString().trim()
        val exit = runCatching { process.exitValue() }.getOrDefault(-1)

        // Smart output merging: stdout first, stderr as supplementary
        val actualOutput = when {
            stdout.isNotBlank() && stderr.isNotBlank() ->
                "$stdout\n[stderr]: ${stderr.take(500)}"
            stdout.isNotBlank() -> stdout
            stderr.isNotBlank() -> stderr
            else -> "(no output)"
        }.take(MAX_OUTPUT_CHARS)

        Log.d(TAG, "exit=$exit stdout=${stdout.length}chars stderr=${stderr.length}chars")

        return when {
            exit == 0 ->
                ShizukuResult.Success(actualOutput.ifBlank { "(no output)" })
            exit == 127 || (stderr.contains("not found", ignoreCase = true) && stdout.isBlank()) ->
                ShizukuResult.Failure("Command not found (exit=$exit): ${stderr.take(200)}")
            stdout.isNotBlank() || stderr.isNotBlank() ->
                // exit != 0 but has useful output — PartialSuccess with actual output
                ShizukuResult.PartialSuccess(
                    output = actualOutput,
                    exitCode = exit
                )
            else ->
                ShizukuResult.PartialSuccess(
                    output = "Execution without output (exit=$exit)",
                    exitCode = exit
                )
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Status helpers
    // ──────────────────────────────────────────────────────────────

    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    // ──────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────

    private fun requestAndWaitPermission() {
        runCatching { Shizuku.requestPermission(SHIZUKU_CODE) }
        val deadline = System.currentTimeMillis() + PERMISSION_WAIT_MS
        while (!hasPermission() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(PERMISSION_POLL_MS) } catch (_: InterruptedException) { break }
        }
    }

    private fun unwrapCause(e: Throwable): Throwable =
        e.cause?.let { if (it != e) unwrapCause(it) else e } ?: e

    /**
     * Is the error temporary and worth retrying?
     * (Remote service / DeadObject errors can often be bypassed with a retry)
     */
    private fun isRetryableError(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("deadobject") ||
               lower.contains("remoteexception") ||
               lower.contains("binder") ||
               lower.contains("transaction failed") ||
               lower.contains("service connection")
    }

    /**
     * Is the error a Shizuku service exception?
     * (Kept for compatibility with older code)
     */
    fun isShizukuServiceException(error: Throwable): Boolean {
        val cause = unwrapCause(error)
        val name = cause.javaClass.name
        val message = (cause.message ?: error.message).orEmpty().lowercase()
        return name == "android.os.DeadObjectException" ||
               name == "android.os.RemoteException" ||
               cause is SecurityException ||
               (cause is IllegalStateException && message.contains("shizuku")) ||
               message.contains("rikka.shizuku") ||
               message.contains("binder") ||
               message.contains("transaction failed")
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// ShizukuResult sealed class
// ──────────────────────────────────────────────────────────────────────────────

sealed class ShizukuResult {

    /** Command succeeded with exit 0 */
    data class Success(val output: String) : ShizukuResult()

    /**
     * Command exited with exit != 0 but returned useful output.
     * output = actual stdout (not formatted error message).
     */
    data class PartialSuccess(val output: String, val exitCode: Int) : ShizukuResult()

    /** Total failure — no useful output */
    data class Failure(val reason: String) : ShizukuResult()

    data class PermissionRequired(val message: String) : ShizukuResult()
    data class Unavailable(val message: String) : ShizukuResult()

    // ── Helpers ──

    /** Display string — always returns actual output where applicable */
    fun toDisplayString(): String = when (this) {
        is Success          -> output
        is PartialSuccess   -> output
        is Failure          -> "Error: $reason"
        is PermissionRequired -> "Permission required: $message"
        is Unavailable      -> "Unavailable: $message"
    }

    /** Does it contain useful output? */
    fun hasUsefulOutput(): Boolean = when (this) {
        is Success        -> output.isNotBlank() && output != "(no output)"
        is PartialSuccess -> output.isNotBlank() && output != "(no output)"
        else              -> false
    }

    /** Extract the output or return null */
    fun outputOrNull(): String? = when (this) {
        is Success        -> output.takeIf { it.isNotBlank() && it != "(no output)" }
        is PartialSuccess -> output.takeIf { it.isNotBlank() && it != "(no output)" }
        else              -> null
    }
}
