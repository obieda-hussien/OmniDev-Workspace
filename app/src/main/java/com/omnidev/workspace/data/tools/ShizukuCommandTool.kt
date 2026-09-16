package com.omnidev.workspace.data.tools

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.omnidev.workspace.data.ipc.ShizukuUserServiceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Authoritative Shizuku command backend.
 *
 * A command is only reported as success when it ran in the UserService process,
 * exited 0, emitted no known fatal signature, and (for supported state mutations)
 * passed a read-back postcondition check.
 */
object ShizukuCommandTool {

    private const val TAG = "ShizukuCommandTool"
    private const val SHIZUKU_CODE = 1001
    private const val COMMAND_TIMEOUT_MS = 30_000L
    private const val PERMISSION_WAIT_MS = 12_000L
    private const val PERMISSION_POLL_MS = 200L
    private const val MAX_OUTPUT_CHARS = 16_000

    private val SETTINGS_PUT = Regex(
        "(?i)(?:^|[;&]\\s*)settings\\s+put\\s+(system|secure|global)\\s+([A-Za-z0-9_.]+)\\s+([^;&\\n\\r]+)"
    )
    private val SETPROP = Regex(
        "(?i)(?:^|[;&]\\s*)setprop\\s+([A-Za-z0-9._-]+)\\s+([^;&\\n\\r]+)"
    )

    const val SHIZUKU_UNAVAILABLE_ERROR: String =
        "Shizuku is unavailable or unauthorized. Ensure Shizuku is running and permission is granted."

    fun init(context: Context) {
        ShizukuUserServiceClient.init(context.applicationContext)
    }

    suspend fun execute(
        command: String,
        timeoutMs: Long = COMMAND_TIMEOUT_MS
    ): ShizukuResult = withContext(Dispatchers.IO) {
        if (command.isBlank()) return@withContext ShizukuResult.Failure("Command is empty.")
        if (!isAvailable()) return@withContext ShizukuResult.Unavailable(SHIZUKU_UNAVAILABLE_ERROR)

        if (!hasPermission()) {
            requestAndWaitPermission()
            if (!hasPermission()) {
                return@withContext ShizukuResult.PermissionRequired(
                    "Shizuku permission required. Grant OmniDev access in the Shizuku app."
                )
            }
        }

        if (!ShizukuUserServiceClient.isInitialized()) {
            return@withContext ShizukuResult.Failure(
                "Shizuku UserService client is not initialized. PrivilegedExecutionManager.init(context) must run first."
            )
        }

        var lastFailure: ShizukuResult = ShizukuResult.Failure("Execution did not start")
        repeat(3) { attempt ->
            val result = executeOnce(command, timeoutMs)
            when (result) {
                is ShizukuResult.Success,
                is ShizukuResult.PartialSuccess,
                is ShizukuResult.PermissionRequired,
                is ShizukuResult.Unavailable -> return@withContext result

                is ShizukuResult.Failure -> {
                    lastFailure = result
                    if (!isRetryableError(result.reason) || attempt == 2) {
                        return@withContext result
                    }
                    Log.w(TAG, "Transient Shizuku UserService error; retry=${attempt + 1}: ${result.reason}")
                    delay(400L * (1L shl attempt))
                }
            }
        }
        lastFailure
    }

    private suspend fun executeOnce(command: String, timeoutMs: Long): ShizukuResult {
        return try {
            val result = ShizukuUserServiceClient.execute(command, timeoutMs)
            val output = result.mergedOutput().trim().take(MAX_OUTPUT_CHARS)
            val semanticFailure = ToolExecutionSemantics.classifyText(output)
            Log.d(
                TAG,
                "UserService uid=${result.uid} exit=${result.exitCode} timeout=${result.timedOut} semantic=$semanticFailure output=${output.length}"
            )

            when {
                result.timedOut -> ShizukuResult.Failure(
                    result.error ?: "Execution timeout exceeded (${timeoutMs / 1000}s): ${command.take(80)}"
                )
                result.error != null -> ShizukuResult.Failure(result.error)
                semanticFailure != null -> ShizukuResult.Failure(
                    "$semanticFailure: ${output.ifBlank { "command emitted a fatal failure signature" }.take(2_000)}"
                )
                result.exitCode != 0 -> {
                    if (result.exitCode == 127 || output.contains("not found", ignoreCase = true)) {
                        ShizukuResult.Failure("Command not found (exit=${result.exitCode}): ${output.take(500)}")
                    } else {
                        ShizukuResult.Failure(
                            "Command failed (exit=${result.exitCode}): ${output.ifBlank { "(no output)" }.take(2_000)}"
                        )
                    }
                }
                else -> {
                    when (val verification = verifySupportedMutation(command, timeoutMs)) {
                        null -> ShizukuResult.Success(output.ifBlank { "(no output)" })
                        is MutationVerification.Verified -> ShizukuResult.Success(
                            buildString {
                                if (output.isNotBlank()) append(output).appendLine()
                                append("[verified] ").append(verification.evidence)
                            }
                        )
                        is MutationVerification.Failed -> ShizukuResult.Failure(
                            "POSTCONDITION_FAILED: ${verification.reason}"
                        )
                    }
                }
            }
        } catch (e: SecurityException) {
            ShizukuResult.PermissionRequired("Shizuku denied permission: ${e.message}")
        } catch (t: Throwable) {
            val root = unwrapCause(t)
            Log.e(TAG, "Shizuku UserService execution failed", root)
            ShizukuResult.Failure(
                "Shizuku UserService error: ${root.javaClass.simpleName}: ${root.message.orEmpty().take(300)}"
            )
        }
    }

    /**
     * Verify state mutations that have a cheap authoritative read-back.
     * Compound scripts may contain one supported mutation; each detected mutation
     * is verified independently. Values are restricted by higher-level sanitizers.
     */
    private suspend fun verifySupportedMutation(
        command: String,
        timeoutMs: Long
    ): MutationVerification? {
        SETTINGS_PUT.find(command)?.let { match ->
            val namespace = match.groupValues[1].lowercase()
            val key = match.groupValues[2]
            val expected = shellTokenValue(match.groupValues[3])
            val probe = ShizukuUserServiceClient.execute(
                "settings get $namespace $key",
                timeoutMs.coerceAtMost(10_000L)
            )
            if (probe.timedOut || probe.error != null || probe.exitCode != 0) {
                return MutationVerification.Failed(
                    "settings put returned exit 0, but read-back failed for $namespace/$key: ${probe.mergedOutput().take(500)}"
                )
            }
            val actual = probe.stdout.trim()
            return if (actual == expected) {
                MutationVerification.Verified("settings $namespace/$key=$actual")
            } else {
                MutationVerification.Failed(
                    "settings $namespace/$key expected '$expected' but read-back returned '$actual'"
                )
            }
        }

        SETPROP.find(command)?.let { match ->
            val key = match.groupValues[1]
            val expected = shellTokenValue(match.groupValues[2])
            val probe = ShizukuUserServiceClient.execute(
                "getprop $key",
                timeoutMs.coerceAtMost(10_000L)
            )
            if (probe.timedOut || probe.error != null || probe.exitCode != 0) {
                return MutationVerification.Failed(
                    "setprop returned exit 0, but getprop verification failed for $key: ${probe.mergedOutput().take(500)}"
                )
            }
            val actual = probe.stdout.trim()
            return if (actual == expected) {
                MutationVerification.Verified("property $key=$actual")
            } else {
                MutationVerification.Failed(
                    "property $key expected '$expected' but read-back returned '$actual'"
                )
            }
        }
        return null
    }

    private fun shellTokenValue(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.length >= 2 && trimmed.first() == '\'' && trimmed.last() == '\'' ->
                trimmed.substring(1, trimmed.length - 1).replace("'\\''", "'")
            trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"' ->
                trimmed.substring(1, trimmed.length - 1)
            else -> trimmed
        }
    }

    private sealed class MutationVerification {
        data class Verified(val evidence: String) : MutationVerification()
        data class Failed(val reason: String) : MutationVerification()
    }

    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    suspend fun privilegedUidOrNull(): Int? = runCatching {
        if (!isAvailable() || !hasPermission() || !ShizukuUserServiceClient.isInitialized()) null
        else ShizukuUserServiceClient.ping()
            .substringAfter("uid=", "")
            .toIntOrNull()
    }.getOrNull()

    private fun requestAndWaitPermission() {
        runCatching { Shizuku.requestPermission(SHIZUKU_CODE) }
        val deadline = System.currentTimeMillis() + PERMISSION_WAIT_MS
        while (!hasPermission() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(PERMISSION_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    private fun unwrapCause(error: Throwable): Throwable {
        val cause = error.cause
        return if (cause != null && cause !== error) unwrapCause(cause) else error
    }

    private fun isRetryableError(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("deadobject") ||
            lower.contains("remoteexception") ||
            lower.contains("binder") ||
            lower.contains("transaction failed") ||
            lower.contains("service disconnected") ||
            lower.contains("user service") ||
            lower.contains("shizuku_connection_timeout")
    }

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
            message.contains("user service") ||
            message.contains("transaction failed")
    }
}

/**
 * PartialSuccess is retained for binary/source compatibility with older callers,
 * but the authoritative UserService executor no longer emits it for non-zero exits.
 */
sealed class ShizukuResult {
    data class Success(val output: String) : ShizukuResult()
    data class PartialSuccess(val output: String, val exitCode: Int) : ShizukuResult()
    data class Failure(val reason: String) : ShizukuResult()
    data class PermissionRequired(val message: String) : ShizukuResult()
    data class Unavailable(val message: String) : ShizukuResult()

    fun toDisplayString(): String = when (this) {
        is Success -> output
        is PartialSuccess -> "Error: command exited $exitCode: $output"
        is Failure -> "Error: $reason"
        is PermissionRequired -> "Permission required: $message"
        is Unavailable -> "Unavailable: $message"
    }

    fun hasUsefulOutput(): Boolean = when (this) {
        is Success -> output.isNotBlank() && output != "(no output)"
        is PartialSuccess -> output.isNotBlank() && output != "(no output)"
        else -> false
    }

    fun outputOrNull(): String? = when (this) {
        is Success -> output.takeIf { it.isNotBlank() && it != "(no output)" }
        is PartialSuccess -> output.takeIf { it.isNotBlank() && it != "(no output)" }
        else -> null
    }
}
