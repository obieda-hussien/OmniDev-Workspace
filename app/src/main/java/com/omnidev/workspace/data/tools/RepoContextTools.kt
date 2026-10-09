package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.repo.RepoContextEngine
import com.omnidev.workspace.data.repo.RepoIndexer

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * RepoContextTools — Live Repository Context query tools (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Provides lightweight, mobile-first symbol search for the agent:
 *   - repo_index_scope: Incrementally index the entire scope
 *   - repo_search_symbols: Fuzzy search by name or qualified name
 *   - repo_symbols_by_kind: List classes, functions and interfaces
 *   - repo_file_symbols: List symbols in a file
 *   - repo_stats: Indexed project statistics
 *
 * SQL-only queries (up to 50 symbols per query), designed for devices with 2-4 GB RAM.
 */
class RepoContextTools(
    @Suppress("unused") private val indexer: RepoIndexer,
    private val engine: RepoContextEngine
) {

    fun getDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "repo_index_scope",
            description = "Incrementally index a scope or project, skipping unchanged files. " +
                "Use at task start if the project has not been indexed.",
            parameters = listOf(
                ToolParameter("scope_path", "string", "Absolute path to the project root", required = false)
            )
        ),
        ToolDefinition(
            name = "repo_search_symbols",
            description = "Fuzzy name search over indexed symbols (classes, functions, properties). " +
                "Queries the local index directly instead of scanning file contents.",
            parameters = listOf(
                ToolParameter("query", "string", "Full or partial symbol name to search (e.g. Foo, parseToken)"),
                ToolParameter("scope_path", "string", "Scope (defaults to the session scope)", required = false),
                ToolParameter("limit", "integer", "Number of results (1-50; default: 30)", required = false)
            )
        ),
        ToolDefinition(
            name = "repo_find_context",
            description = "Find local source evidence for a natural-language question about repository behavior. Returns bounded verbatim excerpts with line numbers; an empty result does not prove absence.",
            parameters = listOf(
                ToolParameter("question", "string", "What behavior or code path are you trying to find?"),
                ToolParameter("scope_path", "string", "Absolute project root"),
                ToolParameter("limit", "integer", "Number of excerpts (1-12, default 6)", required = false)
            )
        ),
        ToolDefinition(
            name = "repo_symbols_by_kind",
            description = "Retrieve symbols of a given kind (class/function/interface/property). " +
                "Useful for exploring project structure.",
            parameters = listOf(
                ToolParameter("kind", "string", "Kind: class, function, interface, object, enum, property, type"),
                ToolParameter("scope_path", "string", "Scope (defaults to the session scope)", required = false),
                ToolParameter("limit", "integer", "Number of results (1-100; default: 50)", required = false)
            )
        ),
        ToolDefinition(
            name = "repo_file_symbols",
            description = "List symbols in a specific file for a quick overview of its contents.",
            parameters = listOf(
                ToolParameter("file_path", "string", "Absolute file path"),
                ToolParameter("scope_path", "string", "Scope (defaults to the session scope)", required = false)
            )
        ),
        ToolDefinition(
            name = "repo_stats",
            description = "Indexed project statistics: file count, symbol count and language distribution.",
            parameters = listOf(
                ToolParameter("scope_path", "string", "Scope (defaults to the session scope)", required = false)
            )
        )
    )

    /** Returns null for tools not handled by this wrapper, allowing fall-through. */
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        val scope = args["scope_path"]?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult("scope_path is required to execute '$name'.", isError = true)

        return try {
            when (name) {
                "repo_index_scope" -> {
                    val res = engine.indexScope(scope)
                    ToolExecutionResult(buildString {
                        appendLine("📚 Indexing $scope completed (${res.elapsedMs}ms):")
                        appendLine("  scanned = ${res.totalScanned}")
                        appendLine("  indexed (new) = ${res.indexed}")
                        appendLine("  updated = ${res.updated}")
                        appendLine("  unchanged = ${res.unchanged}")
                        appendLine("  skipped = ${res.skipped}")
                        appendLine("  symbols = ${res.symbolsExtracted}")
                    })
                }
                "repo_search_symbols" -> {
                    val q = args["query"]?.trim()
                        ?: return ToolExecutionResult("query is required", isError = true)
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 30
                    val results = engine.searchSymbols(scope, q, limit)
                    if (results.isEmpty()) ToolExecutionResult("No symbols matched '$q'.")
                    else ToolExecutionResult(formatSymbols(results, "🔍 Results for '$q' (${results.size}):"))
                }
                "repo_find_context" -> {
                    val question = args["question"]?.trim().orEmpty()
                    if (question.isBlank()) return ToolExecutionResult("question is required", isError = true)
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 12) ?: 6
                    val hits = engine.findContext(scope, question, limit)
                    ToolExecutionResult(if (hits.isEmpty()) {
                        "No indexed source excerpts matched. The index may be incomplete; use search_codebase or read files directly."
                    } else buildString {
                        appendLine("Local source evidence (${hits.size}); inspect surrounding code before editing:")
                        hits.forEach { hit ->
                            appendLine("\n${hit.path}:${hit.startLine}-${hit.endLine}")
                            appendLine(hit.excerpt)
                        }
                    })
                }
                "repo_symbols_by_kind" -> {
                    val kind = args["kind"]?.trim()?.lowercase()
                        ?: return ToolExecutionResult("kind is required", isError = true)
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50
                    val results = engine.symbolsByKind(scope, kind, limit)
                    if (results.isEmpty()) ToolExecutionResult("No symbols of kind '$kind'.")
                    else ToolExecutionResult(formatSymbols(results, "📋 Symbols of kind $kind (${results.size}):"))
                }
                "repo_file_symbols" -> {
                    val fp = args["file_path"]?.trim()
                        ?: return ToolExecutionResult("file_path is required", isError = true)
                    val results = engine.fileSymbols(scope, fp)
                    if (results.isEmpty()) ToolExecutionResult("No symbols in $fp.")
                    else ToolExecutionResult(formatSymbols(results, "📄 Symbols in $fp:"))
                }
                "repo_stats" -> {
                    val s = engine.getStats(scope)
                    ToolExecutionResult(buildString {
                        appendLine("📊 Statistics for $scope:")
                        appendLine("  Files: ${s.fileCount}")
                        appendLine("  Symbols in: ${s.symbolCount}")
                        if (s.languages.isNotEmpty()) {
                            appendLine("  Languages:")
                            for ((lang, n) in s.languages.take(10)) {
                                appendLine("    - $lang: $n")
                            }
                        }
                    })
                }
                else -> ToolExecutionResult("Unknown tool: $name", isError = true)
            }
        } catch (t: Throwable) {
            ToolExecutionResult("Repo context tool error: ${t.message}", isError = true)
        }
    }

    private fun formatSymbols(
        list: List<com.omnidev.workspace.data.db.entities.RepoSymbolEntry>,
        header: String
    ): String = buildString {
        appendLine(header)
        for (s in list) {
            appendLine("  [${s.symbolKind}] ${s.qualifiedName.ifBlank { s.symbolName }} — ${s.filePath}:${s.lineNumber}")
            if (s.snippet.isNotBlank()) appendLine("       ${s.snippet.take(120)}")
        }
    }

    fun handles(name: String): Boolean = name in HANDLED

    companion object {
        val HANDLED = setOf(
            "repo_index_scope",
            "repo_search_symbols",
            "repo_find_context",
            "repo_symbols_by_kind",
            "repo_file_symbols",
            "repo_stats"
        )
    }
}
