package com.omnidev.workspace.data.tools

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

        return when (val shizuku = ShizukuCommandTool.execute(prepared.command, timeoutMs)) {
            is ShizukuResult.Success -> ToolExecutionResult(
                output = ExecutionDomainGuard.applyOutputCompatibility(shizuku.output, prepared),
                isError = false,
                classification = "SUCCESS",
                exitCode = 0,
                backend = "shizuku-user-service",
                verification = "auto-routed ${violation.commandFamily} to Android shell"
            )

            is ShizukuResult.PartialSuccess -> ToolExecutionResult(
                output = ExecutionDomainGuard.applyOutputCompatibility(shizuku.output, prepared),
                isError = true,
                classification = ToolExecutionSemantics.classifyText(shizuku.output)
                    ?: "NON_ZERO_EXIT",
                exitCode = shizuku.exitCode,
                backend = "shizuku-user-service"
            )

            is ShizukuResult.Failure -> ToolExecutionResult(
                output = "Android privileged command failed: ${shizuku.reason}",
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

    private suspend fun fallbackWithoutShizuku(
        prepared: ExecutionDomainGuard.PreparedPrivilegedCommand,
        unavailableMessage: String
    ): ToolExecutionResult {
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
