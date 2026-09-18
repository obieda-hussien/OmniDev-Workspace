package com.omnidev.workspace.domain.engine

/**
 * Zero-token evidence-first compressor for worker-to-worker and worker-to-synthesis handoffs.
 * Full worker output may remain in runtime memory/UI, but only high-value evidence crosses agent
 * boundaries. This avoids spending context on repeated narration.
 */
object TeamHandoffCompressor {

    fun compact(raw: String, maxChars: Int): String {
        if (maxChars <= 0 || raw.isBlank()) return ""
        val redacted = redact(raw)

        data class Line(val index: Int, val text: String, val score: Int)
        val seenEvidence = linkedSetOf<String>()
        val candidates = redacted.lineSequence()
            .mapIndexedNotNull { index, line ->
                val clean = line.trim().replace(WHITESPACE, " ")
                if (clean.isBlank()) return@mapIndexedNotNull null

                val lineScore = score(clean)
                // Deduplicate evidence on every handoff, even when the raw payload already fits
                // inside maxChars. Comparison is normalized, while the first original line is kept.
                if (lineScore > 0) {
                    val fingerprint = normalize(clean)
                    if (!seenEvidence.add(fingerprint)) return@mapIndexedNotNull null
                }

                Line(index, clean.take(MAX_SINGLE_LINE), lineScore)
            }
            .toList()

        val deduplicated = candidates.joinToString("\n") { it.text }
        if (deduplicated.length <= maxChars) return deduplicated

        val selected = linkedSetOf<Int>()
        // Always preserve a tiny framing slice.
        candidates.take(2).forEach { selected += it.index }
        candidates.takeLast(2).forEach { selected += it.index }

        candidates.sortedWith(
            compareByDescending<Line> { it.score }.thenBy { it.index }
        ).forEach { line ->
            if (line.score > 0) selected += line.index
        }

        val indexed = candidates.associateBy { it.index }
        val out = StringBuilder()
        for (index in selected.sorted()) {
            val text = indexed[index]?.text ?: continue
            if (out.length + text.length + 1 > maxChars) continue
            if (out.isNotEmpty()) out.append('\n')
            out.append(text)
        }

        // If evidence lines were sparse, use the remaining room for compact chronological context.
        if (out.length < maxChars * 0.55f) {
            for (line in candidates) {
                if (line.index in selected) continue
                if (out.length + line.text.length + 1 > maxChars) break
                if (out.isNotEmpty()) out.append('\n')
                out.append(line.text)
            }
        }

        return out.toString().take(maxChars)
    }

    private fun score(line: String): Int {
        val lower = line.lowercase()
        var score = 0
        if (ERROR_HINTS.any(lower::contains)) score += 10
        if (VERIFY_HINTS.any(lower::contains)) score += 8
        if (CHANGE_HINTS.any(lower::contains)) score += 6
        if (DECISION_HINTS.any(lower::contains)) score += 5
        if (PATH.find(lower) != null) score += 5
        if (SOURCE_HINTS.any(lower::contains)) score += 4
        if (COMMAND_HINTS.any(lower::contains)) score += 4
        if (line.startsWith("#") || line.endsWith(":")) score += 2
        if (line.length > 1_200) score -= 2
        return score
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(PATH, "/path")
        .replace(HEX, "hex")
        .replace(NUMBER, "n")
        .replace(WHITESPACE, " ")
        .trim()
        .take(400)

    private fun redact(value: String): String = value
        .replace(
            Regex("(?i)(api_?key|token|secret|password|otp|bearer)[=:\\s]+[^\\s,;]+"),
            "$1=[REDACTED]"
        )
        .replace(
            Regex("(?i)\"(api_?key|token|secret|password|otp)\"\\s*:\\s*\"[^\"]+\""),
            "\"$1\":\"[REDACTED]\""
        )

    private const val MAX_SINGLE_LINE = 1_400
    private val WHITESPACE = Regex("\\s+")
    private val NUMBER = Regex("\\b\\d+\\b")
    private val HEX = Regex("\\b[0-9a-f]{8,}\\b", RegexOption.IGNORE_CASE)
    private val PATH = Regex("(?:/[A-Za-z0-9_.@+\\-]+){2,}|(?:[A-Za-z0-9_.@+\\-]+/){1,}[A-Za-z0-9_.@+\\-]+")

    private val ERROR_HINTS = listOf(
        "error", "failed", "failure", "exception", "blocked", "timeout", "denied", "cannot",
        "unresolved", "warning", "crash", "خطأ", "فشل"
    )
    private val VERIFY_HINTS = listOf(
        "verified", "verification", "test passed", "tests passed", "build successful", "build passed",
        "exit_code: 0", "assert", "lint", "confirmed", "تحقق", "اختبار"
    )
    private val CHANGE_HINTS = listOf(
        "changed", "modified", "created", "deleted", "patched", "implemented", "migration",
        "diff", "commit", "file:", "files:", "تعديل", "تم إنشاء"
    )
    private val DECISION_HINTS = listOf(
        "decision", "because", "root cause", "cause:", "constraint", "assumption", "risk",
        "recommend", "therefore", "سبب", "قرار", "مخاطرة"
    )
    private val SOURCE_HINTS = listOf("http://", "https://", "source:", "citation", "docs", "documentation")
    private val COMMAND_HINTS = listOf("exit_code", "stderr", "stdout", "command:", "gradle", "adb ", "rish ", "git ")
}
