package com.omnidev.workspace.data.tools

/**
 * Lightweight shell-aware execution-domain detector.
 *
 * This is intentionally not a full POSIX parser. Its job is narrower: prevent the
 * generic Termux agent shell from *executing* Android privileged commands while
 * still allowing the agent to edit/source code that merely contains words such as
 * `rish`, `settings`, or `dumpsys`.
 *
 * The scanner ignores here-document bodies and comments, and recognizes both
 * top-level command positions and command substitutions such as `$(rish -c ...)`.
 */
object ExecutionDomainGuard {

    data class Violation(
        val commandFamily: String,
        val lineNumber: Int,
        val linePreview: String
    ) {
        fun message(): String =
            "WRONG_EXECUTION_DOMAIN: '$commandFamily' requires Android privileged execution " +
                "(line $lineNumber). Use privileged_tool / Shizuku UserService; use the " +
                "dedicated rish actions only for explicit ADB-equivalent shell work. " +
                "Offending command: ${linePreview.take(220)}"
    }

    private val hereDocStart = Regex("<<-?\\s*['\"]?([A-Za-z0-9_.-]+)['\"]?")

    private val privilegedAtCommandPosition = Regex(
        pattern = "(?i)(?:^|[;&|]\\s*|\\$\\(\\s*|`\\s*|\\bthen\\s+|\\bdo\\s+)(?:" +
            "(?:[A-Za-z_][A-Za-z0-9_]*=[^\\s;]+\\s+)*" +
            "(rish|su\\s+-c|settings\\s+(?:get|put|delete|list)|dumpsys|getprop|setprop|" +
            "pm|am|cmd|wm|svc|input)(?=\\s|$|[;)`])"
    )

    /** Returns null when a script is appropriate for the normal Termux domain. */
    fun findViolation(script: String): Violation? {
        var hereDocDelimiter: String? = null

        script.lineSequence().forEachIndexed { zeroBased, rawLine ->
            val lineNumber = zeroBased + 1
            val trimmed = rawLine.trim()

            hereDocDelimiter?.let { delimiter ->
                val normalized = trimmed.removePrefix("-").trim()
                if (normalized == delimiter) hereDocDelimiter = null
                return@forEachIndexed
            }

            if (trimmed.isBlank() || trimmed.startsWith("#")) return@forEachIndexed

            val code = stripCommentOutsideQuotes(rawLine)
            val match = privilegedAtCommandPosition.find(code)
            if (match != null) {
                val family = match.groupValues[1]
                    .trim()
                    .substringBefore(' ')
                    .lowercase()
                return Violation(family, lineNumber, trimmed)
            }

            // The command that opens a heredoc is legitimate (e.g. cat > script.sh).
            // Only subsequent heredoc payload lines are ignored until the delimiter.
            hereDocStart.find(code)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let {
                hereDocDelimiter = it
            }
        }
        return null
    }

    /**
     * Remove comments only when # occurs outside single/double quotes. We keep quoted
     * command substitutions because those execute in shell (e.g. "$(rish -c id)").
     */
    private fun stripCommentOutsideQuotes(line: String): String {
        var single = false
        var double = false
        var escaped = false
        line.forEachIndexed { index, c ->
            if (escaped) {
                escaped = false
                return@forEachIndexed
            }
            if (c == '\\' && !single) {
                escaped = true
                return@forEachIndexed
            }
            when (c) {
                '\'' -> if (!double) single = !single
                '"' -> if (!single) double = !double
                '#' -> if (!single && !double && (index == 0 || line[index - 1].isWhitespace())) {
                    return line.substring(0, index)
                }
            }
        }
        return line
    }
}
