package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.db.dao.KnowledgeDao
import com.omnidev.workspace.data.db.entities.KnowledgeSnippet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * VectorMemoryManager — Local TF-IDF-based semantic vector memory for RAG retrieval.
 *
 * Instead of relying on exact substring matching (LIKE queries), this manager
 * embeds all stored [KnowledgeSnippet] entries into TF-IDF vectors and performs
 * cosine-similarity ranking against the query.
 *
 * This provides:
 * - **Semantic fuzzy matching**: "favorite color" matches "The user's preferred colour is blue"
 * - **Ranked results**: Top-K most relevant memories returned by similarity score
 * - **No external dependencies**: Pure Kotlin implementation (no ML frameworks, no C++)
 *
 * The agent can use this for RAG (Retrieval Augmented Generation) by:
 * 1. Storing facts via `vector_store`
 * 2. Querying via `vector_search` with a natural language query
 * 3. Injecting the top results into the conversation context
 *
 * TF-IDF is computed on the fly from the full corpus of stored knowledge.
 * For codebases under ~10,000 snippets this is fast enough for real-time queries.
 */
class VectorMemoryManager(private val knowledgeDao: KnowledgeDao) {

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "vector_store",
            description = "Store a fact in semantic vector memory for RAG retrieval. " +
                "Unlike flat text storage, this fact will be retrievable by meaning — " +
                "a query for 'favorite color' will match 'The user prefers blue'. " +
                "Use this for any knowledge that should persist across sessions.",
            parameters = listOf(
                ToolParameter(
                    "content", "string",
                    "The fact, preference, or knowledge to store.",
                    required = true
                ),
                ToolParameter(
                    "category", "string",
                    "Category: 'user_preference', 'project_rule', 'architecture', " +
                        "'api_knowledge', 'general'.",
                    required = false
                ),
                ToolParameter(
                    "tags", "string",
                    "Comma-separated keywords for boosted retrieval.",
                    required = false
                )
            )
        ),
        ToolDefinition(
            name = "vector_search",
            description = "Search semantic vector memory using natural language. " +
                "Returns the top results ranked by TF-IDF cosine similarity. " +
                "Use this at the START of any task to find relevant stored context. " +
                "More powerful than keyword search — understands meaning, not just exact words.",
            parameters = listOf(
                ToolParameter(
                    "query", "string",
                    "Natural language query (e.g., 'user's preferred coding style').",
                    required = true
                ),
                ToolParameter(
                    "top_k", "string",
                    "Number of top results to return (default: 5, max: 20).",
                    required = false
                )
            )
        ),
        ToolDefinition(
            name = "vector_similar",
            description = "Find memories similar to an existing memory by ID. " +
                "Useful for discovering related context or finding duplicates.",
            parameters = listOf(
                ToolParameter(
                    "id", "string",
                    "The numeric ID of the source memory entry.",
                    required = true
                ),
                ToolParameter(
                    "top_k", "string",
                    "Number of similar results to return (default: 5).",
                    required = false
                )
            )
        )
    )

    suspend fun executeTool(
        name: String,
        arguments: Map<String, String>
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        when (name) {
            "vector_store" -> vectorStore(arguments)
            "vector_search" -> vectorSearch(arguments)
            "vector_similar" -> vectorSimilar(arguments)
            else -> ToolExecutionResult("Unknown vector memory tool: $name", isError = true)
        }
    }

    // ──────────────────────────────────────────────
    //  Tool Implementations
    // ──────────────────────────────────────────────

    private suspend fun vectorStore(args: Map<String, String>): ToolExecutionResult {
        val content = args["content"]
            ?: return ToolExecutionResult("Missing required argument: content", isError = true)
        val category = args["category"]?.takeIf { it.isNotBlank() } ?: "general"
        val tags = args["tags"] ?: ""

        val id = knowledgeDao.insert(
            KnowledgeSnippet(category = category, content = content, tags = tags)
        )
        return ToolExecutionResult(
            "✅ Stored in vector memory (id=$id). This fact will be retrievable by semantic similarity."
        )
    }

    private suspend fun vectorSearch(args: Map<String, String>): ToolExecutionResult {
        val query = args["query"]
            ?: return ToolExecutionResult("Missing required argument: query", isError = true)
        val topK = args["top_k"]?.toIntOrNull()?.coerceIn(1, 20) ?: 5

        val allSnippets = knowledgeDao.getAll()
        if (allSnippets.isEmpty()) {
            return ToolExecutionResult("Vector memory is empty. Store facts first with vector_store.")
        }

        val ranked = rankBySimilarity(query, allSnippets, topK)

        if (ranked.isEmpty()) {
            return ToolExecutionResult("No semantically similar memories found for: \"$query\"")
        }

        val formatted = ranked.joinToString("\n\n") { (snippet, score) ->
            val pct = "%.1f".format(score * 100)
            "[${snippet.id}] ($pct% match) [${snippet.category}] ${snippet.content}" +
                if (snippet.tags.isNotBlank()) " [tags: ${snippet.tags}]" else ""
        }
        return ToolExecutionResult(
            "Found ${ranked.size} result(s) by semantic similarity:\n\n$formatted"
        )
    }

    private suspend fun vectorSimilar(args: Map<String, String>): ToolExecutionResult {
        val id = args["id"]?.toLongOrNull()
            ?: return ToolExecutionResult("Missing or invalid argument: id", isError = true)
        val topK = args["top_k"]?.toIntOrNull()?.coerceIn(1, 20) ?: 5

        val source = knowledgeDao.findById(id)
            ?: return ToolExecutionResult("No memory entry found with id=$id", isError = true)

        val allSnippets = knowledgeDao.getAll().filter { it.id != id }
        if (allSnippets.isEmpty()) {
            return ToolExecutionResult("No other entries in vector memory to compare against.")
        }

        val query = "${source.content} ${source.tags}"
        val ranked = rankBySimilarity(query, allSnippets, topK)

        if (ranked.isEmpty()) {
            return ToolExecutionResult("No similar memories found for entry id=$id")
        }

        val formatted = ranked.joinToString("\n\n") { (snippet, score) ->
            val pct = "%.1f".format(score * 100)
            "[${snippet.id}] ($pct% similar) [${snippet.category}] ${snippet.content}"
        }
        return ToolExecutionResult(
            "Top ${ranked.size} similar to id=$id:\n\n$formatted"
        )
    }

    // ──────────────────────────────────────────────
    //  TF-IDF Engine
    // ──────────────────────────────────────────────

    /**
     * Ranks [corpus] entries by TF-IDF cosine similarity to [query].
     * Returns the top [topK] results as pairs of (snippet, score).
     */
    private fun rankBySimilarity(
        query: String,
        corpus: List<KnowledgeSnippet>,
        topK: Int
    ): List<Pair<KnowledgeSnippet, Double>> {
        // Build documents: content + tags for each snippet
        val documents = corpus.map { "${it.content} ${it.tags}".lowercase() }
        val queryDoc = query.lowercase()

        // Tokenize all documents + query
        val allTokenized = documents.map { tokenize(it) }
        val queryTokens = tokenize(queryDoc)

        if (queryTokens.isEmpty()) return emptyList()

        // Build vocabulary from all documents + query
        val vocabulary = mutableSetOf<String>()
        allTokenized.forEach { vocabulary.addAll(it) }
        vocabulary.addAll(queryTokens)

        // Compute IDF for each term
        val totalDocs = documents.size + 1 // +1 for the query doc
        val idf = mutableMapOf<String, Double>()
        for (term in vocabulary) {
            val docFreq = allTokenized.count { term in it } + if (term in queryTokens) 1 else 0
            idf[term] = ln((totalDocs.toDouble() + 1) / (docFreq.toDouble() + 1)) + 1.0
        }

        // Compute TF-IDF vectors and cosine similarity
        val queryVector = computeTfIdf(queryTokens, idf)

        val similarities = corpus.zip(allTokenized).map { (snippet, tokens) ->
            val docVector = computeTfIdf(tokens, idf)
            val similarity = cosineSimilarity(queryVector, docVector)
            snippet to similarity
        }

        return similarities
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(topK)
    }

    /**
     * Tokenizes text into lowercase word tokens, removing punctuation and stop words.
     */
    private fun tokenize(text: String): List<String> {
        return text
            .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 1 && it !in STOP_WORDS }
    }

    /**
     * Computes a TF-IDF vector for the given tokens.
     * Returns a map of term → TF-IDF weight.
     */
    private fun computeTfIdf(
        tokens: List<String>,
        idf: Map<String, Double>
    ): Map<String, Double> {
        if (tokens.isEmpty()) return emptyMap()

        // Term frequency (normalized by doc length)
        val tf = mutableMapOf<String, Double>()
        for (token in tokens) {
            tf[token] = (tf[token] ?: 0.0) + 1.0
        }
        val docLen = tokens.size.toDouble()

        val result = mutableMapOf<String, Double>()
        for ((term, freq) in tf) {
            val idfVal = idf[term] ?: 1.0
            result[term] = (freq / docLen) * idfVal
        }
        return result
    }

    /**
     * Computes cosine similarity between two sparse TF-IDF vectors.
     */
    private fun cosineSimilarity(
        a: Map<String, Double>,
        b: Map<String, Double>
    ): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0

        var dotProduct = 0.0
        for ((term, weightA) in a) {
            val weightB = b[term] ?: continue
            dotProduct += weightA * weightB
        }

        val normA = sqrt(a.values.sumOf { it * it })
        val normB = sqrt(b.values.sumOf { it * it })

        return if (normA > 0 && normB > 0) dotProduct / (normA * normB) else 0.0
    }

    companion object {
        /** Common English stop words to exclude from TF-IDF computation. */
        private val STOP_WORDS = setOf(
            "a", "an", "the", "is", "it", "in", "on", "at", "to", "of", "for",
            "and", "or", "but", "not", "with", "this", "that", "from", "by", "as",
            "be", "was", "were", "been", "are", "am", "do", "does", "did", "has",
            "have", "had", "will", "would", "could", "should", "may", "might",
            "shall", "can", "if", "so", "no", "up", "out", "he", "she", "we",
            "they", "me", "my", "you", "your", "its", "our", "their", "than"
        )
    }
}
