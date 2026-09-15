package com.omnidev.workspace.data.tools

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Executes commands *inside the Termux app process* through Termux's official
 * RunCommandService API.
 *
 * Shizuku/ADB shell (uid=2000) normally cannot traverse `/data/data/com.termux`,
 * therefore Termux package/runtime commands must go through this bridge rather
 * than through a privileged Android shell.
 */
object TermuxRunCommandBridge {

    private const val TAG = "TermuxRunCommand"
    const val TERMUX_PACKAGE = "com.termux"
    const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val TERMUX_BASH = "$TERMUX_PREFIX/bin/bash"

    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    private const val EXTRA_COMMAND_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"
    private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    private const val EXTRA_PLUGIN_RESULT_BUNDLE = "result"
    private const val RESULT_STDOUT = "stdout"
    private const val RESULT_STDERR = "stderr"
    private const val RESULT_STDOUT_ORIGINAL_LENGTH = "stdout_original_length"
    private const val RESULT_STDERR_ORIGINAL_LENGTH = "stderr_original_length"
    private const val RESULT_EXIT_CODE = "exitCode"
    private const val RESULT_ERR = "err"
    private const val RESULT_ERRMSG = "errmsg"

    /** Termux ResultData/Errno success code. */
    private const val TERMUX_ERRNO_SUCCESS = 0

    const val EXTRA_EXECUTION_ID = "com.omnidev.workspace.termux.EXECUTION_ID"

    private const val DEFAULT_TIMEOUT_MS = 90_000L
    private const val MAX_TIMEOUT_MS = 20 * 60_000L

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<TermuxCommandResult>>()

    @Volatile private var appContext: Context? = null

    data class TermuxCommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val internalErrorCode: Int,
        val internalError: String?,
        val stdoutOriginalLength: Int,
        val stderrOriginalLength: Int
    ) {
        val transportSucceeded: Boolean
            get() = internalErrorCode == TERMUX_ERRNO_SUCCESS

        val isSuccess: Boolean
            get() = transportSucceeded && exitCode == 0

        val wasTruncated: Boolean
            get() = stdoutOriginalLength > stdout.length || stderrOriginalLength > stderr.length

        fun mergedOutput(): String = buildString {
            if (stdout.isNotBlank()) append(stdout.trimEnd())
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) appendLine().appendLine("[stderr]")
                append(stderr.trimEnd())
            }
            if (!internalError.isNullOrBlank()) {
                if (isNotEmpty()) appendLine()
                append("[termux] ").append(internalError)
            }
            if (wasTruncated) {
                if (isNotEmpty()) appendLine()
                append("[output truncated by Android binder: stdout=")
                    .append(stdoutOriginalLength)
                    .append(", stderr=")
                    .append(stderrOriginalLength)
                    .append(']')
            }
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isInitialized(): Boolean = appContext != null

    fun hasRunCommandPermission(context: Context = requireContext()): Boolean =
        context.checkSelfPermission(PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED

    fun isTermuxInstalled(context: Context = requireContext()): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun capabilityReport(context: Context = requireContext()): String {
        val packageVisible = isTermuxInstalled(context)
        val permission = hasRunCommandPermission(context)
        return buildString {
            appendLine("Termux package visible: ${if (packageVisible) "YES" else "NO"}")
            appendLine("RUN_COMMAND permission: ${if (permission) "GRANTED" else "MISSING"}")
            appendLine("Required Termux setting: ~/.termux/termux.properties -> allow-external-apps=true")
            append("Execution transport: official com.termux.RUN_COMMAND / RunCommandService")
        }
    }

    /** Execute a shell script through Termux's own bash process. */
    suspend fun executeShell(
        script: String,
        cwd: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        label: String = "OmniDev terminal"
    ): TermuxCommandResult = execute(
        executable = TERMUX_BASH,
        arguments = arrayOf("-lc", script),
        cwd = cwd ?: TERMUX_HOME,
        timeoutMs = timeoutMs,
        label = label,
        description = "Command requested by OmniDev agent"
    )

    suspend fun execute(
        executable: String,
        arguments: Array<String> = emptyArray(),
        stdin: String? = null,
        cwd: String = TERMUX_HOME,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        label: String = "OmniDev command",
        description: String? = null
    ): TermuxCommandResult = withContext(Dispatchers.IO) {
        require(executable.isNotBlank()) { "Termux executable is blank" }
        val context = requireContext()

        if (!isTermuxInstalled(context)) {
            return@withContext setupFailure(
                "Termux is not installed or is not visible to OmniDev. Install the official Termux app first."
            )
        }
        if (!hasRunCommandPermission(context)) {
            return@withContext setupFailure(
                "OmniDev does not have $PERMISSION_RUN_COMMAND. Grant 'Run commands in Termux environment' " +
                    "from Android App Info > Permissions > Additional permissions."
            )
        }

        val effectiveTimeout = timeoutMs.coerceIn(1_000L, MAX_TIMEOUT_MS)
        val executionId = nextId.getAndIncrement()
        val deferred = CompletableDeferred<TermuxCommandResult>()
        pending[executionId] = deferred

        val callbackIntent = Intent(context, TermuxResultReceiver::class.java).apply {
            action = "${context.packageName}.TERMUX_RESULT.$executionId"
            putExtra(EXTRA_EXECUTION_ID, executionId)
        }
        val flags = PendingIntent.FLAG_ONE_SHOT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val callback = PendingIntent.getBroadcast(context, executionId, callbackIntent, flags)

        val intent = Intent().apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            action = ACTION_RUN_COMMAND
            putExtra(EXTRA_COMMAND_PATH, executable)
            putExtra(EXTRA_ARGUMENTS, arguments)
            if (stdin != null) putExtra(EXTRA_STDIN, stdin)
            putExtra(EXTRA_WORKDIR, cwd)
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_COMMAND_LABEL, label.take(120))
            description?.let { putExtra(EXTRA_COMMAND_DESCRIPTION, it.take(500)) }
            putExtra(EXTRA_PENDING_INTENT, callback)
        }

        try {
            val component = context.startService(intent)
            if (component == null) {
                pending.remove(executionId)
                callback.cancel()
                return@withContext setupFailure(
                    "Termux RunCommandService could not be started. Ensure the official Termux app is installed."
                )
            }
            withTimeout(effectiveTimeout) { deferred.await() }
        } catch (t: Throwable) {
            pending.remove(executionId)
            callback.cancel()
            setupFailure(
                when (t) {
                    is kotlinx.coroutines.TimeoutCancellationException ->
                        "Timed out waiting for Termux result after ${effectiveTimeout}ms"
                    is SecurityException ->
                        "Termux denied RUN_COMMAND: ${t.message}. Grant $PERMISSION_RUN_COMMAND and set allow-external-apps=true."
                    else -> "Termux RunCommandService failure: ${t.javaClass.simpleName}: ${t.message}"
                }
            )
        }
    }

    /** Called by [TermuxResultReceiver] in the app process. */
    fun deliverResult(intent: Intent) {
        val id = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
        if (id < 0) return
        val deferred = pending.remove(id) ?: return
        val bundle = intent.getBundleExtra(EXTRA_PLUGIN_RESULT_BUNDLE)
        if (bundle == null) {
            deferred.complete(setupFailure("Termux returned no result bundle"))
            return
        }

        val stdout = bundle.getString(RESULT_STDOUT).orEmpty()
        val stderr = bundle.getString(RESULT_STDERR).orEmpty()
        // Termux's ResultSender serializes *_original_length as strings.
        val stdoutOriginalLength = bundle.getString(RESULT_STDOUT_ORIGINAL_LENGTH)
            ?.toIntOrNull() ?: stdout.length
        val stderrOriginalLength = bundle.getString(RESULT_STDERR_ORIGINAL_LENGTH)
            ?.toIntOrNull() ?: stderr.length

        val result = TermuxCommandResult(
            exitCode = bundle.getInt(RESULT_EXIT_CODE, -1),
            stdout = stdout,
            stderr = stderr,
            internalErrorCode = bundle.getInt(RESULT_ERR, 1),
            internalError = bundle.getString(RESULT_ERRMSG),
            stdoutOriginalLength = stdoutOriginalLength,
            stderrOriginalLength = stderrOriginalLength
        )
        Log.d(TAG, "Termux result id=$id exit=${result.exitCode} internal=${result.internalErrorCode}")
        deferred.complete(result)
    }

    private fun setupFailure(message: String) = TermuxCommandResult(
        exitCode = -1,
        stdout = "",
        stderr = "",
        internalErrorCode = 1,
        internalError = message,
        stdoutOriginalLength = 0,
        stderrOriginalLength = 0
    )

    private fun requireContext(): Context = checkNotNull(appContext) {
        "TermuxRunCommandBridge is not initialized. Call init(context) at app startup."
    }
}
