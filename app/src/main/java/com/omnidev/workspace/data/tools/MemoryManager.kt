package com.omnidev.workspace.data.tools

import com.omnidev.workspace.OmniDevApp
import com.omnidev.workspace.data.db.dao.KnowledgeDao
import com.omnidev.workspace.data.db.entities.KnowledgeSnippet
import com.omnidev.workspace.data.skills.SkillManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Provides long-term memory tools for the AI agent.
 *
 * * HACKER UPGRADES:
 * 1. Token Protection: `buildKnowledgeContext` now strictly limits the number of injected
 * facts to prevent System Prompt token explosion over time.
 * 2. Search Optimization: Bounded search results to prevent context flooding.
 * 3. Prompt Engineering: Forced the LLM to use keywords instead of natural language
 * for SQL-friendly searching.
 * 4. Agent Skills: enabled bundled/user SKILL.md guidance is injected through the same
 * context-hydration seam so Agent and Swarm workers share one durable skill runtime.
 */
class MemoryManager(private val knowledgeDao: KnowledgeDao) {

    companion object {
        // Strict limits to prevent the LLM context window from overflowing
        private const val MAX_INJECTED_RULES = 10
        private const val MAX_INJECTED_PREFS = 10
        private const val MAX_SEARCH_RESULTS = 15
    }

    // ──────────────────────────────────────────────
    //  Tool Definitions (exposed to AgentPipeline)
    // ──────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "remember_fact",
            description = "Store a new fact, preference, or rule in long-term memory. " +
                "Use this proactively to persist important user preferences, project architecture decisions, " +
                "coding style rules, or frequently referenced information across sessions. " +
                "Keep the 'content' concise and factual.",
            parameters = listOf(
                ToolParameter("content", "string", "The fact or rule to remember (Keep it concise).", required = true),
                ToolParameter(
                    "category", "string",
                    "Broad category: 'user_preference', 'project_rule', 'architecture', 'api_key_hint', or 'general'.",
                    required = false
                ),
                ToolParameter(
                    "tags", "string",
                    "Comma-separated keywords for easier retrieval (e.g., 'kotlin,coroutines,ui').",
                    required = false
                )
            )
        ),
        ToolDefinition(
            name = "search_knowledge",
            description = "Search your long-term memory for stored facts, preferences, and rules. " +
                "CRITICAL: The underlying database uses exact keyword matching. You MUST pass 1 or 2 distinct KEYWORDS " +
                "(e.g., 'architecture' or 'api key'), NOT natural language questions. " +
                "Always use this at the START of a task to retrieve relevant context.",
            parameters = listOf(
                ToolParameter(
                    "query", "string",
                    "A single keyword or short phrase to search for (DO NOT use full sentences).",
                    required = true
                )
            )
        ),
        ToolDefinition(
            name = "update_memory",
            description = "Update the content of an existing memory entry by its ID. " +
                "Use this when a previously stored fact becomes outdated or needs correction. " +
                "First use search_knowledge to find the ID.",
            parameters = listOf(
                ToolParameter("id", "string", "The numeric ID of the memory entry to update.", required = true),
                ToolParameter("content", "string", "The new content to replace the old fact.", required = true),
                ToolParameter("category", "string", "Updated category (optional).", required = false),
                ToolParameter("tags", "string", "Updated comma-separated tags (optional).", required = false)
            )
        ),
        ToolDefinition(
            name = "delete_memory",
            description = "Permanently delete a memory entry by its ID. " +
                "Use this to remove outdated or incorrect facts from long-term memory.",
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
        val content = args["content"]?.trim()
            ?: return ToolExecutionResult("Missing required argument: content", isError = true)
        val category = args["category"]?.takeIf { it.isNotBlank() }?.lowercase()?.trim() ?: "general"
        val tags = args["tags"]?.lowercase()?.trim() ?: ""

        val id = knowledgeDao.insert(KnowledgeSnippet(category = category, content = content, tags = tags))
        return ToolExecutionResult("✅ Fact stored successfully in long-term memory (id=$id).")
    }

    private suspend fun searchKnowledge(args: Map<String, String>): ToolExecutionResult {
        val query = args["query"]?.trim()
            ?: return ToolExecutionResult("Missing required argument: query", isError = true)

        // Limit results to prevent flooding the LLM Context window
        val results = knowledgeDao.search(query).take(MAX_SEARCH_RESULTS)

        if (results.isEmpty()) {
            return ToolExecutionResult("No matching knowledge found for keyword: \"$query\". Try a different keyword.")
        }

        val formatted = results.joinToString("\n\n") { snippet ->
            "[ID: ${snippet.id}] (${snippet.category}) ${snippet.content}" +
                if (snippet.tags.isNotBlank()) "\n  Tags: ${snippet.tags}" else ""
        }

        return ToolExecutionResult("🧠 Found ${results.size} memory result(s):\n\n$formatted")
    }

    private suspend fun updateMemory(args: Map<String, String>): ToolExecutionResult {
        val id = args["id"]?.toLongOrNull()
            ?: return ToolExecutionResult("Missing or invalid argument: id (must be a number)", isError = true)
        val newContent = args["content"]?.trim()
            ?: return ToolExecutionResult("Missing required argument: content", isError = true)

        val existing = knowledgeDao.findById(id)
            ?: return ToolExecutionResult("No memory entry found with id=$id", isError = true)

        val updated = existing.copy(
            content = newContent,
            category = args["category"]?.takeIf { it.isNotBlank() }?.lowercase()?.trim() ?: existing.category,
            tags = args["tags"]?.lowercase()?.trim() ?: existing.tags
        )

        knowledgeDao.update(updated)
        return ToolExecutionResult("✅ Memory entry id=$id updated successfully.")
    }

    private suspend fun deleteMemory(args: Map<String, String>): ToolExecutionResult {
        val id = args["id"]?.toLongOrNull()
            ?: return ToolExecutionResult("Missing or invalid argument: id (must be a number)", isError = true)

        val existing = knowledgeDao.findById(id)
        if (existing == null) {
            return ToolExecutionResult("No memory entry found with id=$id.", isError = true)
        }

        knowledgeDao.deleteById(id)
        return ToolExecutionResult("🗑️ Memory entry id=$id deleted permanently.")
    }

    // ──────────────────────────────────────────────
    //  Context Hydration
    // ──────────────────────────────────────────────

    /**
     * Builds a knowledge injection block from enabled Agent Skills plus stored rules/preferences.
     * SkillManager owns its own prompt budget and limits active skills so imported files cannot
     * grow the system prompt without bound.
     */
    suspend fun buildKnowledgeContext(): String? = withContext(Dispatchers.IO) {
        val projectRules = knowledgeDao.findByCategory("project_rule").take(MAX_INJECTED_RULES)
        val userPrefs = knowledgeDao.findByCategory("user_preference").take(MAX_INJECTED_PREFS)
        val archNotes = knowledgeDao.findByCategory("architecture").take(5)
        val all = projectRules + userPrefs + archNotes

        val skillContext = runCatching {
            SkillManager(OmniDevApp.instance.applicationContext).buildEnabledPromptContext()
        }.getOrDefault("")

        if (all.isEmpty() && skillContext.isBlank()) return@withContext null

        buildString {
            if (skillContext.isNotBlank()) append(skillContext)

            if (all.isNotEmpty()) {
                appendLine("\n--- 🧠 LONG-TERM MEMORY (Auto-Injected) ---")
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
                appendLine("\n(Use 'search_knowledge' to retrieve older or specific facts)")
                appendLine("--- END MEMORY ---")
            }
        }
    }
}
