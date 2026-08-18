package com.omnidev.workspace.data.repo

import com.omnidev.workspace.data.db.dao.RepoIndexDao
import com.omnidev.workspace.data.db.entities.RepoSymbolEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * RepoContextEngine — [Localized] [Localized] Live Repository Context (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] Agent ([Localized] AgentBrainTools/RepoContextTools)
 * [Localized] [Localized] [Localized] [Localized]. [Localized] [Localized] SQL-only[Localized] [Localized] [Localized] blobs [Localized] [Localized]
 * [Localized].
 *
 * **Mobile-first**:
 * - LIKE-based fuzzy search ([Localized] + [Localized] [Localized] [Localized] FTS5)
 * - [Localized] [Localized] (idx_sym_*)
 * - [Localized] [Localized] (50 [Localized] [Localized] [Localized]/[Localized]) [Localized] OOM
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

    /** Fuzzy search [Localized] [Localized]/qualified name. */
    suspend fun searchSymbols(
        scopePath: String,
        query: String,
        limit: Int = 30
    ): List<RepoSymbolEntry> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val safe = query.replace("%", "").replace("_", "")
        dao.fuzzySearch(scopePath, "%$safe%", safe, limit.coerceAtMost(50))
    }

    /** [Localized] [Localized] [Localized] (e.g. "function" [Localized] "class"). */
    suspend fun symbolsByKind(
        scopePath: String,
        kind: String,
        limit: Int = 50
    ): List<RepoSymbolEntry> = withContext(Dispatchers.IO) {
        dao.findByKind(scopePath, kind, limit.coerceAtMost(100))
    }

    /** [Localized] [Localized] [Localized] [Localized] ([Localized] [Localized]). */
    suspend fun fileSymbols(scopePath: String, filePath: String): List<RepoSymbolEntry> =
        withContext(Dispatchers.IO) { dao.getFileSymbols(scopePath, filePath) }

    /** [Localized] [Localized] [Localized] qualified name (e.g. com.example.Foo.bar). */
    suspend fun findByQualifiedName(
        scopePath: String,
        qname: String
    ): List<RepoSymbolEntry> = withContext(Dispatchers.IO) {
        dao.findByQualified(scopePath, qname)
    }

    // ──────────────────────────────────────────────────────────────────
    // Stats — [Localized] [Localized] system prompt
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

    /** [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] system prompt. */
    suspend fun buildContextSummary(scopePath: String, maxChars: Int = 400): String =
        withContext(Dispatchers.IO) {
            val stats = getStats(scopePath)
            if (stats.fileCount == 0) return@withContext ""
            buildString {
                appendLine("\n📂 Live Repo Context: $scopePath")
                appendLine("[Localized]: ${stats.fileCount} | [Localized]: ${stats.symbolCount}")
                if (stats.languages.isNotEmpty()) {
                    val top = stats.languages.take(5)
                        .joinToString(", ") { "${it.first}(${it.second})" }
                    appendLine("[Localized]: $top")
                }
            }.take(maxChars)
        }
}
