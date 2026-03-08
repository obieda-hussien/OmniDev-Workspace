package com.omnidev.workspace.data.tools

import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

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

    /**
     * Executes [command] via a Shizuku-brokered shell process.
     * Uses reflection to invoke [Shizuku.newProcess] to handle API accessibility constraints.
     *
     * @return A [ShizukuResult] describing success/failure and captured stdout/stderr.
     */
    suspend fun execute(command: String): ShizukuResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext ShizukuResult.Unavailable(
                "Shizuku is not running. Start Shizuku from the Shizuku app first."
            )
        }
        if (!hasPermission()) {
            Shizuku.requestPermission(SHIZUKU_CODE)
            return@withContext ShizukuResult.PermissionRequired(
                "Shizuku permission is not granted. The permission dialog has been shown."
            )
        }

        runCatching {
            // Use reflection to invoke Shizuku.newProcess() — bypasses Kotlin's
            // compile-time visibility constraints while remaining safe at runtime.
            // Targets Shizuku API 13.1.5; if the method signature changes in a future
            // version this block will throw a NoSuchMethodException which is caught below.
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
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            process.waitFor()
            val exit = process.exitValue()
            if (exit == 0) {
                ShizukuResult.Success(stdout.ifBlank { "(no output)" })
            } else {
                ShizukuResult.Failure(
                    "Command exited with code $exit.\nstdout: $stdout\nstderr: $stderr"
                )
            }
        }.getOrElse { e ->
            ShizukuResult.Failure("Exception executing command: ${e.message}")
        }
    }

    /** Returns true if Shizuku service is alive and we can communicate with it. */
    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** Returns true if the app already holds the Shizuku ADB permission. */
    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
}

sealed class ShizukuResult {
    data class Success(val output: String) : ShizukuResult()
    data class Failure(val reason: String) : ShizukuResult()
    data class PermissionRequired(val message: String) : ShizukuResult()
    data class Unavailable(val message: String) : ShizukuResult()

    fun toDisplayString(): String = when (this) {
        is Success -> output
        is Failure -> "❌ Error: $reason"
        is PermissionRequired -> "🔐 $message"
        is Unavailable -> "⚠️ $message"
    }
}
