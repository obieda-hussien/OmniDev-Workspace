package com.omnidev.workspace.data.repo

import com.omnidev.workspace.data.db.dao.RepoIndexDao
import com.omnidev.workspace.data.db.entities.RepoSymbolEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** RepoContextEngine exposes the Live Repository Context index through SQL queries without loading large blobs. Uses indexed LIKE-based name search and bounded symbol results for mobile memory limits. */
class RepoContextEngine(
    private val dao: RepoIndexDao,
    private val indexer: RepoIndexer
) {

    /** Bounded local question-to-source retrieval; returns verbatim lines, never inferred answers. */
    suspend fun findContext(scopePath: String, question: String, limit: Int = 6): List<LocalCodeRetriever.Hit> =
        withContext(Dispatchers.IO) {
            val root = File(scopePath).canonicalFile
            if (!root.isDirectory || question.isBlank()) return@withContext emptyList()
            val files = dao.getAllFiles(scopePath)
            val words = Regex("[\\p{L}\\p{N}_]{3,}").findAll(question)
                .map { it.value }.distinct().take(8).toList()
            val symbols = words.flatMap { word ->
                dao.fuzzySearch(scopePath, "%${word.replace("%", "").replace("_", "")}%", word, 12)
            }.groupBy { it.filePath }
            val candidates = files.map { file ->
                LocalCodeRetriever.Candidate(file.filePath,
                    symbols[file.filePath].orEmpty().joinToString(" ") { it.symbolName })
            }
            LocalCodeRetriever.retrieve(question, root, candidates, limit)
        }

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

    /** Fuzzy search by name or qualified name. */
    suspend fun searchSymbols(
        scopePath: String,
        query: String,
        limit: Int = 30
    ): List<RepoSymbolEntry> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val safe = query.replace("%", "").replace("_", "")
        dao.fuzzySearch(scopePath, "%$safe%", safe, limit.coerceAtMost(50))
    }

    /** Search a symbol kind, for example function or class. */
    suspend fun symbolsByKind(
        scopePath: String,
        kind: String,
        limit: Int = 50
    ): List<RepoSymbolEntry> = withContext(Dispatchers.IO) {
        dao.findByKind(scopePath, kind, limit.coerceAtMost(100))
    }

    /** List symbols in a file for a quick overview. */
    suspend fun fileSymbols(scopePath: String, filePath: String): List<RepoSymbolEntry> =
        withContext(Dispatchers.IO) { dao.getFileSymbols(scopePath, filePath) }

    /** Exact qualified-name lookup, for example com.example.Foo.bar. */
    suspend fun findByQualifiedName(
        scopePath: String,
        qname: String
    ): List<RepoSymbolEntry> = withContext(Dispatchers.IO) {
        dao.findByQualified(scopePath, qname)
    }

    // ──────────────────────────────────────────────────────────────────
    // Statistics for system-prompt context.
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

    /** Build concise project context for the system prompt. */
    suspend fun buildContextSummary(scopePath: String, maxChars: Int = 400): String =
        withContext(Dispatchers.IO) {
            val stats = getStats(scopePath)
            if (stats.fileCount == 0) return@withContext ""
            buildString {
                appendLine("\n📂 Live Repo Context: $scopePath")
                appendLine("Files: ${stats.fileCount} | Symbols: ${stats.symbolCount}")
                if (stats.languages.isNotEmpty()) {
                    val top = stats.languages.take(5)
                        .joinToString(", ") { "${it.first}(${it.second})" }
                    appendLine("Languages: $top")
                }
            }.take(maxChars)
        }
}
