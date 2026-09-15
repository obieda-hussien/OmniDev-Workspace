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
 * In addition to ordinary memories, `remember_fact(category=agent_skill)` is a
 * deliberately narrow installation bridge for agent-authored SKILL.md documents.
 * The document is validated by [SkillManager] and stored in the user-skill registry;
 * it is NOT inserted into the knowledge table.
 */
class MemoryManager(private val knowledgeDao: KnowledgeDao) {

    companion object {
        private const val MAX_INJECTED_RULES = 10
        private const val MAX_INJECTED_PREFS = 10
        private const val MAX_SEARCH_RESULTS = 15
        private const val SKILL_CATEGORY = "agent_skill"
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "remember_fact",
            description = "Store a new fact, preference, or rule in long-term memory. " +
                "Use this proactively for durable user/project knowledge. Special case: when the user explicitly asks " +
                "to create/install an OmniDev Agent Skill, set category='agent_skill' and put the COMPLETE SKILL.md " +
                "document in content; OmniDev validates and installs it as a user skill instead of storing it as memory.",
            parameters = listOf(
                ToolParameter(
                    "content",
                    "string",
                    "Ordinary fact text, or the complete SKILL.md document when category=agent_skill.",
                    required = true
                ),
                ToolParameter(
                    "category", "string",
                    "Category: user_preference, project_rule, architecture, api_key_hint, general, or agent_skill. " +
                        "Use agent_skill only for an explicitly requested reusable Agent Skill.",
                    required = false
                ),
                ToolParameter(
                    "tags", "string",
                    "Comma-separated keywords for ordinary memory retrieval. Ignored for agent_skill.",
                    required = false
                )
            )
        ),
        ToolDefinition(
            name = "search_knowledge",
            description = "Search long-term memory for stored facts, preferences, and rules. " +
                "The database uses keyword matching, so pass 1-2 distinct keywords instead of a full question.",
            parameters = listOf(
                ToolParameter(
                    "query", "string",
                    "A keyword or short phrase to search for.",
                    required = true
                )
            )
        ),
        ToolDefinition(
            name = "update_memory",
            description = "Update an existing ordinary memory entry by ID. First search_knowledge to find the ID.",
            parameters = listOf(
                ToolParameter("id", "string", "Numeric memory ID.", required = true),
                ToolParameter("content", "string", "Replacement content.", required = true),
                ToolParameter("category", "string", "Updated category (optional).", required = false),
                ToolParameter("tags", "string", "Updated comma-separated tags (optional).", required = false)
            )
        ),
        ToolDefinition(
            name = "delete_memory",
            description = "Permanently delete an ordinary memory entry by ID.",
            parameters = listOf(
                ToolParameter("id", "string", "Numeric memory ID.", required = true)
            )
        )
    )

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
        val rawContent = args["content"]
            ?: return ToolExecutionResult("Missing required argument: content", isError = true)
        val category = args["category"]?.takeIf { it.isNotBlank() }?.lowercase()?.trim() ?: "general"

        if (category == SKILL_CATEGORY) {
            // Do not trim the markdown before writing; preserve formatting after validation.
            val manager = runCatching { SkillManager(OmniDevApp.instance.applicationContext) }
                .getOrElse {
                    return ToolExecutionResult("Skill runtime is unavailable: ${it.message}", isError = true)
                }
            return manager.installSkillMarkdown(rawContent).fold(
                onSuccess = { skill ->
                    ToolExecutionResult(
                        "✅ Agent Skill '${skill.name}' installed and enabled. " +
                            "Manage it in Settings → Tool Arsenal → Agent Skills."
                    )
                },
                onFailure = { error ->
                    ToolExecutionResult(
                        "SKILL_VALIDATION_FAILED: ${error.message ?: "invalid SKILL.md"}",
                        isError = true
                    )
                }
            )
        }

        val content = rawContent.trim()
        if (content.isBlank()) {
            return ToolExecutionResult("Memory content cannot be blank.", isError = true)
        }
        val tags = args["tags"]?.lowercase()?.trim() ?: ""
        val id = knowledgeDao.insert(KnowledgeSnippet(category = category, content = content, tags = tags))
        return ToolExecutionResult("✅ Fact stored successfully in long-term memory (id=$id).")
    }

    private suspend fun searchKnowledge(args: Map<String, String>): ToolExecutionResult {
        val query = args["query"]?.trim()
            ?: return ToolExecutionResult("Missing required argument: query", isError = true)
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

    /**
     * Builds a bounded context block from enabled Agent Skills plus stored rules/preferences.
     * Imported skill instructions remain subordinate to system/tier/authorization policy.
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
