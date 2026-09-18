package com.omnidev.workspace.data.tools

/**
 * Lightweight shell-aware execution-domain detector.
 *
 * This is intentionally not a full POSIX parser. Its job is narrower: keep Android
 * system-shell work out of the developer/Termux domain while allowing source code and
 * documentation to mention words such as rish, settings or content.
 *
 * The same detector is also used by run_terminal/agent_runtime to auto-route Android
 * commands to Shizuku instead of forcing the model to discover the correct backend by
 * trial and error.
 */
object ExecutionDomainGuard {

    data class Violation(
        val commandFamily: String,
        val lineNumber: Int,
        val linePreview: String
    ) {
        fun message(): String =
            "WRONG_EXECUTION_DOMAIN: '${commandFamily}' requires Android privileged execution " +
                "(line ${lineNumber}). OmniDev should route it through Shizuku UserService; " +
                "do not retry the same command through Termux, su, or app-process shell. " +
                "Offending command: ${linePreview.take(220)}"
    }

    data class PreparedPrivilegedCommand(
        val command: String,
        val contentQueryRowLimit: Int? = null,
        val compatibilityNotes: List<String> = emptyList()
    )

    private val hereDocStart = Regex("<<-?\\s*['\"]?([A-Za-z0-9_.-]+)['\"]?")

    private val privilegedAtCommandPosition = Regex(
        pattern = "(?i)(?:^|[;&|]\\s*|\\$\\(\\s*|`\\s*|\\bthen\\s+|\\bdo\\s+)(?:" +
            "(?:[A-Za-z_][A-Za-z0-9_]*=[^\\s;]+\\s+)*" +
            "(adb\\s+shell|rish|su\\s+-c|content|settings\\s+(?:get|put|delete|list)|" +
            "dumpsys|getprop|setprop|pm|am|cmd|wm|svc|input|appops|device_config|ime|monkey)" +
            "(?=\\s|$|[;)`]))"
    )

    private val contentQueryLimit = Regex(
        "(?i)(?<!\\S)--limit\\s+(\\d{1,4})(?=\\s|$)"
    )

    /** Returns null when a script belongs in the normal developer/Termux domain. */
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
                    .lowercase()
                    .let {
                        when {
                            it.startsWith("adb ") -> "adb"
                            it.startsWith("su ") -> "su"
                            else -> it.substringBefore(' ')
                        }
                    }
                return Violation(family, lineNumber, trimmed)
            }

            hereDocStart.find(code)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let {
                hereDocDelimiter = it
            }
        }
        return null
    }

    /**
     * Compatibility normalization for commands that express valid agent intent but are not valid
     * Android shell syntax on every platform build.
     *
     * adb shell content query has no --limit option. OmniDev accepts it as a convenience,
     * removes it before execution, then limits Row blocks in the returned data.
     */
    fun preparePrivilegedCommand(script: String): PreparedPrivilegedCommand {
        var command = script.trim()
        val notes = mutableListOf<String>()

        if (Regex("(?i)^adb\\s+shell\\s+").containsMatchIn(command)) {
            command = command.replaceFirst(Regex("(?i)^adb\\s+shell\\s+"), "")
            notes += "removed redundant adb shell wrapper"
        }

        command = unwrapSimpleShellWrapper(command, "su")?.also {
            notes += "removed unnecessary su wrapper; Shizuku already supplies Android shell identity"
        } ?: command

        command = unwrapSimpleShellWrapper(command, "rish")?.also {
            notes += "replaced rish wrapper with direct Shizuku shell execution"
        } ?: command

        var rowLimit: Int? = null
        val isContentQuery = Regex("(?i)(?:^|[;&|]\\s*)content\\s+query\\b")
            .containsMatchIn(command)
        if (isContentQuery) {
            val match = contentQueryLimit.find(command)
            if (match != null) {
                rowLimit = match.groupValues[1].toIntOrNull()?.coerceIn(1, 500)
                command = contentQueryLimit.replace(command, "").replace(Regex("\\s+"), " ").trim()
                if (rowLimit != null) {
                    notes += "implemented unsupported content-query --limit=$rowLimit in OmniDev output adapter"
                }
            }
        }

        return PreparedPrivilegedCommand(command, rowLimit, notes)
    }

    /** Preserve multiline content rows while applying a model-requested logical row limit. */
    fun applyOutputCompatibility(
        output: String,
        prepared: PreparedPrivilegedCommand
    ): String {
        val limit = prepared.contentQueryRowLimit ?: return withCompatibilityNotes(output, prepared)
        val starts = Regex("(?m)^Row:\\s+\\d+\\b").findAll(output).toList()
        if (starts.size <= limit) return withCompatibilityNotes(output, prepared)

        val cutAt = starts[limit].range.first
        val limited = buildString {
            append(output.substring(0, cutAt).trimEnd())
            appendLine()
            append("[OmniDev limited content query to first $limit of ${starts.size}+ returned rows]")
        }
        return withCompatibilityNotes(limited, prepared)
    }

    private fun withCompatibilityNotes(
        output: String,
        prepared: PreparedPrivilegedCommand
    ): String {
        if (prepared.compatibilityNotes.isEmpty()) return output
        return buildString {
            appendLine("[OmniDev execution compatibility: ${prepared.compatibilityNotes.joinToString("; ")}]")
            append(output)
        }.trimEnd()
    }

    private fun unwrapSimpleShellWrapper(command: String, wrapper: String): String? {
        val regex = Regex("(?is)^\\s*${Regex.escape(wrapper)}\\s+-c\\s+(.+?)\\s*$")
        val payload = regex.find(command)?.groupValues?.getOrNull(1)?.trim() ?: return null
        if (payload.length >= 2) {
            val first = payload.first()
            val last = payload.last()
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return payload.substring(1, payload.length - 1)
            }
        }
        return payload.takeIf { it.isNotBlank() }
    }

    /**
     * Remove comments only when # occurs outside single/double quotes. Quoted command
     * substitutions remain visible because they execute in the shell.
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
