package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.repo.RepoContextEngine
import com.omnidev.workspace.data.repo.RepoIndexer

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * RepoContextTools — [Localized] [Localized] Live Repository Context (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * [Localized] [Localized] Agent [Localized] [Localized] [Localized] [Localized] (mobile-first):
 *   - repo_index_scope: [Localized] scope [Localized] [Localized]
 *   - repo_search_symbols: [Localized] fuzzy [Localized]/qualified name
 *   - repo_symbols_by_kind: [Localized] [Localized] classes / functions / interfaces
 *   - repo_file_symbols: [Localized] [Localized] [Localized]
 *   - repo_stats: [Localized] [Localized] [Localized]
 *
 * [Localized] [Localized] SQL-only (≤ 50 [Localized]/[Localized]) — [Localized] [Localized] 2-4 GB RAM.
 */
class RepoContextTools(
    @Suppress("unused") private val indexer: RepoIndexer,
    private val engine: RepoContextEngine
) {

    fun getDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "repo_index_scope",
            description = "[Localized] scope/[Localized] [Localized] ([Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]). " +
                "[Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].",
            parameters = listOf(
                ToolParameter("scope_path", "string", "[Localized] [Localized] [Localized] [Localized]", required = false)
            )
        ),
        ToolDefinition(
            name = "repo_search_symbols",
            description = "[Localized] fuzzy [Localized] [Localized] [Localized] [Localized] (classes, functions, properties...). " +
                "[Localized] [Localized] [Localized] grep — [Localized] [Localized] [Localized] [Localized].",
            parameters = listOf(
                ToolParameter("query", "string", "[Localized] [Localized] [Localized] [Localized] [Localized] (e.g. Foo, parseToken)"),
                ToolParameter("scope_path", "string", "scope ([Localized] scope [Localized])", required = false),
                ToolParameter("limit", "integer", "[Localized] [Localized] (1-50[Localized] [Localized] 30)", required = false)
            )
        ),
        ToolDefinition(
            name = "repo_symbols_by_kind",
            description = "[Localized] [Localized] [Localized] [Localized] [Localized] [Localized] (class/function/interface/property...). " +
                "[Localized] [Localized] [Localized] [Localized].",
            parameters = listOf(
                ToolParameter("kind", "string", "[Localized]: class, function, interface, object, enum, property, type"),
                ToolParameter("scope_path", "string", "scope ([Localized] scope [Localized])", required = false),
                ToolParameter("limit", "integer", "[Localized] [Localized] (1-100[Localized] [Localized] 50)", required = false)
            )
        ),
        ToolDefinition(
            name = "repo_file_symbols",
            description = "[Localized] [Localized] [Localized] [Localized] [Localized] ([Localized] [Localized] [Localized] [Localized] [Localized]).",
            parameters = listOf(
                ToolParameter("file_path", "string", "[Localized] [Localized] [Localized]"),
                ToolParameter("scope_path", "string", "scope ([Localized] scope [Localized])", required = false)
            )
        ),
        ToolDefinition(
            name = "repo_stats",
            description = "[Localized] [Localized] [Localized]: [Localized] [Localized] [Localized] [Localized] [Localized].",
            parameters = listOf(
                ToolParameter("scope_path", "string", "scope ([Localized] scope [Localized])", required = false)
            )
        )
    )

    /** [Localized] null [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] wrapper ([Localized] fall-through). */
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        val scope = args["scope_path"]?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult("scope_path [Localized] [Localized] '$name'.", isError = true)

        return try {
            when (name) {
                "repo_index_scope" -> {
                    val res = engine.indexScope(scope)
                    ToolExecutionResult(buildString {
                        appendLine("📚 [Localized] $scope [Localized] (${res.elapsedMs}ms):")
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
                        ?: return ToolExecutionResult("query [Localized]", isError = true)
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 30
                    val results = engine.searchSymbols(scope, q, limit)
                    if (results.isEmpty()) ToolExecutionResult("[Localized] [Localized] [Localized] [Localized] [Localized] '$q'.")
                    else ToolExecutionResult(formatSymbols(results, "🔍 [Localized] '$q' (${results.size}):"))
                }
                "repo_symbols_by_kind" -> {
                    val kind = args["kind"]?.trim()?.lowercase()
                        ?: return ToolExecutionResult("kind [Localized]", isError = true)
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50
                    val results = engine.symbolsByKind(scope, kind, limit)
                    if (results.isEmpty()) ToolExecutionResult("[Localized] [Localized] [Localized] [Localized] [Localized] '$kind'.")
                    else ToolExecutionResult(formatSymbols(results, "📋 [Localized] [Localized] $kind (${results.size}):"))
                }
                "repo_file_symbols" -> {
                    val fp = args["file_path"]?.trim()
                        ?: return ToolExecutionResult("file_path [Localized]", isError = true)
                    val results = engine.fileSymbols(scope, fp)
                    if (results.isEmpty()) ToolExecutionResult("[Localized] [Localized] [Localized] [Localized] $fp.")
                    else ToolExecutionResult(formatSymbols(results, "📄 [Localized] $fp:"))
                }
                "repo_stats" -> {
                    val s = engine.getStats(scope)
                    ToolExecutionResult(buildString {
                        appendLine("📊 [Localized] $scope:")
                        appendLine("  [Localized]: ${s.fileCount}")
                        appendLine("  [Localized]: ${s.symbolCount}")
                        if (s.languages.isNotEmpty()) {
                            appendLine("  [Localized]:")
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
            "repo_symbols_by_kind",
            "repo_file_symbols",
            "repo_stats"
        )
    }
}
