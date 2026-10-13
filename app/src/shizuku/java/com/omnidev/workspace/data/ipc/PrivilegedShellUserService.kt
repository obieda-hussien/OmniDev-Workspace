package com.omnidev.workspace.data.ipc

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.annotation.Keep
import com.omnidev.workspace.ipc.IPrivilegedShellService
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

    override fun destroy() {
        // Reserved Shizuku UserService lifecycle transaction.
        System.exit(0)
    }

    override fun captureScreenshot(timeoutMs: Long): ParcelFileDescriptor? {
        val effectiveTimeout = when {
            timeoutMs <= 0L -> 5_000L
            else -> timeoutMs.coerceIn(1_000L, 30_000L)
        }

        return try {
            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            val writeSide = pipe[1]

            Thread({
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                    var process: Process? = null
                    try {
                        process = ProcessBuilder("/system/bin/screencap", "-p")
                            .redirectErrorStream(false)
                            .start()

                        val activeProcess = process
                        val stderrDrainer = Thread({
                            runCatching {
                                activeProcess.errorStream.use { input ->
                                    val buffer = ByteArray(2_048)
                                    while (input.read(buffer) >= 0) {
                                        // Drain only; screenshot data must stay binary-clean.
                                    }
                                }
                            }
                        }, "omni-screencap-stderr").apply {
                            isDaemon = true
                            start()
                        }

                        val watchdog = Thread({
                            try {
                                Thread.sleep(effectiveTimeout)
                                if (activeProcess.isAlive) activeProcess.destroyForcibly()
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                        }, "omni-screencap-watchdog").apply {
                            isDaemon = true
                            start()
                        }

                        activeProcess.inputStream.use { input ->
                            val buffer = ByteArray(32 * 1024)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > 16L * 1024L * 1024L) {
                                    activeProcess.destroyForcibly()
                                    break
                                }
                                output.write(buffer, 0, count)
                            }
                            output.flush()
                        }

                        runCatching { activeProcess.waitFor() }
                        watchdog.interrupt()
                        stderrDrainer.join(500L)
                    } catch (_: Throwable) {
                        runCatching { process?.destroyForcibly() }
                        // Closing the pipe gives the app EOF; it will reject an empty/invalid PNG.
                    }
                }
            }, "omni-screencap-pipe").apply {
                isDaemon = true
                start()
            }

            readSide
        } catch (_: Throwable) {
            null
        }
    }

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

            val stdoutThread = streamThread(
                name = "omni-shizuku-stdout",
                reader = { process.inputStream.bufferedReader(Charsets.UTF_8) },
                target = stdout,
                maxChars = MAX_STDOUT_CHARS
            )
            val stderrThread = streamThread(
                name = "omni-shizuku-stderr",
                reader = { process.errorStream.bufferedReader(Charsets.UTF_8) },
                target = stderr,
                maxChars = MAX_STDERR_CHARS
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

            val exitCode = if (timedOut) -1
            else runCatching { process.exitValue() }.getOrDefault(-1)

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
        maxChars: Int
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
            // Expected when a process is destroyed on timeout.
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
