package com.omnidev.workspace.data.tools

import android.os.Build
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager

/**
 * Deterministic local router for Android shell commands accidentally sent to a generic terminal.
 *
 * The LLM no longer has to discover the execution domain by trying app shell -> Termux -> su.
 * Commands such as content/pm/settings/cmd/dumpsys are recognized before execution and are sent
 * directly to the Shizuku UserService shell. rish/root are only fallback transports when Shizuku
 * itself is unavailable, never retries for a syntactically/semantically failed Shizuku command.
 */
object AndroidPrivilegedCommandRouter {

    private const val DEFAULT_TIMEOUT_MS = 45_000L

    suspend fun executeIfNeeded(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ToolExecutionResult? {
        val violation = ExecutionDomainGuard.findViolation(command) ?: return null
        val prepared = ExecutionDomainGuard.preparePrivilegedCommand(command)
        val adapted = AndroidShellCommandCompatibility.adapt(prepared.command, Build.VERSION.SDK_INT)
        val executionCommand = adapted.command
        fun note(output: String): String = adapted.note?.let { it + "\n" + output } ?: output

        // Preserve explicitly requested execution semantics. rish is an ADB-equivalent shell
        // transport; su means root. Neither should be silently downgraded to Shizuku shell uid.
        if (violation.commandFamily == "rish") {
            return executeViaRish(prepared)
        }
        if (violation.commandFamily == "su") {
            val innerDomain = ExecutionDomainGuard.findViolation(prepared.command)
            if (innerDomain == null || innerDomain.commandFamily in setOf("su", "rish")) {
                return ToolExecutionResult(
                    output =
                        "WRONG_EXECUTION_DOMAIN: su/root semantics cannot be silently replaced by " +
                            "Shizuku shell uid. Use root_shell_tool for a genuinely root-only command. " +
                            "For ordinary Android content/settings/pm/cmd work, call it directly without su.",
                    isError = true,
                    classification = "WRONG_EXECUTION_DOMAIN",
                    backend = "android-domain-router",
                    persistentFailure = true
                )
            }
        }

        return when (val shizuku = ShizukuCommandTool.execute(executionCommand, timeoutMs)) {
            is ShizukuResult.Success -> ToolExecutionResult(
                output = note(ExecutionDomainGuard.applyOutputCompatibility(shizuku.output, prepared)),
                isError = false,
                classification = "SUCCESS",
                exitCode = 0,
                backend = "shizuku-user-service",
                verification = if (adapted.note != null) adapted.note else "auto-routed ${violation.commandFamily} to Android shell"
            )

            is ShizukuResult.PartialSuccess -> ToolExecutionResult(
                output = note(ExecutionDomainGuard.applyOutputCompatibility(shizuku.output, prepared)),
                isError = true,
                classification = ToolExecutionSemantics.classifyText(shizuku.output)
                    ?: "NON_ZERO_EXIT",
                exitCode = shizuku.exitCode,
                backend = "shizuku-user-service"
            )

            is ShizukuResult.Failure -> ToolExecutionResult(
                output = note("Android privileged command failed: ${shizuku.reason}"),
                isError = true,
                classification = ToolExecutionSemantics.classifyText(shizuku.reason)
                    ?: "ANDROID_SHELL_COMMAND_FAILED",
                backend = "shizuku-user-service",
                persistentFailure = ToolExecutionSemantics.classifyText(shizuku.reason) ==
                    "ANDROID_PERMISSION_DENIED"
            )

            is ShizukuResult.PermissionRequired -> ToolExecutionResult(
                output = shizuku.message,
                isError = true,
                classification = "SHIZUKU_PERMISSION_REQUIRED",
                backend = "shizuku-user-service",
                retryable = false,
                persistentFailure = true
            )

            is ShizukuResult.Unavailable -> fallbackWithoutShizuku(prepared, shizuku.message)
        }
    }

    private suspend fun executeViaRish(
        prepared: ExecutionDomainGuard.PreparedPrivilegedCommand
    ): ToolExecutionResult {
        val rishManager = PrivilegedExecutionManager.getRishManager()
            ?: return ToolExecutionResult(
                output = "rish was explicitly requested, but no rish manager is initialized.",
                isError = true,
                classification = "RISH_UNAVAILABLE",
                backend = "rish",
                persistentFailure = true
            )

        return rishManager.execute(prepared.command).fold(
            onSuccess = { output ->
                ToolExecutionResult(
                    output = ExecutionDomainGuard.applyOutputCompatibility(output, prepared),
                    isError = false,
                    classification = "SUCCESS",
                    backend = "rish",
                    verification = "explicit rish command completed through functional rish backend"
                )
            },
            onFailure = { error ->
                val message = error.message.orEmpty().ifBlank { "rish execution failed." }
                ToolExecutionResult(
                    output = message,
                    isError = true,
                    classification = ToolExecutionSemantics.classifyText(message)
                        ?: "RISH_UNAVAILABLE",
                    backend = "rish",
                    persistentFailure = true
                )
            }
        )
    }

    private suspend fun fallbackWithoutShizuku(
        prepared: ExecutionDomainGuard.PreparedPrivilegedCommand,
        unavailableMessage: String
    ): ToolExecutionResult {
        // rish uses the same Shizuku binder and permission. It cannot repair either missing
        // prerequisite; probing it here used to relabel SHIZUKU_UNAVAILABLE as RISH_UNAVAILABLE.
        if (!ShizukuCommandTool.isAvailable() || !ShizukuCommandTool.hasPermission()) {
            return ToolExecutionResult(
                output = "$unavailableMessage\nAndroid shell is blocked. Start Shizuku and grant OmniDev access, " +
                    "then run env_probe. rish shares these prerequisites. Termux developer commands remain independent; " +
                    "changing PATH or installing packages cannot grant shell privileges.",
                isError = true,
                classification = if (!ShizukuCommandTool.isAvailable()) "SHIZUKU_UNAVAILABLE" else "SHIZUKU_PERMISSION_REQUIRED",
                backend = "shizuku-user-service",
                persistentFailure = true
            )
        }
        val rishManager = PrivilegedExecutionManager.getRishManager()
            ?: return ToolExecutionResult(
                output = buildString {
                    appendLine(unavailableMessage)
                    append(
                        "Android command was not sent to Termux/app shell because that would run " +
                            "under the wrong UID. No rish manager is initialized. " +
                            "Root is never used as an implicit fallback."
                    )
                }.trimEnd(),
                isError = true,
                classification = "ANDROID_BACKEND_UNAVAILABLE",
                backend = "android-domain-router",
                persistentFailure = true
            )

        val fallback = rishManager.execute(prepared.command)
        return fallback.fold(
            onSuccess = { output ->
                ToolExecutionResult(
                    output = ExecutionDomainGuard.applyOutputCompatibility(output, prepared),
                    isError = false,
                    classification = "SUCCESS",
                    backend = "rish",
                    verification = "Shizuku unavailable; command completed through functional rish backend"
                )
            },
            onFailure = { error ->
                val message = error.message.orEmpty().ifBlank { "rish fallback failed." }
                ToolExecutionResult(
                    output = buildString {
                        appendLine(unavailableMessage)
                        append(message)
                    }.trimEnd(),
                    isError = true,
                    classification = ToolExecutionSemantics.classifyText(message)
                        ?: "RISH_UNAVAILABLE",
                    backend = "rish",
                    persistentFailure = true
                )
            }
        )
    }

}
