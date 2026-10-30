package com.omnidev.workspace.domain.engine

/**
 * Detects a reply that announces work instead of reporting a result, for example
 * "I'll start by checking the folder:" with no tool call. Weaker models often end their turn this
 * way and the run would otherwise publish the promise as the final answer.
 *
 * Conservative on purpose: only short replies whose last paragraph announces an action or ends
 * open ("...:" / "..."), and never when the text carries completion evidence. A false positive costs
 * one extra model turn in which the model can simply restate its finished answer.
 */
object UnfulfilledPromiseDetector {
    private const val MAX_CHARS = 1_800
    private const val TAIL_CHARS = 420

    private val ARABIC_MARKERS = listOf(
        "هبدأ", "هبدا", "هقوم", "سأقوم", "سوف أقوم", "سأبدأ", "سوف أبدأ", "هنفذ", "سأنفذ", "هفحص", "سأفحص",
        "هشغل", "سأشغل", "هجرب", "سأجرب", "هنشئ", "سأنشئ", "هكمل", "سأكمل", "هعمل", "هظبط", "سأضبط", "خليني"
    )
    private val ENGLISH_MARKERS = Regex(
        "\\b(i will|i'll|i am going to|i'm going to|let me|next,? i|now i('ll| will)|first,? i('ll| will)|going to)\\b"
    )
    private val DONE_MARKERS = listOf(
        "✅", "تم ", "تمت", "اكتمل", "النتيجة", "الخلاصة", "الملخص", "إليك", "done", "completed", "finished",
        "verified", "result:", "summary", "here is", "here's"
    )

    fun isUnfulfilled(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_CHARS) return false
        val tail = trimmed.takeLast(TAIL_CHARS).lowercase()
        val lastParagraph = tail.split(Regex("\\n\\s*\\n")).last().trim()
        if (lastParagraph.isEmpty()) return false

        val endsOpen = lastParagraph.endsWith(":") || lastParagraph.endsWith("...") || lastParagraph.endsWith("…") ||
            lastParagraph.endsWith("：")
        val announces = ARABIC_MARKERS.any { lastParagraph.contains(it) } || ENGLISH_MARKERS.containsMatchIn(lastParagraph)
        if (!announces && !endsOpen) return false

        val hasEvidence = DONE_MARKERS.any { tail.contains(it) }
        return endsOpen || !hasEvidence
    }
}
