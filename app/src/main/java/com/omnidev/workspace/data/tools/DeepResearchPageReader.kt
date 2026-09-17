package com.omnidev.workspace.data.tools

import kotlin.math.ln
import kotlin.math.max

/** Query-focused reader used after search discovery by web_search_deep. */
internal object DeepResearchPageReader {
    private const val MAX_PAGE_TEXT = 9_000
    private const val MAX_PASSAGES = 5

    suspend fun read(url: String, query: String): String {
        val response = PageFetchEngine.fetch(url)
        val page = ReadablePageExtractor.extract(response)
        if (page.text.length < 120) return ""

        val passages = selectPassages(query, page.text)
        return buildString {
            appendLine("**Source metadata:**")
            if (page.metadata.title.isNotBlank()) appendLine("- Title: ${page.metadata.title}")
            appendLine("- Final URL: ${response.finalUrl}")
            if (page.metadata.author.isNotBlank()) appendLine("- Author: ${page.metadata.author}")
            if (page.metadata.publishedAt.isNotBlank()) appendLine("- Published: ${page.metadata.publishedAt}")
            if (page.metadata.modifiedAt.isNotBlank()) appendLine("- Modified: ${page.metadata.modifiedAt}")
            appendLine("- Readable words: ${page.wordCount}; extraction quality=${"%.2f".format(page.qualityScore)}")
            if (page.warnings.isNotEmpty()) appendLine("- Warnings: ${page.warnings.joinToString(" | ")}")
            appendLine()
            if (passages.isNotEmpty()) {
                appendLine("**Query-focused evidence:**")
                passages.forEachIndexed { index, passage -> appendLine("${index + 1}. $passage") }
                appendLine()
            }
            appendLine("--- BEGIN UNTRUSTED PAGE CONTENT ---")
            appendLine(page.text.take(MAX_PAGE_TEXT))
            appendLine("--- END UNTRUSTED PAGE CONTENT ---")
        }.trim()
    }

    private fun selectPassages(query: String, text: String): List<String> {
        val queryTerms = SearchSemantics.tokenize(query).toSet()
        if (queryTerms.isEmpty()) return emptyList()

        val chunks = text
            .split(Regex("\\n{2,}|(?<=[.!?؟])\\s+"))
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length in 90..1100 }
        if (chunks.isEmpty()) return emptyList()

        val docFreq = queryTerms.associateWith { term -> chunks.count { term in SearchSemantics.tokenize(it).toSet() } }
        val n = chunks.size.toDouble()

        data class Candidate(val text: String, val terms: Set<String>, val score: Double)
        val candidates = chunks.mapIndexed { index, chunk ->
            val tokens = SearchSemantics.tokenize(chunk)
            val tokenSet = tokens.toSet()
            val matched = queryTerms.intersect(tokenSet)
            val coverage = matched.size.toDouble() / max(1, queryTerms.size)
            val idfSignal = matched.sumOf { term -> ln((n + 1.0) / (docFreq.getValue(term) + 1.0)) + 1.0 }
            val density = matched.size.toDouble() / max(1, tokenSet.size)
            val earlyBonus = 1.0 / (1.0 + index / 12.0)
            Candidate(chunk, tokenSet, coverage * 2.2 + idfSignal * 0.35 + density * 0.5 + earlyBonus * 0.12)
        }.filter { it.score > 0.1 }.sortedByDescending { it.score }

        val selected = mutableListOf<Candidate>()
        for (candidate in candidates) {
            if (selected.size >= MAX_PASSAGES) break
            val tooSimilar = selected.any { existing ->
                val union = candidate.terms.union(existing.terms).size
                union > 0 && candidate.terms.intersect(existing.terms).size.toDouble() / union > 0.68
            }
            if (!tooSimilar) selected += candidate
        }
        return selected.map { it.text }
    }
}
