package com.omnidev.workspace.data.repo

import com.omnidev.workspace.data.db.dao.RepoIndexDao
import com.omnidev.workspace.data.db.entities.RepoSymbolEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * RepoContextEngine — واجهة استعلام Live Repository Context (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * هذا هو الواجهة التي يتعامل معها الـ Agent (عبر AgentBrainTools/RepoContextTools)
 * لاستعلام الفهرس بشكل سريع. كل العمليات SQL-only، بدون تحميل blobs ضخمة في
 * الذاكرة.
 *
 * **Mobile-first**:
 * - LIKE-based fuzzy search (يكفي + سريع، لا حاجة FTS5)
 * - استعلامات مُفهرسة (idx_sym_*)
 * - النتائج محدودة (50 رمز كحد أقصى/استعلام) لتجنب OOM
 */
class RepoContextEngine(
    private val dao: RepoIndexDao,
    private val indexer: RepoIndexer
) {

    // ──────────────────────────────────────────────────────────────────
    // Indexing operations (proxied)
    // ──────────────────────────────────────────────────────────────────

    suspend fun indexScope(
        scopePath: String,
        onProgress: ((RepoIndexer.IndexProgress) -> Unit)? = null
    ): RepoIndexer.IndexProgress = indexer.indexScope(scopePath, onProgress)

    suspend fun reindexFile(scopePath: String, filePath: String) =
        indexer.reindexFile(scopePath, filePath)

    suspend fun clearScope(scopePath: String) = indexer.clearScope(scopePath)

    // ──────────────────────────────────────────────────────────────────
    // Queries
    // ──────────────────────────────────────────────────────────────────

    /** Fuzzy search عام بالاسم/qualified name. */
    suspend fun searchSymbols(
        scopePath: String,
        query: String,
        limit: Int = 30
    ): List<RepoSymbolEntry> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val safe = query.replace("%", "").replace("_", "")
        dao.fuzzySearch(scopePath, "%$safe%", safe, limit.coerceAtMost(50))
    }

    /** ابحث برمز محدد (e.g. "function" أو "class"). */
    suspend fun symbolsByKind(
        scopePath: String,
        kind: String,
        limit: Int = 50
    ): List<RepoSymbolEntry> = withContext(Dispatchers.IO) {
        dao.findByKind(scopePath, kind, limit.coerceAtMost(100))
    }

    /** كل الرموز في ملف (للملخص السريع). */
    suspend fun fileSymbols(scopePath: String, filePath: String): List<RepoSymbolEntry> =
        withContext(Dispatchers.IO) { dao.getFileSymbols(scopePath, filePath) }

    /** بحث دقيق بالـ qualified name (e.g. com.example.Foo.bar). */
    suspend fun findByQualifiedName(
        scopePath: String,
        qname: String
    ): List<RepoSymbolEntry> = withContext(Dispatchers.IO) {
        dao.findByQualified(scopePath, qname)
    }

    // ──────────────────────────────────────────────────────────────────
    // Stats — للحقن في system prompt
    // ──────────────────────────────────────────────────────────────────

    data class ScopeStats(
        val fileCount: Int,
        val symbolCount: Int,
        val languages: List<Pair<String, Int>>
    )

    suspend fun getStats(scopePath: String): ScopeStats = withContext(Dispatchers.IO) {
        ScopeStats(
            fileCount = dao.countFiles(scopePath),
            symbolCount = dao.countSymbols(scopePath),
            languages = dao.getLanguageBreakdown(scopePath).map { it.language to it.cnt }
        )
    }

    /** يبني نص قصير عن المشروع للحقن في system prompt. */
    suspend fun buildContextSummary(scopePath: String, maxChars: Int = 400): String =
        withContext(Dispatchers.IO) {
            val stats = getStats(scopePath)
            if (stats.fileCount == 0) return@withContext ""
            buildString {
                appendLine("\n📂 Live Repo Context: $scopePath")
                appendLine("الملفات: ${stats.fileCount} | الرموز: ${stats.symbolCount}")
                if (stats.languages.isNotEmpty()) {
                    val top = stats.languages.take(5)
                        .joinToString(", ") { "${it.first}(${it.second})" }
                    appendLine("اللغات: $top")
                }
            }.take(maxChars)
        }
}
