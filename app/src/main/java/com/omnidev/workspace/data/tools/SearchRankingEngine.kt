package com.omnidev.workspace.data.tools

import java.net.URI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

internal object SearchRankingEngine {
    private const val RRF_K = 60.0
    private const val K1 = 1.5
    private const val B = 0.75

    fun rank(query: String, batches: List<WebSearchProviderBatch>, maxResults: Int, mode: WebSearchMode, now: Long = System.currentTimeMillis()): List<WebSearchHit> {
        data class Agg(var hit: WebSearchHit, var rrf: Double = 0.0, val providers: MutableSet<String> = linkedSetOf())
        val merged = linkedMapOf<String, Agg>()
        batches.filter { it.hits.isNotEmpty() }.forEach { batch ->
            batch.hits.forEachIndexed { index, raw ->
                val hit = if (raw.publishedAtEpochMs != null) raw else raw.copy(
                    publishedAtEpochMs = SearchSemantics.parseDate(listOfNotNull(raw.publishedLabel, raw.title, raw.snippet, raw.url).joinToString(" "), now)
                )
                val key = SearchSemantics.dedupKey(hit)
                val agg = merged.getOrPut(key) { Agg(hit) }
                agg.rrf += providerWeight(batch.provider) / (RRF_K + index + 1.0)
                agg.providers += batch.provider
                if (hit.snippet.length > agg.hit.snippet.length || (hit.publishedAtEpochMs != null && agg.hit.publishedAtEpochMs == null)) {
                    agg.hit = hit.copy(isResearch = hit.isResearch || agg.hit.isResearch)
                }
            }
        }
        if (merged.isEmpty()) return emptyList()

        val entries = merged.values.toList()
        val hits = entries.map { it.hit }
        val bm25 = bm25(query, hits)
        val maxBm25 = bm25.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val maxRrf = entries.maxOf { it.rrf }.takeIf { it > 0 } ?: 1.0
        val queryTerms = SearchSemantics.tokenize(query).toSet()
        val researchIntent = SearchSemantics.isResearchIntent(query)
        val freshIntent = SearchSemantics.isFreshnessIntent(query)

        data class Scored(val hit: WebSearchHit, val score: Double, val relevance: Double, val bucket: Int)
        val scored = entries.mapIndexed { i, agg ->
            val relevance = ((bm25[i] / maxBm25) * 0.72 + coverage(queryTerms, agg.hit) * 0.28).coerceIn(0.0, 1.0)
            val freshness = freshness(agg.hit.publishedAtEpochMs, now)
            val rrf = (agg.rrf / maxRrf).coerceIn(0.0, 1.0)
            val authority = when { agg.hit.isResearch -> 1.0; agg.hit.source.contains("Wikipedia", true) -> 0.88; else -> 0.74 }
            val score = if (mode == WebSearchMode.DEEP) {
                relevance * 0.43 + rrf * 0.18 + authority * 0.14 + freshness * 0.25
            } else {
                relevance * (if (freshIntent || researchIntent) 0.48 else 0.58) + rrf * 0.20 + authority * 0.10 + freshness * (if (freshIntent || researchIntent) 0.18 else 0.06)
            }
            val bucket = when {
                researchIntent && agg.hit.isResearch && agg.hit.publishedAtEpochMs != null && relevance >= 0.10 -> 0
                researchIntent && agg.hit.isResearch && relevance >= 0.10 -> 1
                else -> 2
            }
            Scored(agg.hit, score, relevance, bucket)
        }.filter { it.hit.title.isNotBlank() && it.hit.url.startsWith("http") && it.relevance > 0.0 }

        val sorted = scored.sortedWith(compareBy<Scored> { it.bucket }.thenComparator { a, b ->
            if (a.bucket == 0 && b.bucket == 0) {
                val dateOrder = (b.hit.publishedAtEpochMs ?: Long.MIN_VALUE).compareTo(a.hit.publishedAtEpochMs ?: Long.MIN_VALUE)
                if (dateOrder != 0) return@thenComparator dateOrder
            }
            b.score.compareTo(a.score)
        }).map { it.hit }

        return diversify(removeNearDuplicateTitles(sorted), maxResults)
    }

    private fun bm25(query: String, hits: List<WebSearchHit>): List<Double> {
        val terms = SearchSemantics.tokenize(query).distinct()
        if (terms.isEmpty()) return List(hits.size) { 0.0 }
        val docs = hits.map { SearchSemantics.tokenize("${it.title} ${it.title} ${it.snippet}") }
        val avg = docs.map { it.size.toDouble() }.average().takeIf { it > 0 } ?: 1.0
        val n = docs.size.toDouble()
        val idf = terms.associateWith { term ->
            val df = docs.count { term in it }.toDouble()
            ln((n - df + 0.5) / (df + 0.5) + 1.0)
        }
        return docs.map { doc ->
            val tf = doc.groupingBy { it }.eachCount()
            val len = max(1, doc.size).toDouble()
            terms.sumOf { term ->
                val f = tf.getOrDefault(term, 0).toDouble()
                if (f == 0.0) 0.0 else idf.getValue(term) * (f * (K1 + 1)) / (f + K1 * (1 - B + B * (len / avg)))
            }
        }
    }

    private fun coverage(query: Set<String>, hit: WebSearchHit): Double = if (query.isEmpty()) 0.0 else {
        query.intersect(SearchSemantics.tokenize("${hit.title} ${hit.snippet}").toSet()).size.toDouble() / query.size
    }

    private fun freshness(epoch: Long?, now: Long): Double = if (epoch == null) 0.16 else {
        exp(-((now - epoch).coerceAtLeast(0) / 86_400_000.0) / 240.0).coerceIn(0.08, 1.0)
    }

    private fun providerWeight(name: String): Double = when {
        name.contains("arxiv", true) || name.contains("pubmed", true) -> 1.16
        name.contains("wikipedia", true) -> 1.05
        else -> 1.0
    }

    private fun removeNearDuplicateTitles(hits: List<WebSearchHit>): List<WebSearchHit> {
        val out = mutableListOf<WebSearchHit>(); val seen = mutableListOf<Set<String>>()
        hits.forEach { hit ->
            val tokens = SearchSemantics.tokenize(hit.title).toSet()
            val duplicate = tokens.size >= 3 && seen.any { old -> old.size >= 3 && tokens.intersect(old).size.toDouble() / tokens.union(old).size >= 0.88 }
            if (!duplicate) { out += hit; seen += tokens }
        }
        return out
    }

    private fun diversify(hits: List<WebSearchHit>, maxResults: Int): List<WebSearchHit> {
        val out = mutableListOf<WebSearchHit>(); val counts = mutableMapOf<String, Int>(); val deferred = mutableListOf<WebSearchHit>()
        hits.forEach { hit ->
            val host = runCatching { URI(hit.url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
            val limit = if (hit.isResearch) 4 else 2
            if (counts.getOrDefault(host, 0) < limit && out.size < maxResults) { out += hit; counts[host] = counts.getOrDefault(host, 0) + 1 } else deferred += hit
        }
        deferred.forEach { if (out.size < maxResults) out += it }
        return out.take(maxResults)
    }
}
