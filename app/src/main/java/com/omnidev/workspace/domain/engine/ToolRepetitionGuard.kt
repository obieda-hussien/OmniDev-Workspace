package com.omnidev.workspace.domain.engine

/**
 * Run-local repetition quota with semantic canonicalization.
 *
 * Models often evade literal duplicate detection accidentally by changing whitespace, case in a
 * search query, path separators, or volatile tracing arguments. The guard normalizes only fields
 * where those changes are not semantically meaningful; commands and arbitrary values remain
 * conservative to avoid blocking legitimate retries with different inputs.
 */
internal class ToolRepetitionGuard(private val limit: Int) {

    init {
        require(limit >= 1) { "limit must be >= 1" }
    }

    private data class CanonicalCall(
        val name: String,
        val arguments: Map<String, String>
    )

    private val counts = mutableMapOf<CanonicalCall, Int>()

    fun allow(name: String, arguments: Map<String, String>): Boolean {
        val key = CanonicalCall(
            name = name.trim().lowercase(),
            arguments = canonicalizeArguments(name, arguments)
        )
        val count = (counts[key] ?: 0) + 1
        counts[key] = count
        return count <= limit
    }

    internal fun seenCount(name: String, arguments: Map<String, String>): Int {
        val key = CanonicalCall(
            name = name.trim().lowercase(),
            arguments = canonicalizeArguments(name, arguments)
        )
        return counts[key] ?: 0
    }

    private fun canonicalizeArguments(
        toolName: String,
        arguments: Map<String, String>
    ): Map<String, String> {
        val normalizedTool = toolName.trim().lowercase()
        return arguments.entries
            .asSequence()
            .filterNot { it.key.trim().lowercase() in VOLATILE_KEYS }
            .sortedBy { it.key.lowercase() }
            .associate { (rawKey, rawValue) ->
                val key = rawKey.trim().lowercase()
                key to normalizeValue(normalizedTool, key, rawValue)
            }
    }

    private fun normalizeValue(toolName: String, key: String, value: String): String {
        val trimmed = value.trim().replace(Regex("\\s+"), " ")
        return when {
            key in QUERY_KEYS -> trimmed.lowercase()
            key in PATH_KEYS -> normalizePath(trimmed)
            key == "action" -> trimmed.lowercase()
            toolName.contains("search") && key == "text" -> trimmed.lowercase()
            else -> trimmed
        }
    }

    private fun normalizePath(path: String): String {
        if (path.isBlank()) return path
        var normalized = path.replace('\\', '/')
        while ("//" in normalized) normalized = normalized.replace("//", "/")
        normalized = normalized.replace("/./", "/")
        if (normalized.startsWith("./")) normalized = normalized.removePrefix("./")
        if (normalized.length > 1) normalized = normalized.trimEnd('/')
        return normalized
    }

    companion object {
        private val VOLATILE_KEYS = setOf(
            "request_id", "trace_id", "span_id", "timestamp", "timestamp_ms", "nonce"
        )

        private val QUERY_KEYS = setOf(
            "query", "q", "pattern", "regex", "search", "keyword", "keywords"
        )

        private val PATH_KEYS = setOf(
            "path", "file", "file_path", "filepath", "directory", "workingdirectory",
            "working_directory", "cwd"
        )
    }
}
