package com.omnidev.workspace.data.tools

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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

    /**
     * Termux uses Activity.RESULT_OK (-1) for "no internal/plugin error".
     * This is NOT Errno 0. Treating 0 as success caused valid exit=0 commands
     * to be reported as failures with `[Termux err=-1]`.
     */
    private const val TERMUX_RESULT_OK = Activity.RESULT_OK
    private const val REQUEST_CODE_RUN_COMMAND = 0x544D // "TM"

    const val EXTRA_EXECUTION_ID = "com.omnidev.workspace.termux.EXECUTION_ID"

    private const val DEFAULT_TIMEOUT_MS = 90_000L
    private const val MAX_TIMEOUT_MS = 20 * 60_000L
    private const val PERMISSION_WAIT_MS = 15_000L
    private const val PERMISSION_POLL_MS = 200L
    private const val AGENT_TERMINAL_LABEL = "OmniDev agent terminal"

    private const val ENABLE_EXTERNAL_APPS_COMMAND =
        "mkdir -p ~/.termux; " +
            "touch ~/.termux/termux.properties; " +
            "if grep -q '^allow-external-apps=' ~/.termux/termux.properties; then " +
            "sed -i 's/^allow-external-apps=.*/allow-external-apps=true/' ~/.termux/termux.properties; " +
            "else printf '\nallow-external-apps=true\n' >> ~/.termux/termux.properties; fi; " +
            "termux-reload-settings"

    enum class TransportHealth { UNKNOWN, HEALTHY, UNAVAILABLE }

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<TermuxCommandResult>>()
    private val externalAppsSetupShown = AtomicBoolean(false)

    @Volatile private var appContext: Context? = null
    @Volatile private var transportHealth: TransportHealth = TransportHealth.UNKNOWN

    fun cachedTransportHealth(): TransportHealth = transportHealth
    fun isKnownUnusable(): Boolean = transportHealth == TransportHealth.UNAVAILABLE

    /**
     * Explicit recovery gate. Normal probes respect a known transport failure so the agent does
     * not rediscover the same broken RunCommandService every iteration.
     */
    fun resetTransportHealth() {
        transportHealth = TransportHealth.UNKNOWN
    }

    private fun recordTransportHealth(result: TermuxCommandResult) {
        transportHealth = if (result.transportSucceeded) {
            TransportHealth.HEALTHY
        } else {
            TransportHealth.UNAVAILABLE
        }
    }

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
            get() = internalErrorCode == TERMUX_RESULT_OK

        /**
         * Detect unambiguous command failures hidden by a later successful shell
         * command. Only stderr + Termux internal errors are inspected: stdout is
         * data and may legitimately contain old crash logs/source code mentioning
         * SecurityException or other failure signatures.
         */
        val semanticFailureClassification: String?
            get() {
                val evidence = buildString {
                    if (stderr.isNotBlank()) append(stderr)
                    if (!internalError.isNullOrBlank()) {
                        if (isNotEmpty()) appendLine()
                        append(internalError)
                    }
                }
                return evidence.takeIf { it.isNotBlank() }
                    ?.let(ToolExecutionSemantics::classifyText)
            }

        val isSuccess: Boolean
            get() = transportSucceeded && exitCode == 0 && semanticFailureClassification == null

        val needsExternalAppsOptIn: Boolean
            get() = internalError?.contains("allow-external-apps", ignoreCase = true) == true

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
                if (needsExternalAppsOptIn) {
                    appendLine()
                    append("OmniDev copied the one-time setup command to the clipboard. Open Termux, paste it, run it once, then retry.")
                }
            }
            semanticFailureClassification?.let {
                if (isNotEmpty()) appendLine()
                append("[semantic_failure] ").append(it)
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
        transportHealth = TransportHealth.UNKNOWN
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
            appendLine("RUN_COMMAND permission: ${if (permission) "GRANTED" else "MISSING (requested automatically when first needed)"}")
            appendLine("Required one-time Termux opt-in: ~/.termux/termux.properties -> allow-external-apps=true")
            append("Execution transport: official com.termux.RUN_COMMAND / RunCommandService")
        }
    }

    /** Execute a shell script through Termux's own bash process. */
    suspend fun executeShell(
        script: String,
        cwd: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        label: String = "OmniDev terminal"
    ): TermuxCommandResult {
        if (label == AGENT_TERMINAL_LABEL) {
            ExecutionDomainGuard.findViolation(script)?.let { violation ->
                return setupFailure(violation.message())
            }
        }
        return execute(
            executable = TERMUX_BASH,
            arguments = arrayOf("-lc", script),
            cwd = cwd ?: TERMUX_HOME,
            timeoutMs = timeoutMs,
            label = label,
            description = "Command requested by OmniDev agent"
        )
    }

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
            val dialogStarted = PermissionRequestBridge.requestRuntimePermissions(
                arrayOf(PERMISSION_RUN_COMMAND),
                REQUEST_CODE_RUN_COMMAND
            )
            if (dialogStarted) {
                val deadline = System.currentTimeMillis() + PERMISSION_WAIT_MS
                while (!hasRunCommandPermission(context) && System.currentTimeMillis() < deadline) {
                    delay(PERMISSION_POLL_MS)
                }
            }
            if (!hasRunCommandPermission(context)) {
                return@withContext setupFailure(
                    if (dialogStarted) {
                        "RUN_COMMAND permission is required. OmniDev requested it at runtime; grant the dialog and retry the command."
                    } else {
                        "RUN_COMMAND permission is required, but no foreground Activity is available to show the Android permission dialog. " +
                            "Open OmniDev and retry, or grant 'Run commands in Termux environment' from App Info > Permissions > Additional permissions."
                    }
                )
            }
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
                    "Android could not resolve/start Termux RunCommandService. Verify that the official Termux app is installed and enabled."
                ).also(::recordTransportHealth)
            }
            withTimeout(effectiveTimeout) { deferred.await() }
                .also(::recordTransportHealth)
        } catch (t: Throwable) {
            pending.remove(executionId)
            callback.cancel()
            setupFailure(
                when (t) {
                    is kotlinx.coroutines.TimeoutCancellationException ->
                        "Timed out waiting for Termux result after ${effectiveTimeout}ms"
                    is SecurityException -> {
                        PermissionRequestBridge.requestRuntimePermissions(
                            arrayOf(PERMISSION_RUN_COMMAND),
                            REQUEST_CODE_RUN_COMMAND
                        )
                        "Termux denied RUN_COMMAND: ${t.message}. OmniDev requested the permission at runtime; grant it and retry."
                    }
                    else -> "Termux RunCommandService failure: ${t.javaClass.simpleName}: ${t.message}"
                }
            ).also(::recordTransportHealth)
        }
    }

    /** Called by [TermuxResultReceiver] in the app process. */
    fun deliverResult(intent: Intent) {
        val id = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
        if (id < 0) return
        val deferred = pending.remove(id) ?: return
        val bundle = intent.getBundleExtra(EXTRA_PLUGIN_RESULT_BUNDLE)
        if (bundle == null) {
            val failure = setupFailure("Termux returned no result bundle")
            recordTransportHealth(failure)
            deferred.complete(failure)
            return
        }

        val stdout = bundle.getString(RESULT_STDOUT).orEmpty()
        val stderr = bundle.getString(RESULT_STDERR).orEmpty()
        val stdoutOriginalLength = readLength(bundle, RESULT_STDOUT_ORIGINAL_LENGTH, stdout.length)
        val stderrOriginalLength = readLength(bundle, RESULT_STDERR_ORIGINAL_LENGTH, stderr.length)

        val result = TermuxCommandResult(
            exitCode = bundle.getInt(RESULT_EXIT_CODE, -1),
            stdout = stdout,
            stderr = stderr,
            stdoutOriginalLength = stdoutOriginalLength,
            stderrOriginalLength = stderrOriginalLength,
            internalErrorCode = bundle.getInt(RESULT_ERR, TERMUX_RESULT_OK),
            internalError = bundle.getString(RESULT_ERRMSG)
        )
        Log.d(
            TAG,
            "Termux result id=$id exit=${result.exitCode} internal=${result.internalErrorCode} semantic=${result.semanticFailureClassification}"
        )

        recordTransportHealth(result)
        if (result.needsExternalAppsOptIn) {
            presentExternalAppsSetup()
        }
        deferred.complete(result)
    }

    private fun readLength(bundle: Bundle, key: String, fallback: Int): Int {
        @Suppress("DEPRECATION")
        return when (val raw = bundle.get(key)) {
            is Int -> raw
            is Long -> raw.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            is String -> raw.toIntOrNull() ?: fallback
            else -> fallback
        }
    }

    /**
     * `allow-external-apps=true` is intentionally a Termux-side security opt-in.
     * OmniDev cannot silently rewrite another app's private files. When Termux
     * reports that policy failure, make setup as close to one-tap as Android allows:
     * copy the exact idempotent setup command and open Termux once.
     */
    private fun presentExternalAppsSetup() {
        if (!externalAppsSetupShown.compareAndSet(false, true)) return
        val activity = PermissionRequestBridge.foregroundActivity() ?: return
        activity.runOnUiThread {
            runCatching {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Enable OmniDev in Termux", ENABLE_EXTERNAL_APPS_COMMAND))
                activity.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)?.let { launch ->
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.startActivity(launch)
                }
            }.onFailure { Log.w(TAG, "Could not present Termux external-app setup: ${it.message}") }
        }
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
