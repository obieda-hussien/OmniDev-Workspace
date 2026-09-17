package com.omnidev.workspace.data.tools

import org.jsoup.Jsoup
import kotlin.math.max

internal object DeepResearchPageReader {
    private const val PAGE_TIMEOUT_MS = 14_000
    private const val MAX_PAGE_CHARS = 8_000

    fun read(url: String, query: String): String {
        if (!KeylessSearchHttp.isSafePublicUrl(url)) return ""
        val doc = Jsoup.connect(url)
            .userAgent(KeylessSearchHttp.userAgent())
            .timeout(PAGE_TIMEOUT_MS)
            .followRedirects(true)
            .maxBodySize(1_750_000)
            .get()

        doc.select(
            "script,style,nav,footer,header,aside,iframe,noscript,svg,form,button,input,select,textarea," +
                ".ad,.ads,.advertisement,.sidebar,.menu,.cookie,.popup,.newsletter,.social-share," +
                "[aria-hidden=true],[role=navigation]"
        ).remove()

        val content = doc.selectFirst("article")
            ?: doc.selectFirst("main")
            ?: doc.selectFirst("[role=main]")
            ?: doc.selectFirst(
                ".post-content,.entry-content,.article-content,.article-body,.story-content,.post-body," +
                    "#article-body,.main-content,.content,#content,#main"
            )
            ?: doc.body()
            ?: return ""

        val text = content.wholeText().lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
        if (text.isBlank()) return ""

        val terms = SearchSemantics.tokenize(query).toSet()
        val passages = text.split(Regex("\\n{2,}|(?<=[.!?؟])\\s+"))
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length in 80..900 }
            .map { passage ->
                val passageTerms = SearchSemantics.tokenize(passage).toSet()
                val matched = terms.intersect(passageTerms).size
                val coverage = matched.toDouble() / max(1, terms.size)
                val density = matched.toDouble() / max(1, passageTerms.size)
                passage to (coverage * 0.8 + density * 0.2)
            }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .fold(mutableListOf<Pair<String, Double>>()) { selected, candidate ->
                val candidateTerms = SearchSemantics.tokenize(candidate.first).toSet()
                val nearDuplicate = selected.any { existing ->
                    val existingTerms = SearchSemantics.tokenize(existing.first).toSet()
                    val union = candidateTerms.union(existingTerms).size
                    union > 0 && candidateTerms.intersect(existingTerms).size.toDouble() / union > 0.72
                }
                if (!nearDuplicate && selected.size < 4) selected += candidate
                selected
            }
            .map { it.first }

        return buildString {
            if (passages.isNotEmpty()) {
                appendLine("**Query-focused evidence:**")
                passages.forEachIndexed { index, passage -> appendLine("${index + 1}. $passage") }
                appendLine()
            }
            appendLine("**Readable page text (truncated):**")
            append(text.take(MAX_PAGE_CHARS))
        }
    }
}
