package com.omnidev.workspace.data.ipc

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.omnidev.workspace.data.tools.ShizukuCommandTool
import com.omnidev.workspace.data.tools.ShizukuResult

/**
 * PrivilegedExecutionManager — singleton wrapper around the Shizuku API with a
 * root/SU fallback for executing elevated shell commands and interacting with
 * hidden Android system services.
 *
 * ### Security contract
 * - Callers must hold `com.omnidev.permission.CONTROL_CORE` (enforced by
 *   [OmniCoreService] at the IPC boundary before this class is ever invoked).
 * - This class never validates caller identity itself; that is the
 *   responsibility of the AIDL service layer.
 *
 * ### Threading
 * All public suspend functions are safe to call from any coroutine context;
 * they internally switch to [Dispatchers.IO] for blocking I/O.
 */
object PrivilegedExecutionManager {

    // ─────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Execute [command] with elevated privileges.
     *
     * Tries Shizuku first; falls back to `/system/bin/su` if Shizuku is
     * unavailable. Returns [Result.success] with trimmed stdout on success, or
     * [Result.failure] with a descriptive [Exception] on failure.
     */
    suspend fun executeCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        if (command.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Command must not be blank."))
        }

        return@withContext when {
            ShizukuCommandTool.isAvailable() && ShizukuCommandTool.hasPermission() ->
                executeViaShizuku(command)
            isRootAvailable() ->
                executeViaRoot(command)
            else ->
                Result.failure(
                    IllegalStateException(
                        "No privileged execution backend available. " +
                        "Shizuku is not running and root is not accessible."
                    )
                )
        }
    }

    /**
     * Query the availability of Shizuku without side-effects.
     *
     * @return true if Shizuku is bound, alive, and this app holds the permission.
     */
    fun isShizukuReady(): Boolean =
        ShizukuCommandTool.isAvailable() && ShizukuCommandTool.hasPermission()

    /**
     * Returns true if a root/SU binary is reachable on this device
     * (does not check whether root has been granted to this app).
     */
    fun isRootAvailable(): Boolean = runCatching {
        val which = Runtime.getRuntime().exec(arrayOf("which", "su"))
        which.waitFor() == 0
    }.getOrDefault(false)

    /**
     * Returns a [DeviceStateSnapshot] describing the current device state.
     * Combines OS-level metadata with Shizuku/root availability flags.
     */
    suspend fun getDeviceState(context: Context): DeviceStateSnapshot =
        withContext(Dispatchers.IO) {
            val foregroundPkg = getForegroundPackage()
            DeviceStateSnapshot(
                buildFingerprint = Build.FINGERPRINT,
                sdkInt = Build.VERSION.SDK_INT,
                shizukuReady = isShizukuReady(),
                rootAvailable = isRootAvailable(),
                foregroundPackage = foregroundPkg
            )
        }

    // ─────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun executeViaShizuku(command: String): Result<String> {
        return when (val result = ShizukuCommandTool.execute(command)) {
            is ShizukuResult.Success -> Result.success(result.output.trim())
            is ShizukuResult.Failure -> Result.failure(RuntimeException(result.reason))
            is ShizukuResult.PermissionRequired ->
                Result.failure(SecurityException(result.message))
            is ShizukuResult.Unavailable ->
                Result.failure(IllegalStateException(result.message))
        }
    }

    private fun executeViaRoot(command: String): Result<String> = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        // Read streams before waitFor() to prevent deadlock when the process
        // output fills the OS pipe buffer before it has exited.
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit == 0) {
            stdout.trim().ifBlank { "(no output)" }
        } else {
            throw RuntimeException("Root command exited $exit. stderr: $stderr")
        }
    }

    private suspend fun getForegroundPackage(): String = withContext(Dispatchers.IO) {
        runCatching {
            when (val r = ShizukuCommandTool.execute(
                "dumpsys activity activities | grep mResumedActivity | head -1"
            )) {
                is ShizukuResult.Success -> r.output.trim()
                else -> "unknown"
            }
        }.getOrDefault("unknown")
    }
}

/**
 * Immutable snapshot of device state returned by [PrivilegedExecutionManager.getDeviceState].
 */
data class DeviceStateSnapshot(
    val buildFingerprint: String,
    val sdkInt: Int,
    val shizukuReady: Boolean,
    val rootAvailable: Boolean,
    val foregroundPackage: String
) {
    /** Serialise to a compact JSON string for transport over the AIDL boundary. */
    fun toJson(): String = buildString {
        append("{")
        append("\"buildFingerprint\":\"${buildFingerprint.jsonEscape()}\",")
        append("\"sdkInt\":$sdkInt,")
        append("\"shizukuReady\":$shizukuReady,")
        append("\"rootAvailable\":$rootAvailable,")
        append("\"foregroundPackage\":\"${foregroundPackage.jsonEscape()}\"")
        append("}")
    }

    private fun String.jsonEscape(): String = this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
