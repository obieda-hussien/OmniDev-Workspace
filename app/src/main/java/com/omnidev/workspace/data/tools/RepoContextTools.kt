package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.repo.RepoContextEngine
import com.omnidev.workspace.data.repo.RepoIndexer

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * RepoContextTools — Context note Context note Live Repository Context (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Context note Context note Agent Context note Context note Context note Context note (mobile-first):
 *   - repo_index_scope: Context note scope Context note Context note
 *   - repo_search_symbols: Context note fuzzy Context note/qualified name
 *   - repo_symbols_by_kind: Context note Context note classes / functions / interfaces
 *   - repo_file_symbols: Context note Context note Context note
 *   - repo_stats: Context note Context note Context note
 *
 * Context note Context note SQL-only (≤ 50 Context note/Context note) — Context note Context note 2-4 GB RAM.
 */
class RepoContextTools(
    @Suppress("unused") private val indexer: RepoIndexer,
    private val engine: RepoContextEngine
) {

    fun getDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "repo_index_scope",
            description = "Info scope/Info Info (Info Info Info Info Info Info Info). " +
                "Info Info Info Info Info Info Info Info Info Info.",
            parameters = listOf(
                ToolParameter("scope_path", "string", "Info Info Info Info", required = false)
            )
        ),
        ToolDefinition(
            name = "repo_search_symbols",
            description = "Info fuzzy Info Info Info Info (classes, functions, properties...). " +
                "Info Info Info grep — Info Info Info Info.",
            parameters = listOf(
                ToolParameter("query", "string", "Info Info Info Info Info (e.g. Foo, parseToken)"),
                ToolParameter("scope_path", "string", "scope (Info scope Info)", required = false),
                ToolParameter("limit", "integer", "Info Info (1-50Info Info 30)", required = false)
            )
        ),
        ToolDefinition(
            name = "repo_symbols_by_kind",
            description = "Info Info Info Info Info Info (class/function/interface/property...). " +
                "Info Info Info Info.",
            parameters = listOf(
                ToolParameter("kind", "string", "Info: class, function, interface, object, enum, property, type"),
                ToolParameter("scope_path", "string", "scope (Info scope Info)", required = false),
                ToolParameter("limit", "integer", "Info Info (1-100Info Info 50)", required = false)
            )
        ),
        ToolDefinition(
            name = "repo_file_symbols",
            description = "Info Info Info Info Info (Info Info Info Info Info).",
            parameters = listOf(
                ToolParameter("file_path", "string", "Info Info Info"),
                ToolParameter("scope_path", "string", "scope (Info scope Info)", required = false)
            )
        ),
        ToolDefinition(
            name = "repo_stats",
            description = "Info Info Info: Info Info Info Info Info.",
            parameters = listOf(
                ToolParameter("scope_path", "string", "scope (Info scope Info)", required = false)
            )
        )
    )

    /** Context note null Context note Context note Context note Context note Context note Context note wrapper (Context note fall-through). */
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        val scope = args["scope_path"]?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult("scope_path Info Info '$name'.", isError = true)

        return try {
            when (name) {
                "repo_index_scope" -> {
                    val res = engine.indexScope(scope)
                    ToolExecutionResult(buildString {
                        appendLine("📚 Info $scope Info (${res.elapsedMs}ms):")
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
                        ?: return ToolExecutionResult("query Info", isError = true)
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 30
                    val results = engine.searchSymbols(scope, q, limit)
                    if (results.isEmpty()) ToolExecutionResult("Info Info Info Info Info '$q'.")
                    else ToolExecutionResult(formatSymbols(results, "🔍 Info '$q' (${results.size}):"))
                }
                "repo_symbols_by_kind" -> {
                    val kind = args["kind"]?.trim()?.lowercase()
                        ?: return ToolExecutionResult("kind Info", isError = true)
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50
                    val results = engine.symbolsByKind(scope, kind, limit)
                    if (results.isEmpty()) ToolExecutionResult("Info Info Info Info Info '$kind'.")
                    else ToolExecutionResult(formatSymbols(results, "📋 Info Info $kind (${results.size}):"))
                }
                "repo_file_symbols" -> {
                    val fp = args["file_path"]?.trim()
                        ?: return ToolExecutionResult("file_path Info", isError = true)
                    val results = engine.fileSymbols(scope, fp)
                    if (results.isEmpty()) ToolExecutionResult("Info Info Info Info $fp.")
                    else ToolExecutionResult(formatSymbols(results, "📄 Info $fp:"))
                }
                "repo_stats" -> {
                    val s = engine.getStats(scope)
                    ToolExecutionResult(buildString {
                        appendLine("📊 Info $scope:")
                        appendLine("  Info: ${s.fileCount}")
                        appendLine("  Info: ${s.symbolCount}")
                        if (s.languages.isNotEmpty()) {
                            appendLine("  Info:")
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
