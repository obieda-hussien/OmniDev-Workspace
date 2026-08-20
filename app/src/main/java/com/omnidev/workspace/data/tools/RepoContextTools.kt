package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.repo.RepoContextEngine
import com.omnidev.workspace.data.repo.RepoIndexer

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * RepoContextTools — System awareness note System awareness note Live Repository Context (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * System awareness note System awareness note Agent System awareness note System awareness note System awareness note System awareness note (mobile-first):
 *   - repo_index_scope: System awareness note scope System awareness note System awareness note
 *   - repo_search_symbols: System awareness note fuzzy System awareness note/qualified name
 *   - repo_symbols_by_kind: System awareness note System awareness note classes / functions / interfaces
 *   - repo_file_symbols: System awareness note System awareness note System awareness note
 *   - repo_stats: System awareness note System awareness note System awareness note
 *
 * System awareness note System awareness note SQL-only (≤ 50 System awareness note/System awareness note) — System awareness note System awareness note 2-4 GB RAM.
 */
class RepoContextTools(
    @Suppress("unused") private val indexer: RepoIndexer,
    private val engine: RepoContextEngine
) {

    fun getDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "repo_index_scope",
            description = "System awareness note scope/System awareness note System awareness note (System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note). " +
                "System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.",
            parameters = listOf(
                ToolParameter("scope_path", "string", "System awareness note System awareness note System awareness note System awareness note", required = false)
            )
        ),
        ToolDefinition(
            name = "repo_search_symbols",
            description = "System awareness note fuzzy System awareness note System awareness note System awareness note System awareness note (classes, functions, properties...). " +
                "System awareness note System awareness note System awareness note grep — System awareness note System awareness note System awareness note System awareness note.",
            parameters = listOf(
                ToolParameter("query", "string", "System awareness note System awareness note System awareness note System awareness note System awareness note (e.g. Foo, parseToken)"),
                ToolParameter("scope_path", "string", "scope (System awareness note scope System awareness note)", required = false),
                ToolParameter("limit", "integer", "System awareness note System awareness note (1-50System awareness note System awareness note 30)", required = false)
            )
        ),
        ToolDefinition(
            name = "repo_symbols_by_kind",
            description = "System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note (class/function/interface/property...). " +
                "System awareness note System awareness note System awareness note System awareness note.",
            parameters = listOf(
                ToolParameter("kind", "string", "System awareness note: class, function, interface, object, enum, property, type"),
                ToolParameter("scope_path", "string", "scope (System awareness note scope System awareness note)", required = false),
                ToolParameter("limit", "integer", "System awareness note System awareness note (1-100System awareness note System awareness note 50)", required = false)
            )
        ),
        ToolDefinition(
            name = "repo_file_symbols",
            description = "System awareness note System awareness note System awareness note System awareness note System awareness note (System awareness note System awareness note System awareness note System awareness note System awareness note).",
            parameters = listOf(
                ToolParameter("file_path", "string", "System awareness note System awareness note System awareness note"),
                ToolParameter("scope_path", "string", "scope (System awareness note scope System awareness note)", required = false)
            )
        ),
        ToolDefinition(
            name = "repo_stats",
            description = "System awareness note System awareness note System awareness note: System awareness note System awareness note System awareness note System awareness note System awareness note.",
            parameters = listOf(
                ToolParameter("scope_path", "string", "scope (System awareness note scope System awareness note)", required = false)
            )
        )
    )

    /** System awareness note null System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note wrapper (System awareness note fall-through). */
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        val scope = args["scope_path"]?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult("scope_path System awareness note System awareness note '$name'.", isError = true)

        return try {
            when (name) {
                "repo_index_scope" -> {
                    val res = engine.indexScope(scope)
                    ToolExecutionResult(buildString {
                        appendLine("📚 System awareness note $scope System awareness note (${res.elapsedMs}ms):")
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
                        ?: return ToolExecutionResult("query System awareness note", isError = true)
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 30
                    val results = engine.searchSymbols(scope, q, limit)
                    if (results.isEmpty()) ToolExecutionResult("System awareness note System awareness note System awareness note System awareness note System awareness note '$q'.")
                    else ToolExecutionResult(formatSymbols(results, "🔍 System awareness note '$q' (${results.size}):"))
                }
                "repo_symbols_by_kind" -> {
                    val kind = args["kind"]?.trim()?.lowercase()
                        ?: return ToolExecutionResult("kind System awareness note", isError = true)
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50
                    val results = engine.symbolsByKind(scope, kind, limit)
                    if (results.isEmpty()) ToolExecutionResult("System awareness note System awareness note System awareness note System awareness note System awareness note '$kind'.")
                    else ToolExecutionResult(formatSymbols(results, "📋 System awareness note System awareness note $kind (${results.size}):"))
                }
                "repo_file_symbols" -> {
                    val fp = args["file_path"]?.trim()
                        ?: return ToolExecutionResult("file_path System awareness note", isError = true)
                    val results = engine.fileSymbols(scope, fp)
                    if (results.isEmpty()) ToolExecutionResult("System awareness note System awareness note System awareness note System awareness note $fp.")
                    else ToolExecutionResult(formatSymbols(results, "📄 System awareness note $fp:"))
                }
                "repo_stats" -> {
                    val s = engine.getStats(scope)
                    ToolExecutionResult(buildString {
                        appendLine("📊 System awareness note $scope:")
                        appendLine("  System awareness note: ${s.fileCount}")
                        appendLine("  System awareness note: ${s.symbolCount}")
                        if (s.languages.isNotEmpty()) {
                            appendLine("  System awareness note:")
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
