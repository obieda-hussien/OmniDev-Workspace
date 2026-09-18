package com.omnidev.workspace.data.tools

/**
 * Decides whether a privileged shell command may be retried automatically after a transport-level
 * failure whose execution status is uncertain.
 *
 * Reads and idempotent setters may be retried. Commands that can duplicate user-visible side
 * effects execute at-most-once unless a higher-level caller explicitly re-plans.
 */
object ExecutionRetryPolicy {

    private val idempotentSetters = listOf(
        Regex("(?is)^\\s*settings\\s+put\\b"),
        Regex("(?is)^\\s*setprop\\b"),
        Regex("(?is)^\\s*cmd\\s+location\\s+set-location-enabled\\b"),
        Regex("(?is)^\\s*svc\\s+(?:wifi|data|bluetooth|nfc)\\s+(?:enable|disable)\\b"),
        Regex("(?is)^\\s*wm\\s+(?:size|density)\\s+(?:reset|[0-9x]+)\\b")
    )

    private val genericReads = listOf(
        Regex("(?is)^\\s*dumpsys(?:\\s|$)"),
        Regex("(?is)^\\s*getprop(?:\\s|$)"),
        Regex("(?is)^\\s*ps(?:\\s|$)"),
        Regex("(?is)^\\s*id(?:\\s|$)"),
        Regex("(?is)^\\s*whoami(?:\\s|$)"),
        Regex("(?is)^\\s*cat\\s+"),
        Regex("(?is)^\\s*ls(?:\\s|$)"),
        Regex("(?is)^\\s*stat\\s+"),
        Regex("(?is)^\\s*which\\s+"),
        Regex("(?is)^\\s*command\\s+-v\\s+")
    )

    fun isSafeToRetry(command: String): Boolean {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return false

        val segments = splitSimpleChain(trimmed)
        if (segments.size > 1) return segments.all(::isSafeSingleCommand)
        return isSafeSingleCommand(trimmed)
    }

    private fun isSafeSingleCommand(command: String): Boolean {
        if (ExecutionDomainGuard.isReadOnlyPrivilegedCommand(command)) return true
        if (idempotentSetters.any { it.containsMatchIn(command) }) return true
        if (genericReads.any { it.containsMatchIn(command) }) return true
        return false
    }

    private fun splitSimpleChain(command: String): List<String> {
        if (command.contains('$') || command.contains('`') || command.contains("\n") || command.contains("||")) {
            return listOf(command)
        }
        return command
            .split(Regex("\\s*(?:&&|;)\\s*"))
            .map(String::trim)
            .filter(String::isNotBlank)
            .ifEmpty { listOf(command) }
    }
}
