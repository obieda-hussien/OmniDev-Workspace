package com.omnidev.workspace.data.ipc

import android.content.Context
import android.os.Bundle
import android.system.Os
import androidx.annotation.Keep
import com.omnidev.workspace.ipc.IPrivilegedShellService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Real Shizuku UserService command executor.
 *
 * IMPORTANT: this class is instantiated by Shizuku in a separate process and
 * therefore must keep a public no-arg constructor. Do not move command execution
 * back to Runtime.exec() in the app process: doing so silently drops privileges.
 */
@Keep
class PrivilegedShellUserService @JvmOverloads constructor(
    @Suppress("UNUSED_PARAMETER") context: Context? = null
) : IPrivilegedShellService.Stub() {

    companion object {
        private const val MAX_STDOUT_CHARS = 128_000
        private const val MAX_STDERR_CHARS = 32_000
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val MAX_TIMEOUT_MS = 10 * 60_000L
    }

    override fun ping(): String = "ok:uid=${runCatching { Os.getuid() }.getOrDefault(-1)}"

    override fun getUid(): Int = runCatching { Os.getuid() }.getOrDefault(-1)

    override fun execute(command: String?, timeoutMs: Long): Bundle {
        if (command.isNullOrBlank()) {
            return resultBundle(
                exitCode = -1,
                stdout = "",
                stderr = "",
                timedOut = false,
                error = "Command is empty"
            )
        }

        val effectiveTimeout = when {
            timeoutMs <= 0L -> DEFAULT_TIMEOUT_MS
            else -> timeoutMs.coerceAtMost(MAX_TIMEOUT_MS)
        }

        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(false)
                .start()

            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val stdoutDone = AtomicBoolean(false)
            val stderrDone = AtomicBoolean(false)

            val stdoutThread = streamThread(
                name = "omni-shizuku-stdout",
                reader = { process.inputStream.bufferedReader(Charsets.UTF_8) },
                target = stdout,
                maxChars = MAX_STDOUT_CHARS,
                done = stdoutDone
            )
            val stderrThread = streamThread(
                name = "omni-shizuku-stderr",
                reader = { process.errorStream.bufferedReader(Charsets.UTF_8) },
                target = stderr,
                maxChars = MAX_STDERR_CHARS,
                done = stderrDone
            )

            stdoutThread.start()
            stderrThread.start()

            val waiter = Thread({
                try {
                    process.waitFor()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }, "omni-shizuku-waiter").apply {
                isDaemon = true
                start()
            }

            waiter.join(effectiveTimeout)
            val timedOut = waiter.isAlive
            if (timedOut) {
                runCatching { process.destroy() }
                waiter.interrupt()
            }

            stdoutThread.join(2_000L)
            stderrThread.join(2_000L)

            if (stdoutThread.isAlive) stdoutThread.interrupt()
            if (stderrThread.isAlive) stderrThread.interrupt()

            val exitCode = if (timedOut) {
                -1
            } else {
                runCatching { process.exitValue() }.getOrDefault(-1)
            }

            resultBundle(
                exitCode = exitCode,
                stdout = stdout.toString().trimEnd(),
                stderr = stderr.toString().trimEnd(),
                timedOut = timedOut,
                error = if (timedOut) "Command timed out after ${effectiveTimeout}ms" else null
            )
        } catch (t: Throwable) {
            resultBundle(
                exitCode = -1,
                stdout = "",
                stderr = "",
                timedOut = false,
                error = "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
            )
        }
    }

    private fun streamThread(
        name: String,
        reader: () -> java.io.BufferedReader,
        target: StringBuilder,
        maxChars: Int,
        done: AtomicBoolean
    ): Thread = Thread({
        try {
            reader().use { input ->
                val buffer = CharArray(4096)
                while (!Thread.currentThread().isInterrupted) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val remaining = maxChars - target.length
                    if (remaining <= 0) continue
                    target.append(buffer, 0, min(count, remaining))
                }
            }
        } catch (_: Throwable) {
            // The process may close streams while being killed on timeout.
        } finally {
            done.set(true)
        }
    }, name).apply { isDaemon = true }

    private fun resultBundle(
        exitCode: Int,
        stdout: String,
        stderr: String,
        timedOut: Boolean,
        error: String?
    ): Bundle = Bundle().apply {
        putInt("exitCode", exitCode)
        putString("stdout", stdout)
        putString("stderr", stderr)
        putBoolean("timedOut", timedOut)
        putInt("uid", getUid())
        if (error != null) putString("error", error)
    }
}
