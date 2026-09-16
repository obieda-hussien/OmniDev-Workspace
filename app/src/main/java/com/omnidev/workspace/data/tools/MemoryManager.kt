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
 * `search_knowledge` also acts as the stable on-demand skill invocation bridge:
 *   - query="skills" lists installed skills
 *   - query="skill:<exact-name>" loads that enabled skill into the current turn
 */
class MemoryManager(private val knowledgeDao: KnowledgeDao) {

    companion object {
        private const val MAX_INJECTED_RULES = 10
        private const val MAX_INJECTED_PREFS = 10
        private const val MAX_SEARCH_RESULTS = 15
        private const val SKILL_CATEGORY = "agent_skill"
        private const val SKILL_LIST_QUERY = "skills"
        private const val SKILL_QUERY_PREFIX = "skill:"
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "remember_fact",
            description = "Store a new fact, preference, or rule in long-term memory. " +
                "Special case: when the user asks to create/install an OmniDev Agent Skill, set category='agent_skill' " +
                "and put the COMPLETE SKILL.md document in content. OmniDev validates, installs, and enables it in the local skill registry.",
            parameters = listOf(
                ToolParameter(
                    "content",
                    "string",
                    "Ordinary fact text, or the complete SKILL.md document when category=agent_skill.",
                    required = true
                ),
                ToolParameter(
                    "category", "string",
                    "Category: user_preference, project_rule, architecture, api_key_hint, general, or agent_skill.",
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
            description = "Search long-term memory OR use the local Agent Skills registry. " +
                "Use query='skills' to list installed skills. When a skill matches the current task, invoke it with " +
                "query='skill:<exact-skill-name>' BEFORE acting; the result returns the full current SKILL.md instructions. " +
                "For ordinary memory search, pass 1-2 distinct keywords instead of a full question.",
            parameters = listOf(
                ToolParameter(
                    "query", "string",
                    "Memory keyword, 'skills', or 'skill:<exact-skill-name>' for on-demand skill invocation.",
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
            val manager = runCatching { SkillManager(OmniDevApp.instance.applicationContext) }
                .getOrElse {
                    return ToolExecutionResult("Skill runtime is unavailable: ${it.message}", isError = true)
                }
            return manager.installSkillMarkdown(rawContent).fold(
                onSuccess = { skill ->
                    ToolExecutionResult(
                        "✅ Agent Skill '${skill.name}' installed and enabled. " +
                            "It is immediately available through search_knowledge(query=\"skill:${skill.name}\"). " +
                            "Manage it in Settings → Agent Skills."
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
        if (query.isBlank()) return ToolExecutionResult("Search query cannot be blank.", isError = true)

        val skillManager = runCatching { SkillManager(OmniDevApp.instance.applicationContext) }.getOrNull()

        if (query.equals(SKILL_LIST_QUERY, ignoreCase = true)) {
            val skills = skillManager?.listSkills().orEmpty()
            if (skills.isEmpty()) return ToolExecutionResult("No Agent Skills are installed.")
            return ToolExecutionResult(
                buildString {
                    appendLine("🧩 Installed Agent Skills (${skills.size})")
                    appendLine("Invoke an enabled skill with search_knowledge(query=\"skill:<exact-name>\").")
                    appendLine()
                    skills.forEach { skill ->
                        appendLine(
                            "- ${skill.name} | ${if (skill.enabled) "ENABLED" else "DISABLED"} | " +
                                "${skill.origin.name.lowercase()} | ${skill.description}"
                        )
                    }
                }.trimEnd()
            )
        }

        if (query.startsWith(SKILL_QUERY_PREFIX, ignoreCase = true)) {
            val requestedName = query.substringAfter(':').trim().lowercase()
            if (requestedName.isBlank()) {
                return ToolExecutionResult(
                    "Skill invocation requires an exact name, e.g. query='skill:omnidev-android-engineering'.",
                    isError = true
                )
            }
            val manager = skillManager
                ?: return ToolExecutionResult("Skill runtime is unavailable.", isError = true)
            return manager.buildInvocation(requestedName).fold(
                onSuccess = { ToolExecutionResult(it) },
                onFailure = { error ->
                    ToolExecutionResult(
                        "SKILL_NOT_AVAILABLE: ${error.message}. Call search_knowledge(query='skills') to inspect the registry.",
                        isError = true
                    )
                }
            )
        }

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
     * Builds a bounded context block from the Agent Skills catalog plus stored
     * rules/preferences. Full skill bodies are loaded on demand, preventing prompt
     * bloat while allowing every enabled imported/agent-authored skill to be used.
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
                appendLine("\n(Use 'search_knowledge' to retrieve older/specific facts or invoke Agent Skills.)")
                appendLine("--- END MEMORY ---")
            }
        }
    }
}
