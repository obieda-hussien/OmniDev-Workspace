package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.db.dao.KnowledgeDao
import com.omnidev.workspace.data.db.entities.KnowledgeSnippet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Provides long-term memory tools for the AI agent.
 *
 * The agent can call:
 * - `remember_fact(content, category, tags)` — stores a new fact/rule in Room.
 * - `search_knowledge(query)` — retrieves the most relevant stored facts.
 *
 * Before each new conversation, [hydrateSystemPrompt] injects project-level knowledge
 * into the agent's system prompt automatically (context hydration).
 */
class MemoryManager(private val knowledgeDao: KnowledgeDao) {

    // ──────────────────────────────────────────────
    //  Tool Definitions (exposed to AgentPipeline)
    // ──────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "remember_fact",
            description = "Store a new fact, preference, or rule in long-term memory. " +
                "Use this to persist important user preferences, project architecture decisions, " +
                "coding style rules, or frequently referenced information across sessions.",
            parameters = listOf(
                ToolParameter("content", "string", "The fact or rule to remember.", required = true),
                ToolParameter(
                    "category", "string",
                    "Broad category: 'user_preference', 'project_rule', 'architecture', 'api_key_hint', or 'general'.",
                    required = false
                ),
                ToolParameter(
                    "tags", "string",
                    "Comma-separated keywords for easier retrieval (e.g., 'kotlin,coroutines,flow').",
                    required = false
                )
            )
        ),
        ToolDefinition(
            name = "search_knowledge",
            description = "Search your long-term memory for stored facts, preferences, and rules. " +
                "Returns the most relevant entries matching the query.",
            parameters = listOf(
                ToolParameter(
                    "query", "string",
                    "Natural-language or keyword query to search in stored knowledge.",
                    required = true
                )
            )
        )
    )

    // ──────────────────────────────────────────────
    //  Tool Execution
    // ──────────────────────────────────────────────

    suspend fun executeTool(
        name: String,
        arguments: Map<String, String>
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        when (name) {
            "remember_fact" -> rememberFact(arguments)
            "search_knowledge" -> searchKnowledge(arguments)
            else -> ToolExecutionResult("Unknown memory tool: $name", isError = true)
        }
    }

    private suspend fun rememberFact(args: Map<String, String>): ToolExecutionResult {
        val content = args["content"]
            ?: return ToolExecutionResult("Missing required argument: content", isError = true)
        val category = args["category"]?.takeIf { it.isNotBlank() } ?: "general"
        val tags = args["tags"] ?: ""
        val id = knowledgeDao.insert(KnowledgeSnippet(category = category, content = content, tags = tags))
        return ToolExecutionResult("Fact stored successfully (id=$id). I will remember this across future sessions.")
    }

    private suspend fun searchKnowledge(args: Map<String, String>): ToolExecutionResult {
        val query = args["query"]
            ?: return ToolExecutionResult("Missing required argument: query", isError = true)
        val results = knowledgeDao.search(query)
        if (results.isEmpty()) {
            return ToolExecutionResult("No matching knowledge found for: \"$query\"")
        }
        val formatted = results.joinToString("\n\n") { snippet ->
            "[${snippet.id}] (${snippet.category}) ${snippet.content}" +
                if (snippet.tags.isNotBlank()) " [tags: ${snippet.tags}]" else ""
        }
        return ToolExecutionResult("Found ${results.size} result(s):\n\n$formatted")
    }

    // ──────────────────────────────────────────────
    //  Context Hydration
    // ──────────────────────────────────────────────

    /**
     * Builds a knowledge injection block from all stored project rules and user preferences.
     * This is prepended to the agent's system prompt at the start of each new session.
     *
     * Returns null if no relevant knowledge exists (avoids polluting empty-state prompts).
     */
    suspend fun buildKnowledgeContext(): String? = withContext(Dispatchers.IO) {
        val projectRules = knowledgeDao.findByCategory("project_rule")
        val userPrefs = knowledgeDao.findByCategory("user_preference")
        val archNotes = knowledgeDao.findByCategory("architecture")

        val all = projectRules + userPrefs + archNotes
        if (all.isEmpty()) return@withContext null

        buildString {
            appendLine("\n--- LONG-TERM MEMORY (Auto-Injected) ---")
            if (projectRules.isNotEmpty()) {
                appendLine("\nProject Rules:")
                projectRules.forEach { appendLine("• ${it.content}") }
            }
            if (userPrefs.isNotEmpty()) {
                appendLine("\nUser Preferences:")
                userPrefs.forEach { appendLine("• ${it.content}") }
            }
            if (archNotes.isNotEmpty()) {
                appendLine("\nArchitecture Notes:")
                archNotes.forEach { appendLine("• ${it.content}") }
            }
            appendLine("--- END MEMORY ---")
        }
    }
}
