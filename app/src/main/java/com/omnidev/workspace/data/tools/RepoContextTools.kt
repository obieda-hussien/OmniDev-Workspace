package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.repo.RepoContextEngine
import com.omnidev.workspace.data.repo.RepoIndexer

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * RepoContextTools — أدوات استعلام Live Repository Context (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * توفّر للـ Agent قدرات بحث رمزي خفيفة (mobile-first):
 *   - repo_index_scope: فهرسة scope بالكامل تدريجياً
 *   - repo_search_symbols: بحث fuzzy بالاسم/qualified name
 *   - repo_symbols_by_kind: كل الـ classes / functions / interfaces
 *   - repo_file_symbols: قائمة رموز ملف
 *   - repo_stats: إحصاءات المشروع المُفهرس
 *
 * كل الاستعلامات SQL-only (≤ 50 رمز/استعلام) — آمنة لأجهزة 2-4 GB RAM.
 */
class RepoContextTools(
    @Suppress("unused") private val indexer: RepoIndexer,
    private val engine: RepoContextEngine
) {

    fun getDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "repo_index_scope",
            description = "فهرسة scope/مشروع بالكامل (تدريجياً، لا يعيد فهرسة ما لم يتغيّر). " +
                "استخدم مرة واحدة في بداية المهمة لو لم يُفهرس بعد.",
            parameters = listOf(
                ToolParameter("scope_path", "string", "المسار المطلق لجذر المشروع", required = false)
            )
        ),
        ToolDefinition(
            name = "repo_search_symbols",
            description = "بحث fuzzy بالاسم في الرموز المُفهرسة (classes, functions, properties...). " +
                "أسرع وأخف من grep — يستخدم الفهرس المحلي مباشرة.",
            parameters = listOf(
                ToolParameter("query", "string", "اسم أو جزء منه للبحث (e.g. Foo, parseToken)"),
                ToolParameter("scope_path", "string", "scope (افتراضي scope الجلسة)", required = false),
                ToolParameter("limit", "integer", "عدد النتائج (1-50، الافتراضي 30)", required = false)
            )
        ),
        ToolDefinition(
            name = "repo_symbols_by_kind",
            description = "استرجاع كل الرموز من نوع معين (class/function/interface/property...). " +
                "مفيد لاستكشاف بنية المشروع.",
            parameters = listOf(
                ToolParameter("kind", "string", "النوع: class, function, interface, object, enum, property, type"),
                ToolParameter("scope_path", "string", "scope (افتراضي scope الجلسة)", required = false),
                ToolParameter("limit", "integer", "عدد النتائج (1-100، الافتراضي 50)", required = false)
            )
        ),
        ToolDefinition(
            name = "repo_file_symbols",
            description = "قائمة الرموز في ملف بعينه (مفيد لفهم سريع لمحتوى الملف).",
            parameters = listOf(
                ToolParameter("file_path", "string", "المسار المطلق للملف"),
                ToolParameter("scope_path", "string", "scope (افتراضي scope الجلسة)", required = false)
            )
        ),
        ToolDefinition(
            name = "repo_stats",
            description = "إحصاءات المشروع المُفهرس: عدد الملفات والرموز، توزيع اللغات.",
            parameters = listOf(
                ToolParameter("scope_path", "string", "scope (افتراضي scope الجلسة)", required = false)
            )
        )
    )

    /** يُرجع null إذا الأداة ليست مملوكة لهذا الـ wrapper (لتمرير fall-through). */
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        val scope = args["scope_path"]?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult("scope_path مطلوب لتنفيذ '$name'.", isError = true)

        return try {
            when (name) {
                "repo_index_scope" -> {
                    val res = engine.indexScope(scope)
                    ToolExecutionResult(buildString {
                        appendLine("📚 فهرسة $scope مكتملة (${res.elapsedMs}ms):")
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
                        ?: return ToolExecutionResult("query مطلوب", isError = true)
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 30
                    val results = engine.searchSymbols(scope, q, limit)
                    if (results.isEmpty()) ToolExecutionResult("لم يُعثر على رموز تطابق '$q'.")
                    else ToolExecutionResult(formatSymbols(results, "🔍 نتائج '$q' (${results.size}):"))
                }
                "repo_symbols_by_kind" -> {
                    val kind = args["kind"]?.trim()?.lowercase()
                        ?: return ToolExecutionResult("kind مطلوب", isError = true)
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50
                    val results = engine.symbolsByKind(scope, kind, limit)
                    if (results.isEmpty()) ToolExecutionResult("لا توجد رموز من النوع '$kind'.")
                    else ToolExecutionResult(formatSymbols(results, "📋 رموز نوع $kind (${results.size}):"))
                }
                "repo_file_symbols" -> {
                    val fp = args["file_path"]?.trim()
                        ?: return ToolExecutionResult("file_path مطلوب", isError = true)
                    val results = engine.fileSymbols(scope, fp)
                    if (results.isEmpty()) ToolExecutionResult("لا توجد رموز في $fp.")
                    else ToolExecutionResult(formatSymbols(results, "📄 رموز $fp:"))
                }
                "repo_stats" -> {
                    val s = engine.getStats(scope)
                    ToolExecutionResult(buildString {
                        appendLine("📊 إحصاءات $scope:")
                        appendLine("  ملفات: ${s.fileCount}")
                        appendLine("  رموز: ${s.symbolCount}")
                        if (s.languages.isNotEmpty()) {
                            appendLine("  لغات:")
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
