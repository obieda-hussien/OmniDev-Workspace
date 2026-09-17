package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.brain.HashEmbedder
import com.omnidev.workspace.data.db.entities.KnowledgeSnippet

/**
 * Shared retrieval engine for Omni Memory.
 *
 * Combines:
 *  - multilingual local semantic similarity via [HashEmbedder]
 *  - exact phrase matching
 *  - token overlap
 *  - tag/category boosts
 *
 * Both the classic knowledge tools and the legacy vector tools use this same engine and the same
 * [KnowledgeSnippet] corpus, so there is one source of truth and one ranking policy.
 */
object HybridMemorySearchEngine {

    data class Match(
        val snippet: KnowledgeSnippet,
        val score: Double,
        val semanticScore: Double,
        val lexicalScore: Double,
        val reason: String
    )

    fun rank(
        query: String,
        corpus: List<KnowledgeSnippet>,
        topK: Int
    ): List<Match> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank() || corpus.isEmpty() || topK <= 0) return emptyList()

        val normalizedQuery = HashEmbedder.normalize(cleanQuery).trim().replace(WHITESPACE, " ")
        val queryTokens = tokenize(normalizedQuery)
        if (normalizedQuery.isBlank() || queryTokens.isEmpty()) return emptyList()

        val queryEmbedding = HashEmbedder.embed(cleanQuery)

        return corpus.asSequence()
            .mapNotNull { snippet -> score(snippet, normalizedQuery, queryTokens, queryEmbedding) }
            .sortedWith(
                compareByDescending<Match> { it.score }
                    .thenByDescending { it.snippet.createdAt }
            )
            .take(topK)
            .toList()
    }

    private fun score(
        snippet: KnowledgeSnippet,
        normalizedQuery: String,
        queryTokens: Set<String>,
        queryEmbedding: FloatArray
    ): Match? {
        val normalizedContent = HashEmbedder.normalize(snippet.content).trim().replace(WHITESPACE, " ")
        val normalizedTags = HashEmbedder.normalize(snippet.tags).trim().replace(WHITESPACE, " ")
        val normalizedCategory = HashEmbedder.normalize(snippet.category).trim().replace(WHITESPACE, " ")
        val document = listOf(normalizedContent, normalizedTags, normalizedCategory)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        if (document.isBlank()) return null

        val documentTokens = tokenize(document)
        val tagTokens = tokenize(normalizedTags)
        val categoryTokens = tokenize(normalizedCategory)

        val exactPhrase = normalizedContent.contains(normalizedQuery) || normalizedTags.contains(normalizedQuery)
        val overlap = coverage(queryTokens, documentTokens)
        val tagCoverage = coverage(queryTokens, tagTokens)
        val categoryCoverage = coverage(queryTokens, categoryTokens)
        val prefixCoverage = prefixCoverage(queryTokens, documentTokens)

        val semantic = HashEmbedder
            .cosine(queryEmbedding, HashEmbedder.embed("${snippet.content} ${snippet.tags} ${snippet.category}"))
            .coerceAtLeast(0f)
            .toDouble()

        val lexical = (
            overlap * 0.58 +
                prefixCoverage * 0.16 +
                tagCoverage * 0.18 +
                categoryCoverage * 0.08
            ).coerceIn(0.0, 1.0)

        var combined = semantic * 0.52 + lexical * 0.34
        if (exactPhrase) combined += 0.14
        if (tagCoverage >= 0.5) combined += 0.04
        combined = combined.coerceIn(0.0, 1.0)

        // Hash embeddings can collide. Require either a meaningful semantic signal or some lexical
        // evidence before a candidate is allowed into the result set.
        if (!exactPhrase && lexical <= 0.0 && semantic < MIN_SEMANTIC_SCORE) return null
        if (combined < MIN_COMBINED_SCORE) return null

        val reason = when {
            exactPhrase -> "exact phrase"
            tagCoverage >= 0.5 -> "tags + semantic"
            overlap >= 0.5 -> "keyword + semantic"
            semantic >= 0.45 -> "strong semantic"
            else -> "semantic"
        }

        return Match(
            snippet = snippet,
            score = combined,
            semanticScore = semantic,
            lexicalScore = lexical,
            reason = reason
        )
    }

    private fun tokenize(normalized: String): Set<String> =
        normalized
            .split(WHITESPACE)
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .take(MAX_QUERY_TOKENS)
            .toSet()

    private fun coverage(query: Set<String>, candidate: Set<String>): Double {
        if (query.isEmpty() || candidate.isEmpty()) return 0.0
        return query.count { it in candidate }.toDouble() / query.size.toDouble()
    }

    private fun prefixCoverage(query: Set<String>, candidate: Set<String>): Double {
        if (query.isEmpty() || candidate.isEmpty()) return 0.0
        val hits = query.count { queryToken ->
            candidate.any { candidateToken ->
                candidateToken.startsWith(queryToken) || queryToken.startsWith(candidateToken)
            }
        }
        return hits.toDouble() / query.size.toDouble()
    }

    private val WHITESPACE = Regex("\\s+")
    private const val MAX_QUERY_TOKENS = 64
    private const val MIN_SEMANTIC_SCORE = 0.15
    private const val MIN_COMBINED_SCORE = 0.08

    private val STOP_WORDS = setOf(
        "the", "a", "an", "and", "or", "is", "are", "to", "of", "for", "in", "on",
        "with", "this", "that", "from", "by", "my", "your", "our", "their",
        "في", "من", "علي", "الى", "عن", "هذا", "هذه", "ذلك", "هو", "هي", "انا",
        "انت", "نحن", "مع", "او", "ثم", "كل", "اي", "ما", "لا", "لم", "لن"
    )
}
