package com.omnidev.workspace.data.repo

import com.omnidev.workspace.data.db.dao.RepoIndexDao
import com.omnidev.workspace.data.db.entities.RepoSymbolEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * RepoContextEngine — System awareness note System awareness note Live Repository Context (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note Agent (System awareness note AgentBrainTools/RepoContextTools)
 * System awareness note System awareness note System awareness note System awareness note. System awareness note System awareness note SQL-onlySystem awareness note System awareness note System awareness note blobs System awareness note System awareness note
 * System awareness note.
 *
 * **Mobile-first**:
 * - LIKE-based fuzzy search (System awareness note + System awareness note System awareness note System awareness note FTS5)
 * - System awareness note System awareness note (idx_sym_*)
 * - System awareness note System awareness note (50 System awareness note System awareness note System awareness note/System awareness note) System awareness note OOM
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

    /** Fuzzy search System awareness note System awareness note/qualified name. */
    suspend fun searchSymbols(
        scopePath: String,
        query: String,
        limit: Int = 30
    ): List<RepoSymbolEntry> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val safe = query.replace("%", "").replace("_", "")
        dao.fuzzySearch(scopePath, "%$safe%", safe, limit.coerceAtMost(50))
    }

    /** System awareness note System awareness note System awareness note (e.g. "function" System awareness note "class"). */
    suspend fun symbolsByKind(
        scopePath: String,
        kind: String,
        limit: Int = 50
    ): List<RepoSymbolEntry> = withContext(Dispatchers.IO) {
        dao.findByKind(scopePath, kind, limit.coerceAtMost(100))
    }

    /** System awareness note System awareness note System awareness note System awareness note (System awareness note System awareness note). */
    suspend fun fileSymbols(scopePath: String, filePath: String): List<RepoSymbolEntry> =
        withContext(Dispatchers.IO) { dao.getFileSymbols(scopePath, filePath) }

    /** System awareness note System awareness note System awareness note qualified name (e.g. com.example.Foo.bar). */
    suspend fun findByQualifiedName(
        scopePath: String,
        qname: String
    ): List<RepoSymbolEntry> = withContext(Dispatchers.IO) {
        dao.findByQualified(scopePath, qname)
    }

    // ──────────────────────────────────────────────────────────────────
    // Stats — System awareness note System awareness note system prompt
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

    /** System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note system prompt. */
    suspend fun buildContextSummary(scopePath: String, maxChars: Int = 400): String =
        withContext(Dispatchers.IO) {
            val stats = getStats(scopePath)
            if (stats.fileCount == 0) return@withContext ""
            buildString {
                appendLine("\n📂 Live Repo Context: $scopePath")
                appendLine("System awareness note: ${stats.fileCount} | System awareness note: ${stats.symbolCount}")
                if (stats.languages.isNotEmpty()) {
                    val top = stats.languages.take(5)
                        .joinToString(", ") { "${it.first}(${it.second})" }
                    appendLine("System awareness note: $top")
                }
            }.take(maxChars)
        }
}
