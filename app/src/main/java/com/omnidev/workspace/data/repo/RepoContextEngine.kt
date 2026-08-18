package com.omnidev.workspace.data.repo

import com.omnidev.workspace.data.db.dao.RepoIndexDao
import com.omnidev.workspace.data.db.entities.RepoSymbolEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * RepoContextEngine — Context note Context note Live Repository Context (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Context note Context note Context note Context note Context note Context note Context note Agent (Context note AgentBrainTools/RepoContextTools)
 * Context note Context note Context note Context note. Context note Context note SQL-onlyContext note Context note Context note blobs Context note Context note
 * Context note.
 *
 * **Mobile-first**:
 * - LIKE-based fuzzy search (Context note + Context note Context note Context note FTS5)
 * - Context note Context note (idx_sym_*)
 * - Context note Context note (50 Context note Context note Context note/Context note) Context note OOM
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

    /** Fuzzy search Context note Context note/qualified name. */
    suspend fun searchSymbols(
        scopePath: String,
        query: String,
        limit: Int = 30
    ): List<RepoSymbolEntry> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val safe = query.replace("%", "").replace("_", "")
        dao.fuzzySearch(scopePath, "%$safe%", safe, limit.coerceAtMost(50))
    }

    /** Context note Context note Context note (e.g. "function" Context note "class"). */
    suspend fun symbolsByKind(
        scopePath: String,
        kind: String,
        limit: Int = 50
    ): List<RepoSymbolEntry> = withContext(Dispatchers.IO) {
        dao.findByKind(scopePath, kind, limit.coerceAtMost(100))
    }

    /** Context note Context note Context note Context note (Context note Context note). */
    suspend fun fileSymbols(scopePath: String, filePath: String): List<RepoSymbolEntry> =
        withContext(Dispatchers.IO) { dao.getFileSymbols(scopePath, filePath) }

    /** Context note Context note Context note qualified name (e.g. com.example.Foo.bar). */
    suspend fun findByQualifiedName(
        scopePath: String,
        qname: String
    ): List<RepoSymbolEntry> = withContext(Dispatchers.IO) {
        dao.findByQualified(scopePath, qname)
    }

    // ──────────────────────────────────────────────────────────────────
    // Stats — Context note Context note system prompt
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

    /** Context note Context note Context note Context note Context note Context note Context note system prompt. */
    suspend fun buildContextSummary(scopePath: String, maxChars: Int = 400): String =
        withContext(Dispatchers.IO) {
            val stats = getStats(scopePath)
            if (stats.fileCount == 0) return@withContext ""
            buildString {
                appendLine("\n📂 Live Repo Context: $scopePath")
                appendLine("Info: ${stats.fileCount} | Info: ${stats.symbolCount}")
                if (stats.languages.isNotEmpty()) {
                    val top = stats.languages.take(5)
                        .joinToString(", ") { "${it.first}(${it.second})" }
                    appendLine("Info: $top")
                }
            }.take(maxChars)
        }
}
