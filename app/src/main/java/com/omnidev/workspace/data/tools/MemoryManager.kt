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
                "Use this proactively to persist important user preferences, project architecture decisions, " +
                "coding style rules, or frequently referenced information across sessions. " +
                "You MUST call this autonomously whenever the user states a preference, you learn something " +
                "important about the project, or you want to remember a decision for future sessions.",
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
                "Returns the most relevant entries matching the query. " +
                "You MUST call this at the START of any new task to retrieve relevant context before acting.",
            parameters = listOf(
                ToolParameter(
                    "query", "string",
                    "Natural-language or keyword query to search in stored knowledge.",
                    required = true
                )
            )
        ),
        ToolDefinition(
            name = "update_memory",
            description = "Update the content of an existing memory entry by its ID. " +
                "Use this when a previously stored fact becomes outdated or needs correction. " +
                "First use search_knowledge to find the ID of the entry, then call this to update it.",
            parameters = listOf(
                ToolParameter("id", "string", "The numeric ID of the memory entry to update.", required = true),
                ToolParameter("content", "string", "The new content to replace the old fact.", required = true),
                ToolParameter(
                    "category", "string",
                    "Updated category (optional, keep existing if omitted).",
                    required = false
                ),
                ToolParameter(
                    "tags", "string",
                    "Updated comma-separated tags (optional, keep existing if omitted).",
                    required = false
                )
            )
        ),
        ToolDefinition(
            name = "delete_memory",
            description = "Permanently delete a memory entry by its ID. " +
                "Use this to remove outdated, incorrect, or irrelevant facts from long-term memory. " +
                "First use search_knowledge to find the ID of the entry to delete.",
            parameters = listOf(
                ToolParameter("id", "string", "The numeric ID of the memory entry to delete.", required = true)
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
            "update_memory" -> updateMemory(arguments)
            "delete_memory" -> deleteMemory(arguments)
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

    private suspend fun updateMemory(args: Map<String, String>): ToolExecutionResult {
        val id = args["id"]?.toLongOrNull()
            ?: return ToolExecutionResult("Missing or invalid argument: id (must be a number)", isError = true)
        val newContent = args["content"]
            ?: return ToolExecutionResult("Missing required argument: content", isError = true)

        // Load the existing snippet so we can preserve unchanged fields
        val existing = knowledgeDao.findById(id)
            ?: return ToolExecutionResult("No memory entry found with id=$id", isError = true)

        val updated = existing.copy(
            content = newContent,
            category = args["category"]?.takeIf { it.isNotBlank() } ?: existing.category,
            tags = args["tags"] ?: existing.tags
        )
        knowledgeDao.update(updated)
        return ToolExecutionResult("Memory entry id=$id updated successfully.")
    }

    private suspend fun deleteMemory(args: Map<String, String>): ToolExecutionResult {
        val id = args["id"]?.toLongOrNull()
            ?: return ToolExecutionResult("Missing or invalid argument: id (must be a number)", isError = true)
        knowledgeDao.deleteById(id)
        return ToolExecutionResult("Memory entry id=$id deleted successfully.")
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
        val generalFacts = knowledgeDao.findByCategory("general")

        val all = projectRules + userPrefs + archNotes + generalFacts
        if (all.isEmpty()) return@withContext null

        buildString {
            appendLine("\n--- LONG-TERM MEMORY (Auto-Injected) ---")
            if (projectRules.isNotEmpty()) {
                appendLine("\nProject Rules:")
                projectRules.forEach { appendLine("• [${it.id}] ${it.content}") }
            }
            if (userPrefs.isNotEmpty()) {
                appendLine("\nUser Preferences:")
                userPrefs.forEach { appendLine("• [${it.id}] ${it.content}") }
            }
            if (archNotes.isNotEmpty()) {
                appendLine("\nArchitecture Notes:")
                archNotes.forEach { appendLine("• [${it.id}] ${it.content}") }
            }
            if (generalFacts.isNotEmpty()) {
                appendLine("\nGeneral Knowledge:")
                generalFacts.forEach { appendLine("• [${it.id}] ${it.content}") }
            }
            appendLine("--- END MEMORY ---")
        }
    }
}
