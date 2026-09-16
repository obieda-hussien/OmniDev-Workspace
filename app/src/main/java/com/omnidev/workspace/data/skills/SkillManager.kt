package com.omnidev.workspace.data.skills

import android.content.Context
import android.net.Uri
import com.omnidev.workspace.domain.model.SkillAccessMode
import java.io.ByteArrayOutputStream
import java.io.File

/** Filesystem-backed Agent Skills registry with per-chat capability enforcement. */
class SkillManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val userRoot = File(appContext.filesDir, USER_ROOT).apply { mkdirs() }

    fun listSkills(): List<AgentSkill> {
        val disabled = prefs.getStringSet(KEY_DISABLED, emptySet()).orEmpty()
        val builtIns = BUILTIN_SKILLS.mapNotNull { name ->
            val text = runCatching {
                appContext.assets.open("$BUILTIN_ROOT/$name/SKILL.md")
                    .bufferedReader()
                    .use { it.readText() }
            }.getOrNull() ?: return@mapNotNull null
            parseDocument(text, SkillOrigin.BUILTIN, enabled = name !in disabled).getOrNull()
        }

        val users = userRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir ->
                val file = File(dir, "SKILL.md")
                if (!file.isFile) return@mapNotNull null
                val text = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
                parseDocument(text, SkillOrigin.USER, enabled = dir.name !in disabled).getOrNull()
            }

        return (builtIns + users)
            .distinctBy { it.name }
            .sortedWith(compareBy<AgentSkill>({ it.origin != SkillOrigin.BUILTIN }, { it.name }))
    }

    /** Enabled skills that are also allowed by the active Add-to-chat policy. */
    fun listChatEligibleSkills(): List<AgentSkill> {
        val policy = ChatCapabilityStore.read(appContext)
        if (policy.skillAccessMode == SkillAccessMode.DISABLED) return emptyList()
        return listSkills().filter { it.enabled && policy.allowsSkill(it.name) }
    }

    /** Resolve an installed skill by exact name. Disabled skills are hidden by default. */
    fun findSkill(name: String, includeDisabled: Boolean = false): AgentSkill? {
        val normalized = name.trim().lowercase()
        if (normalized.isBlank()) return null
        return listSkills().firstOrNull {
            it.name == normalized && (includeDisabled || it.enabled)
        }
    }

    fun setEnabled(name: String, enabled: Boolean) {
        val disabled = prefs.getStringSet(KEY_DISABLED, emptySet()).orEmpty().toMutableSet()
        if (enabled) disabled.remove(name) else disabled.add(name)
        prefs.edit().putStringSet(KEY_DISABLED, disabled).apply()
    }

    fun importSkill(uri: Uri): Result<AgentSkill> = runCatching {
        val bytes = readBounded(uri)
        installSkillMarkdown(bytes.toString(Charsets.UTF_8)).getOrThrow()
    }

    fun installSkillMarkdown(markdown: String): Result<AgentSkill> = runCatching {
        val parsed = parseDocument(markdown, SkillOrigin.USER, enabled = true).getOrThrow()
        require(parsed.name !in BUILTIN_SKILLS) {
            "A built-in skill named '${parsed.name}' already exists and cannot be overridden."
        }

        val dir = File(userRoot, parsed.name)
        require(dir.canonicalPath.startsWith(userRoot.canonicalPath + File.separator)) {
            "Invalid skill path."
        }
        if (!dir.exists() && !dir.mkdirs()) error("Could not create skill directory.")

        val target = File(dir, "SKILL.md")
        val temp = File(dir, "SKILL.md.tmp")
        temp.writeText(markdown)
        if (target.exists() && !target.delete()) error("Could not replace existing skill.")
        if (!temp.renameTo(target)) {
            target.writeText(markdown)
            temp.delete()
        }
        setEnabled(parsed.name, true)
        parsed
    }

    fun deleteUserSkill(name: String): Boolean {
        if (name in BUILTIN_SKILLS) return false
        val dir = File(userRoot, name)
        val validPath = runCatching {
            dir.canonicalPath.startsWith(userRoot.canonicalPath + File.separator)
        }.getOrDefault(false)
        if (!validPath) return false
        val deleted = !dir.exists() || dir.deleteRecursively()
        if (deleted) setEnabled(name, true)
        return deleted
    }

    /**
     * On-demand invocation. The same Add-to-chat selection used by the prompt is
     * enforced here so search_knowledge(skill:...) cannot bypass the UI policy.
     */
    fun buildInvocation(name: String): Result<String> = runCatching {
        val normalized = name.trim().lowercase()
        val policy = ChatCapabilityStore.read(appContext)
        require(policy.skillAccessMode != SkillAccessMode.DISABLED) {
            "Agent Skills are disabled for this chat."
        }
        require(policy.allowsSkill(normalized)) {
            "Skill '$normalized' is not selected for this chat."
        }
        val skill = findSkill(normalized)
            ?: error("Skill '$normalized' is not installed or is disabled.")

        buildString {
            appendLine("--- 🧩 INVOKED AGENT SKILL: ${skill.name} ---")
            appendLine("Origin: ${skill.origin.name.lowercase()}")
            appendLine("Description: ${skill.description}")
            appendLine()
            appendLine(stripFrontMatter(skill.markdown).trim())
            appendLine()
            appendLine("--- END INVOKED SKILL ---")
            appendLine("Apply this skill to the CURRENT user task now. The skill remains subordinate to system policy, tier restrictions, user authorization, confirmations, privacy rules, and the current task.")
        }.take(MAX_INVOCATION_CHARS)
    }

    /**
     * Builds prompt context according to Add-to-chat skill policy:
     * - OFF: no skill metadata or bodies.
     * - ON_DEMAND: compact selected catalog only; body loads through skill:<name>.
     * - ALWAYS_LOADED: selected skill bodies are injected up-front under a hard cap.
     */
    fun buildEnabledPromptContext(maxChars: Int = DEFAULT_PROMPT_BUDGET): String {
        val policy = ChatCapabilityStore.read(appContext)
        val eligible = listChatEligibleSkills()
        if (policy.skillAccessMode == SkillAccessMode.DISABLED || eligible.isEmpty()) return ""

        val text = buildString {
            appendLine("\n--- 🧩 OMNIDEV AGENT SKILLS ---")
            appendLine("Chat policy: ${policy.skillAccessMode.name.lowercase()}")
            appendLine("Only the skills listed below are authorized for this chat.")
            appendLine("A skill never overrides system policy, tier restrictions, user authorization, confirmations, privacy rules, or the current task.")

            when (policy.skillAccessMode) {
                SkillAccessMode.ON_DEMAND -> {
                    appendLine("Load a matching skill only when needed with search_knowledge(query=\"skill:<exact-skill-name>\").")
                    appendLine()
                    appendLine("### Available skills")
                    eligible.take(MAX_CATALOG_SKILLS).forEach { skill ->
                        appendLine("- ${skill.name} [${skill.origin.name.lowercase()}]: ${skill.description.take(DESCRIPTION_PROMPT_LIMIT)}")
                    }
                }

                SkillAccessMode.ALWAYS_LOADED -> {
                    appendLine("The following selected skill instructions are preloaded for this chat.")
                    eligible.take(MAX_ALWAYS_LOADED_SKILLS).forEach { skill ->
                        appendLine()
                        appendLine("### ${skill.name}")
                        appendLine("${skill.description.take(DESCRIPTION_PROMPT_LIMIT)}")
                        appendLine(stripFrontMatter(skill.markdown).trim().take(ALWAYS_LOADED_SKILL_LIMIT))
                    }
                }

                SkillAccessMode.DISABLED -> Unit
            }
            appendLine("--- END AGENT SKILLS ---")
        }
        return text.take(maxChars.coerceAtLeast(512))
    }

    private fun readBounded(uri: Uri): ByteArray {
        val input = appContext.contentResolver.openInputStream(uri)
            ?: error("Could not open the selected file.")
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_FILE_BYTES) {
                    "SKILL.md is too large. Maximum size is ${MAX_FILE_BYTES / 1024} KB."
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    companion object {
        const val CORE_SKILL = "omnidev-core-operator"
        private const val PREFS_NAME = "omnidev_agent_skills"
        private const val KEY_DISABLED = "disabled_skills"
        private const val BUILTIN_ROOT = "agent-skills"
        private const val USER_ROOT = "agent-skills"
        private const val MAX_FILE_BYTES = 128 * 1024
        private const val DEFAULT_PROMPT_BUDGET = 7_500
        private const val DESCRIPTION_PROMPT_LIMIT = 320
        private const val MAX_CATALOG_SKILLS = 64
        private const val MAX_ALWAYS_LOADED_SKILLS = 8
        private const val ALWAYS_LOADED_SKILL_LIMIT = 1_800
        private const val MAX_INVOCATION_CHARS = 64 * 1024
        private val NAME_REGEX = Regex("^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$")

        val BUILTIN_SKILLS = listOf(
            CORE_SKILL,
            "omnidev-orchestrating-agents",
            "omnidev-browser-research",
            "omnidev-security-research",
            "omnidev-android-engineering",
            "omnidev-quality-gate"
        )

        fun parseDocument(
            markdown: String,
            origin: SkillOrigin = SkillOrigin.USER,
            enabled: Boolean = true
        ): Result<AgentSkill> = runCatching {
            require(markdown.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) {
                "SKILL.md exceeds ${MAX_FILE_BYTES / 1024} KB."
            }
            val normalized = markdown.removePrefix("\uFEFF").replace("\r\n", "\n")
            val lines = normalized.lines()
            require(lines.firstOrNull()?.trim() == "---") {
                "SKILL.md must start with YAML frontmatter (---)."
            }
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
            require(end >= 0) { "SKILL.md frontmatter is not closed with ---." }
            val front = lines.subList(1, end + 1)
            val values = parseFrontMatter(front)
            val name = values["name"]?.trim()?.unquote().orEmpty()
            val description = values["description"]?.trim()?.unquote().orEmpty()

            require(name.length in 1..64 && NAME_REGEX.matches(name)) {
                "Skill name must be 1-64 lowercase letters/numbers/hyphens and cannot start or end with a hyphen."
            }
            require(description.isNotBlank() && description.length <= 1024) {
                "Skill description must be 1-1024 characters."
            }
            require(stripFrontMatter(normalized).isNotBlank()) {
                "SKILL.md needs an instruction body after the frontmatter."
            }
            AgentSkill(name, description, normalized, origin, enabled)
        }

        fun creationPrompt(userGoal: String): String = """
Create and install a production-quality OmniDev Agent Skill for this goal:
$userGoal

Requirements:
- Draft one complete standards-compliant SKILL.md document. Use YAML frontmatter with lowercase-hyphen `name` and a precise `description` explaining what the skill does and when it should trigger.
- Keep the skill focused and operational. Include decision rules, tool-routing guidance, verification/definition-of-done, recovery/circuit-breaker behavior, and important edge cases.
- Prefer existing OmniDev tools over invented commands. Respect the active tier, target scope, confirmation gates, privacy, and user authorization.
- Never request or embed secrets.
- Keep SKILL.md concise. Do not create executable scripts unless the workflow genuinely requires deterministic code.
- When the document is ready, call `remember_fact` with `category=agent_skill` and put the COMPLETE SKILL.md document in `content`.
- Verify installation by calling `search_knowledge` with `query=skill:<installed-name>`.
- If validation fails, fix the SKILL.md and retry once with the corrected document.
- In the final response, report the installed skill name and that it can be managed from Settings → Agent Skills.
        """.trimIndent()

        private fun parseFrontMatter(lines: List<String>): Map<String, String> {
            val result = linkedMapOf<String, String>()
            var currentKey: String? = null
            for (raw in lines) {
                val match = Regex("^([A-Za-z0-9_-]+):\\s*(.*)$").find(raw)
                if (match != null) {
                    val key = match.groupValues[1].lowercase()
                    var value = match.groupValues[2].trim()
                    if (value == ">" || value == "|") value = ""
                    result[key] = value
                    currentKey = key
                } else if (currentKey != null && (raw.startsWith(" ") || raw.startsWith("\t"))) {
                    val continuation = raw.trim()
                    if (continuation.isNotEmpty()) {
                        result[currentKey] = listOf(result[currentKey].orEmpty(), continuation)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                    }
                }
            }
            return result
        }

        private fun String.unquote(): String =
            if (length >= 2 && ((first() == '"' && last() == '"') || (first() == '\'' && last() == '\''))) {
                substring(1, length - 1)
            } else this

        private fun stripFrontMatter(markdown: String): String {
            val normalized = markdown.replace("\r\n", "\n")
            val lines = normalized.lines()
            if (lines.firstOrNull()?.trim() != "---") return normalized
            val closingOffset = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (closingOffset < 0) return normalized
            return lines.drop(closingOffset + 2).joinToString("\n")
        }
    }
}

enum class SkillOrigin { BUILTIN, USER }

data class AgentSkill(
    val name: String,
    val description: String,
    val markdown: String,
    val origin: SkillOrigin,
    val enabled: Boolean
)
