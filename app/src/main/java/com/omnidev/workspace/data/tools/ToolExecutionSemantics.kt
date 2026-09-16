package com.omnidev.workspace.data.tools

/**
 * Converts raw shell/tool observations into reliable semantic outcomes.
 *
 * The important invariant is: transport success or a final `echo` with exit 0
 * must never hide a strong failure emitted earlier in stderr. This classifier is
 * intentionally conservative and only upgrades results when the output contains
 * unambiguous failure signatures seen in Android/Termux/Shizuku execution.
 */
object ToolExecutionSemantics {

    private const val DEFAULT_MODEL_OUTPUT_LIMIT = 4_000
    private const val ERROR_MODEL_OUTPUT_LIMIT = 6_000

    private val terminalLikeTools = setOf(
        "agent_runtime",
        "privileged_tool",
        "shizuku_command",
        "direct_terminal",
        "termux_bridge",
        "advanced_terminal",
        "python_runtime",
        "setup_build_environment",
        "execution_diagnostics",
        "root_shell_tool",
        "advanced_root_shell"
    )

    private data class Match(
        val classification: String,
        val retryable: Boolean = false,
        val persistent: Boolean = false
    )

    fun normalize(toolName: String, result: ToolExecutionResult): ToolExecutionResult {
        if (result.isError) {
            return compact(
                result.copy(
                    classification = result.classification ?: classifyText(result.output)?.classification ?: "TOOL_ERROR"
                )
            )
        }
        if (toolName !in terminalLikeTools) return compact(result)

        val text = result.output
        val explicitExit = extractExitCode(text)
        if (explicitExit != null && explicitExit != 0) {
            val match = classifyText(text) ?: Match("NON_ZERO_EXIT")
            return compact(
                result.copy(
                    isError = true,
                    classification = match.classification,
                    exitCode = explicitExit,
                    retryable = match.retryable,
                    persistentFailure = match.persistent
                )
            )
        }

        val match = classifyText(text) ?: return compact(
            result.copy(classification = result.classification ?: "SUCCESS")
        )

        return compact(
            result.copy(
                isError = true,
                classification = match.classification,
                retryable = match.retryable,
                persistentFailure = match.persistent
            )
        )
    }

    /**
     * Strong failure signatures only. Do not classify generic words like "error"
     * because tools may legitimately inspect logs or source code containing them.
     */
    fun classifyText(text: String): String? = classifyMatch(text)?.classification

    fun isPersistentFailure(result: ToolExecutionResult): Boolean =
        result.persistentFailure || result.classification in setOf(
            "RISH_NATIVE_LOADER_FAILURE",
            "RISH_DEX_MISSING",
            "RISH_LAYOUT_BROKEN",
            "ROOT_UNAVAILABLE",
            "WRONG_EXECUTION_DOMAIN",
            "ANDROID_PERMISSION_DENIED"
        )

    private fun classifyTextInternal(text: String): Match? = classifyMatch(text)

    private fun classifyMatch(text: String): Match? {
        val lower = text.lowercase()
        return when {
            lower.contains("couldn't find \"librish.so\"") ||
                lower.contains("could not find \"librish.so\"") ||
                lower.contains("unsatisfiedlinkerror") && lower.contains("librish") ->
                Match("RISH_NATIVE_LOADER_FAILURE", persistent = true)

            lower.contains("cannot find") && lower.contains("rish_shizuku.dex") ->
                Match("RISH_DEX_MISSING", persistent = true)

            lower.contains("omnidev_rish_layout_error") ||
                lower.contains("$prefix/bin/rish is a directory") ->
                Match("RISH_LAYOUT_BROKEN", persistent = true)

            lower.contains("no su program found") ||
                lower.contains("su: not found") ->
                Match("ROOT_UNAVAILABLE", persistent = true)

            lower.contains("securityexception") && lower.contains("permission denial") ->
                Match("ANDROID_PERMISSION_DENIED", persistent = true)

            lower.contains("exception occurred while executing") && lower.contains("permission") ->
                Match("ANDROID_PERMISSION_DENIED", persistent = true)

            lower.contains("requires android.permission.interact_across_users") ||
                lower.contains("requires android.permission.clear_app_cache") ->
                Match("ANDROID_PERMISSION_DENIED", persistent = true)

            lower.contains("request timeout") && lower.contains("shizuku") ->
                Match("SHIZUKU_CONNECTION_TIMEOUT", retryable = true)

            lower.contains("command not found") ||
                Regex("(?m)(^|[: ])[^\\n]*: not found(?:$|\\n)").containsMatchIn(lower) ->
                Match("COMMAND_NOT_FOUND")

            lower.contains("unknown command '") || lower.contains("unknown command:") ->
                Match("UNSUPPORTED_COMMAND")

            lower.contains("permission denied") &&
                (lower.contains("/data/") || lower.contains("operation not permitted")) ->
                Match("FILESYSTEM_PERMISSION_DENIED")

            lower.contains("wrong_execution_domain") ->
                Match("WRONG_EXECUTION_DOMAIN", persistent = true)

            else -> null
        }
    }

    private fun extractExitCode(text: String): Int? {
        Regex("\\[exit_code:\\s*(-?\\d+)]", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        Regex("terminal failed \\(exit=(-?\\d+)\\)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        Regex("\\bexit=(-?\\d+)\\b", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private fun compact(result: ToolExecutionResult): ToolExecutionResult {
        val maxChars = if (result.isError) ERROR_MODEL_OUTPUT_LIMIT else DEFAULT_MODEL_OUTPUT_LIMIT
        if (result.output.length <= maxChars) return result

        val head = (maxChars * 2) / 3
        val tail = maxChars - head
        val compacted = buildString(maxChars + 180) {
            append(result.output.take(head))
            append("\n\n… [observation compacted: ")
            append(result.output.length - maxChars)
            append(" chars omitted] …\n\n")
            append(result.output.takeLast(tail))
        }
        return result.copy(output = compacted, truncated = true)
    }
}
