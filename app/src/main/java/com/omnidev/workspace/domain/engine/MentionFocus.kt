package com.omnidev.workspace.domain.engine

/** Explicit composer directives. Ordinary @handles, emails and quoted code are not directives. */
data class MentionFocus(val tools: Set<String> = emptySet(), val skills: Set<String> = emptySet()) {
    val active: Boolean get() = tools.isNotEmpty() || skills.isNotEmpty()

    fun permitsTool(name: String): Boolean = tools.isEmpty() || name in tools

    fun validateTools(available: Set<String>) {
        val missing = tools - available
        require(missing.isEmpty()) { "Mentioned tools are unavailable or disabled: ${missing.joinToString()}. Remove the mention or enable the tool." }
    }

    fun prompt(): String = buildString {
        if (tools.isNotEmpty()) appendLine("User selected tools for this turn: ${tools.joinToString()}. Use only this tool set. If it cannot finish the task, explain the missing capability and ask the user to adjust the mentions. Never probe unrelated tools or claim success without evidence.")
        if (skills.isNotEmpty()) appendLine("Apply these explicitly selected skills to the current task: ${skills.joinToString()}. Their instructions are preloaded; do not search for other skills. Tool mentions restrict tools; skill mentions specialize instructions and do not grant access.")
    }

    companion object {
        private val CODE = Regex("```[\\s\\S]*?(?:```|$)|`[^`\\n]*`")
        private val DIRECTIVE = Regex("(?<![\\p{L}\\p{N}_/@:])@(tool|skill):([A-Za-z0-9_.-]+)")
        private val EMPTY = Regex("(?<![\\p{L}\\p{N}_/@:])@(tool|skill):(?![A-Za-z0-9_.-])")

        fun parse(text: String): MentionFocus {
            val plain = CODE.replace(text) { " ".repeat(it.value.length) }
            require(!EMPTY.containsMatchIn(plain)) { "Complete the tool or skill mention before sending." }
            val matches = DIRECTIVE.findAll(plain).toList()
            val tools = matches.filter { it.groupValues[1] == "tool" }.map { it.groupValues[2].trimEnd('.') }.toSet()
            val skills = matches.filter { it.groupValues[1] == "skill" }.map { it.groupValues[2].trimEnd('.') }.toSet()
            require(tools.size <= 12 && skills.size <= 4) { "Select at most 12 tools and 4 skills per message." }
            return MentionFocus(tools, skills)
        }

        fun remove(text: String, candidate: MentionCandidate): String {
            val plain = CODE.replace(text) { " ".repeat(it.value.length) }
            val ranges = DIRECTIVE.findAll(plain).filter {
                it.groupValues[1] == candidate.kind && it.groupValues[2].trimEnd('.') == candidate.name
            }.map { it.range }.toList()
            var result = text
            ranges.asReversed().forEach { range -> result = result.removeRange(range) }
            return result
        }

        data class Query(val start: Int, val end: Int, val text: String)

        /** Complete the token at the caret without destroying the rest of a multiline draft. */
        fun query(text: String, cursor: Int): Query? {
            if (cursor !in 0..text.length) return null
            val prefix = text.take(cursor)
            val match = Regex("(?<![\\p{L}\\p{N}_/@:])@([A-Za-z0-9_:.-]*)$").find(prefix) ?: return null
            if (CODE.findAll(text).any { match.range.first in it.range }) return null
            val end = text.indexOfFirstFrom(cursor) { !it.isLetterOrDigit() && it !in "_:.-" }
            return Query(match.range.first, end, match.groupValues[1])
        }

        private fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int =
            (start until length).firstOrNull { predicate(this[it]) } ?: length
    }
}

data class MentionCandidate(val kind: String, val name: String, val description: String) {
    val token: String get() = "@$kind:$name"

    companion object {
        fun search(candidates: List<MentionCandidate>, query: String): List<MentionCandidate> {
            val normalized = query.lowercase()
            return candidates.filter {
                normalized.isBlank() || it.token.drop(1).lowercase().contains(normalized) ||
                    (':' !in normalized && (it.name.lowercase().contains(normalized) || it.description.lowercase().contains(normalized)))
            }.sortedWith(compareBy<MentionCandidate> { !it.name.startsWith(normalized.substringAfter(':')) }.thenBy { it.name }).take(6)
        }
    }
}
