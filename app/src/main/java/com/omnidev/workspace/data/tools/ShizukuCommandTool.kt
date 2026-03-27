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
    suspend fun execute(command: String): ShizukuResult {
        return withContext(Dispatchers.IO) {
            if (!isAvailable()) {
                return@withContext ShizukuResult.Unavailable(SHIZUKU_UNAVAILABLE_ERROR)
            }

            if (!hasPermission()) {
                Shizuku.requestPermission(SHIZUKU_CODE)
                val start = System.currentTimeMillis()
                while (!hasPermission() && System.currentTimeMillis() - start < 10_000L) {
                    try {
                        Thread.sleep(300L)
                    } catch (_: InterruptedException) {
                    }
                }
                if (!hasPermission()) {
                    android.util.Log.w(
                        "ShizukuCommandTool",
                        "Shizuku permission not granted after wait; returning PermissionRequired."
                    )
                    return@withContext ShizukuResult.PermissionRequired(SHIZUKU_UNAVAILABLE_ERROR)
                }
            }

            var attempt = 0
            val maxAttempts = 3
            var backoff = 500L
            while (attempt < maxAttempts) {
                attempt++
                try {
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

                    val stdoutBuffer = StringBuffer()
                    val stderrBuffer = StringBuffer()

                    val stdoutThread = Thread {
                        try {
                            process.inputStream.bufferedReader().use { reader ->
                                reader.lineSequence().forEach { line ->
                                    if (stdoutBuffer.length < 6_000) stdoutBuffer.appendLine(line)
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }.apply { start() }

                    val stderrThread = Thread {
                        try {
                            process.errorStream.bufferedReader().use { reader ->
                                reader.lineSequence().forEach { line ->
                                    if (stderrBuffer.length < 2_000) stderrBuffer.appendLine(line)
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }.apply { start() }

                    val waitThread = Thread {
                        try {
                            process.waitFor()
                        } catch (_: InterruptedException) {
                        }
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

                    val output = when {
                        stdout.isNotBlank() && stderr.isNotBlank() ->
                            "$stdout\n[stderr]: $stderr"
                        stdout.isNotBlank() -> stdout
                        stderr.isNotBlank() -> stderr
                        else -> "(no output)"
                    }

                    val normalizedOutput = output.trim().ifBlank { "(no output)" }

                    if (exit == 0) {
                        return@withContext ShizukuResult.Success(normalizedOutput)
                    }

                    val failureMessage = if (normalizedOutput == "(no output)") {
                        "Command exited with code $exit but produced no stdout/stderr. " +
                            "This may indicate the command completed silently or there is a shell compatibility issue."
                    } else {
                        "Command exited with code $exit.\nstdout: $stdout\nstderr: $stderr"
                    }

                    return@withContext ShizukuResult.PartialSuccess(
                        output = failureMessage,
                        exitCode = exit
                    )
                } catch (e: Throwable) {
                    if (isShizukuServiceException(e)) {
                        android.util.Log.w(
                            "ShizukuCommandTool",
                            "Shizuku service error on attempt $attempt: ${e.message}"
                        )
                        if (attempt >= maxAttempts) {
                            return@withContext ShizukuResult.Failure(SHIZUKU_UNAVAILABLE_ERROR)
                        }
                        try {
                            Thread.sleep(backoff)
                        } catch (_: InterruptedException) {
                        }
                        backoff = (backoff * 2).coerceAtMost(5_000L)
                        continue
                    }

                    return@withContext ShizukuResult.Failure("Exception executing command: ${e.message}")
                }
            }

            return@withContext ShizukuResult.Failure(SHIZUKU_UNAVAILABLE_ERROR)
        }
    }

    /** Returns true if Shizuku service is alive and we can communicate with it. */
    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** Returns true if the app already holds the Shizuku ADB permission. */
    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

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
            (root is SecurityException && message.contains("shizuku", ignoreCase = true)) ||
            (root is IllegalStateException && message.contains("shizuku", ignoreCase = true)) ||
            message.contains("rikka.shizuku.Shizuku.newProcess", ignoreCase = true)
    }
}

sealed class ShizukuResult {
    data class Success(val output: String) : ShizukuResult()
    data class PartialSuccess(val output: String, val exitCode: Int) : ShizukuResult()
    data class Failure(val reason: String) : ShizukuResult()
    data class PermissionRequired(val message: String) : ShizukuResult()
    data class Unavailable(val message: String) : ShizukuResult()

    fun toDisplayString(): String = when (this) {
        is Success -> output
        is PartialSuccess -> output
        is Failure -> "❌ Error: $reason"
        is PermissionRequired -> "🔐 $message"
        is Unavailable -> "⚠️ $message"
    }
}
