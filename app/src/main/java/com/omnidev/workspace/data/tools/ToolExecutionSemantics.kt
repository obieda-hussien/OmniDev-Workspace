package com.omnidev.workspace.data.tools

/**
 * Converts raw shell/tool observations into reliable semantic outcomes.
 *
 * Transport success or a final `echo` must never hide an earlier shell failure,
 * but data printed on stdout (for example a log file containing SecurityException)
 * must not be mistaken for the current command failing. For nominally successful
 * results, semantic classification is therefore restricted to explicit exit markers,
 * stderr/internal-error sections and runtime-generated failure envelopes.
 */
object ToolExecutionSemantics {

    private const val DEFAULT_MODEL_OUTPUT_LIMIT = 4_000
    private const val ERROR_MODEL_OUTPUT_LIMIT = 6_000
    private const val TELEMETRY_PREFIX = "[omni-outcome]"

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
        val normalized = when {
            result.isError -> {
                // The tool already declared failure, so its whole diagnostic payload is evidence.
                val match = classifyMatch(result.output)
                result.copy(
                    classification = result.classification ?: match?.classification ?: "TOOL_ERROR",
                    backend = result.backend ?: inferBackend(toolName, result.output),
                    retryable = result.retryable || match?.retryable == true,
                    persistentFailure = result.persistentFailure || match?.persistent == true
                )
            }

            toolName !in terminalLikeTools -> result

            else -> {
                val text = result.output
                val explicitExit = extractExitCode(text)
                if (explicitExit != null && explicitExit != 0) {
                    val match = classifyMatch(text) ?: Match("NON_ZERO_EXIT")
                    result.copy(
                        isError = true,
                        classification = match.classification,
                        exitCode = explicitExit,
                        backend = result.backend ?: inferBackend(toolName, text),
                        retryable = match.retryable,
                        persistentFailure = match.persistent
                    )
                } else {
                    // Only inspect authoritative failure channels/envelopes here. Plain stdout
                    // may intentionally contain old crash logs or source code with error text.
                    val failureEvidence = failureSignalText(text)
                    val match = failureEvidence?.let(::classifyMatch)
                    if (match == null) {
                        result.copy(
                            classification = result.classification ?: "SUCCESS",
                            backend = result.backend ?: inferBackend(toolName, text)
                        )
                    } else {
                        result.copy(
                            isError = true,
                            classification = match.classification,
                            backend = result.backend ?: inferBackend(toolName, text),
                            retryable = match.retryable,
                            persistentFailure = match.persistent
                        )
                    }
                }
            }
        }

        return compact(decorate(normalized))
    }

    /** Strong failure signatures used when the caller already knows the text is an error channel. */
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

    /**
     * Extract only runtime-owned failure evidence from a mixed observation.
     * This prevents `cat crash.log` from failing merely because stdout contains a stack trace.
     */
    private fun failureSignalText(text: String): String? {
        val lower = text.lowercase()
        val stderrMarker = "\n[stderr]\n"
        val stderrIndex = lower.indexOf(stderrMarker)
        if (stderrIndex >= 0) return text.substring(stderrIndex + stderrMarker.length)

        val termuxMarker = "[termux]"
        val termuxIndex = lower.indexOf(termuxMarker)
        if (termuxIndex >= 0) return text.substring(termuxIndex)

        val semanticMarker = "[semantic_failure]"
        val semanticIndex = lower.indexOf(semanticMarker)
        if (semanticIndex >= 0) return text.substring(semanticIndex)

        val trimmed = text.trimStart()
        if (trimmed.startsWith("❌") ||
            trimmed.startsWith("Error:", ignoreCase = true) ||
            trimmed.startsWith("WRONG_EXECUTION_DOMAIN", ignoreCase = true) ||
            trimmed.startsWith("GITHUB_", ignoreCase = true)
        ) return trimmed

        return null
    }

    private fun decorate(result: ToolExecutionResult): ToolExecutionResult {
        if (result.output.startsWith(TELEMETRY_PREFIX)) return result
        val line = buildString {
            append(TELEMETRY_PREFIX)
            append(" status=").append(if (result.isError) "FAIL" else "PASS")
            append(" class=").append(result.classification ?: if (result.isError) "TOOL_ERROR" else "SUCCESS")
            result.backend?.takeIf { it.isNotBlank() }?.let { append(" backend=").append(it) }
            result.exitCode?.let { append(" exit=").append(it) }
            append(" retryable=").append(result.retryable)
            append(" persistent=").append(result.persistentFailure)
            result.verification?.takeIf { it.isNotBlank() }?.let {
                append(" verified=").append(it.replace('\n', ' ').take(240))
            }
        }
        return result.copy(output = "$line\n${result.output}".trimEnd())
    }

    private fun inferBackend(toolName: String, text: String): String? {
        val lower = text.lowercase()
        return when {
            lower.contains("uid=2000(shell)") && lower.contains("rish") -> "rish"
            lower.contains("shizuku userservice") || toolName == "shizuku_command" -> "shizuku-user-service"
            toolName == "privileged_tool" && lower.contains("rish") -> "rish"
            toolName == "privileged_tool" -> "privileged-router"
            toolName in setOf("agent_runtime", "direct_terminal", "termux_bridge", "python_runtime", "setup_build_environment") -> "termux"
            toolName in setOf("root_shell_tool", "advanced_root_shell") -> "root"
            else -> null
        }
    }

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
                lower.contains("\$prefix/bin/rish is a directory") ->
                Match("RISH_LAYOUT_BROKEN", persistent = true)

            lower.contains("no su program found") || lower.contains("su: not found") ->
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

        val firstLineEnd = result.output.indexOf('\n').takeIf { it >= 0 } ?: 0
        val telemetry = if (firstLineEnd > 0) result.output.substring(0, firstLineEnd + 1) else ""
        val body = if (firstLineEnd > 0) result.output.substring(firstLineEnd + 1) else result.output
        val available = (maxChars - telemetry.length - 120).coerceAtLeast(600)
        val head = (available * 2) / 3
        val tail = available - head
        val compacted = buildString(maxChars + 80) {
            append(telemetry)
            append(body.take(head))
            append("\n\n… [observation compacted: ")
            append((body.length - available).coerceAtLeast(0))
            append(" chars omitted] …\n\n")
            append(body.takeLast(tail))
        }
        return result.copy(output = compacted, truncated = true)
    }
}
