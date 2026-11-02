package com.omnidev.workspace.domain.engine

/**
 * Last-line hygiene for text shown to the user: removes tool-call envelopes, run-log lines and
 * stray foreign-script characters, and repairs Markdown that would otherwise render badly.
 */
object FinalAnswerSanitizer {

    private val logLine = Regex("(?m)^\\s*\\[\\+\\d+ms]\\s+(?:THINK|PHASE|TOKENS|RUN|OK|ERR|DEEP).*$\\n?")
    private val fencedEnvelope = Regex("(?s)```[a-zA-Z]*\\s*\\{\\s*\"omni_tool_call\".*?```\\s*")
    private val foreignScripts = Regex("[\\u0590-\\u05FF\\u0E00-\\u0E7F\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]")
    private val manyBlankLines = Regex("\\n{3,}")
    private val fenceOpenGlued = Regex("(?m)([^\\n])\\n(```[a-zA-Z]*\\n)")

    fun clean(text: String): String {
        if (text.isBlank()) return text
        var out = text.replace(fencedEnvelope, "")
        out = stripBareEnvelopes(out)
        out = out.replace(logLine, "")
        out = out.replace(foreignScripts, "")
        out = out.replace(fenceOpenGlued) { "${it.groupValues[1]}\n\n${it.groupValues[2]}" }
        if (Regex("```").findAll(out).count() % 2 == 1) out = out.trimEnd() + "\n```"
        return out.replace(manyBlankLines, "\n\n").trim()
    }

    /** Removes `{"omni_tool_call": ...}` objects by brace matching (strings and escapes respected). */
    private fun stripBareEnvelopes(text: String): String {
        val key = Regex("\\{\\s*\"omni_tool_call\"")
        var out = text
        while (true) {
            val start = key.find(out)?.range?.first ?: return out
            var depth = 0
            var inString = false
            var escaped = false
            var end = -1
            for (i in start until out.length) {
                val c = out[i]
                if (inString) {
                    if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
                } else when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) { end = i; break } }
                }
            }
            out = if (end < 0) out.substring(0, start) else out.removeRange(start, end + 1)
        }
    }
}
