package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.db.dao.KnowledgeDao
import com.omnidev.workspace.data.db.entities.KnowledgeSnippet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Legacy vector-memory compatibility facade.
 *
 * There is no second vector-memory datastore anymore: these tools read and write the same
 * [KnowledgeSnippet] corpus used by [MemoryManager] and rank it with [HybridMemorySearchEngine].
 * Keeping the old tool names avoids breaking older prompts, skills and automation graphs while
 * Omni Memory becomes the single user-facing memory system.
 */
class VectorMemoryManager(private val knowledgeDao: KnowledgeDao) {

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "vector_store",
            description = "Legacy compatibility alias for storing an item in Omni Memory. " +
                "The item is saved to the same canonical store used by remember_fact and is searchable by every memory tool.",
            parameters = listOf(
                ToolParameter("content", "string", "The fact, preference, rule, or knowledge to store.", required = true),
                ToolParameter(
                    "category", "string",
                    "Category such as user_preference, project_rule, architecture, api_knowledge, or general.",
                    required = false
                ),
                ToolParameter("tags", "string", "Comma-separated keywords for retrieval boosting.", required = false)
            )
        ),
        ToolDefinition(
            name = "vector_search",
            description = "Legacy compatibility search over Omni Memory. Uses the same multilingual hybrid ranking " +
                "engine as search_knowledge instead of a separate TF-IDF memory silo.",
            parameters = listOf(
                ToolParameter("query", "string", "Natural-language memory query.", required = true),
                ToolParameter("top_k", "string", "Number of results (default 5, max 20).", required = false)
            )
        ),
        ToolDefinition(
            name = "vector_similar",
            description = "Find Omni Memory entries similar to an existing memory ID using the shared hybrid retrieval engine.",
            parameters = listOf(
                ToolParameter("id", "string", "Numeric source memory ID.", required = true),
                ToolParameter("top_k", "string", "Number of similar results (default 5, max 20).", required = false)
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
            else -> ToolExecutionResult("Unknown legacy memory tool: $name", isError = true)
        }
    }

    private suspend fun vectorStore(args: Map<String, String>): ToolExecutionResult {
        val content = args["content"]?.trim()
            ?: return ToolExecutionResult("Missing required argument: content", isError = true)
        if (content.isBlank()) return ToolExecutionResult("Memory content cannot be blank.", isError = true)

        val category = args["category"]?.takeIf { it.isNotBlank() }?.lowercase()?.trim() ?: "general"
        val tags = args["tags"]?.lowercase()?.trim() ?: ""
        val id = knowledgeDao.insert(KnowledgeSnippet(category = category, content = content, tags = tags))

        return ToolExecutionResult(
            "✅ Stored in Omni Memory (id=$id). vector_store is a compatibility alias; no separate vector copy was created."
        )
    }

    private suspend fun vectorSearch(args: Map<String, String>): ToolExecutionResult {
        val query = args["query"]?.trim()
            ?: return ToolExecutionResult("Missing required argument: query", isError = true)
        if (query.isBlank()) return ToolExecutionResult("Search query cannot be blank.", isError = true)

        val topK = args["top_k"]?.toIntOrNull()?.coerceIn(1, 20) ?: 5
        val corpus = knowledgeDao.getAll()
        if (corpus.isEmpty()) return ToolExecutionResult("Omni Memory is empty.")

        val matches = HybridMemorySearchEngine.rank(query, corpus, topK)
        if (matches.isEmpty()) {
            return ToolExecutionResult("No relevant Omni Memory entries found for: \"$query\"")
        }

        val formatted = matches.joinToString("\n\n") { match ->
            val snippet = match.snippet
            val pct = "%.1f".format(match.score * 100.0)
            "[${snippet.id}] ($pct% • ${match.reason}) [${snippet.category}] ${snippet.content}" +
                if (snippet.tags.isNotBlank()) " [tags: ${snippet.tags}]" else ""
        }
        return ToolExecutionResult("Omni Memory hybrid results (${matches.size}):\n\n$formatted")
    }

    private suspend fun vectorSimilar(args: Map<String, String>): ToolExecutionResult {
        val id = args["id"]?.toLongOrNull()
            ?: return ToolExecutionResult("Missing or invalid argument: id", isError = true)
        val topK = args["top_k"]?.toIntOrNull()?.coerceIn(1, 20) ?: 5

        val source = knowledgeDao.findById(id)
            ?: return ToolExecutionResult("No memory entry found with id=$id", isError = true)
        val corpus = knowledgeDao.getAll().filter { it.id != id }
        if (corpus.isEmpty()) return ToolExecutionResult("No other Omni Memory entries to compare against.")

        val query = buildString {
            append(source.content)
            if (source.tags.isNotBlank()) append(" ${source.tags}")
            if (source.category.isNotBlank()) append(" ${source.category}")
        }
        val matches = HybridMemorySearchEngine.rank(query, corpus, topK)
        if (matches.isEmpty()) return ToolExecutionResult("No similar memories found for entry id=$id")

        val formatted = matches.joinToString("\n\n") { match ->
            val snippet = match.snippet
            val pct = "%.1f".format(match.score * 100.0)
            "[${snippet.id}] ($pct% similar • ${match.reason}) [${snippet.category}] ${snippet.content}"
        }
        return ToolExecutionResult("Top ${matches.size} Omni Memory entries similar to id=$id:\n\n$formatted")
    }
}
