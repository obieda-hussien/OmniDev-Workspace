package com.omnidev.workspace.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.omnidev.workspace.data.db.entities.RepoFileIndexEntry
import com.omnidev.workspace.data.db.entities.RepoSymbolEntry

/**
 * DAO System awareness note System awareness note Live Repository Context Engine — System awareness note System awareness note System awareness note System awareness note.
 * System awareness note System awareness note scope-aware System awareness note System awareness note System awareness note System awareness note.
 */
@Dao
interface RepoIndexDao {

    // ─── File index ────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFile(entry: RepoFileIndexEntry): Long

    @Query("SELECT * FROM repo_file_index WHERE scopePath = :scope AND filePath = :path LIMIT 1")
    suspend fun getFile(scope: String, path: String): RepoFileIndexEntry?

    @Query("SELECT * FROM repo_file_index WHERE scopePath = :scope ORDER BY filePath ASC")
    suspend fun getAllFiles(scope: String): List<RepoFileIndexEntry>

    @Query("SELECT COUNT(*) FROM repo_file_index WHERE scopePath = :scope")
    suspend fun countFiles(scope: String): Int

    @Query("""
        SELECT language, COUNT(*) AS cnt
        FROM repo_file_index
        WHERE scopePath = :scope AND language != ''
        GROUP BY language
        ORDER BY cnt DESC
    """)
    suspend fun getLanguageBreakdown(scope: String): List<LanguageCount>

    @Query("DELETE FROM repo_file_index WHERE scopePath = :scope AND filePath = :path")
    suspend fun deleteFile(scope: String, path: String)

    @Query("DELETE FROM repo_file_index WHERE scopePath = :scope")
    suspend fun clearScope(scope: String)

    // ─── Symbols ────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSymbols(symbols: List<RepoSymbolEntry>): List<Long>

    @Query("DELETE FROM repo_symbols WHERE scopePath = :scope AND filePath = :path")
    suspend fun deleteSymbolsForFile(scope: String, path: String)

    @Query("DELETE FROM repo_symbols WHERE scopePath = :scope")
    suspend fun clearSymbolsForScope(scope: String)

    /** Fuzzy LIKE-based search (System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note FTS). */
    @Query("""
        SELECT * FROM repo_symbols
        WHERE scopePath = :scope
          AND (symbolName LIKE :fragment OR qualifiedName LIKE :fragment)
        ORDER BY
          CASE WHEN symbolName LIKE :exact THEN 0 ELSE 1 END,
          symbolName ASC
        LIMIT :limit
    """)
    suspend fun fuzzySearch(
        scope: String,
        fragment: String,
        exact: String,
        limit: Int = 50
    ): List<RepoSymbolEntry>

    @Query("""
        SELECT * FROM repo_symbols
        WHERE scopePath = :scope AND symbolKind = :kind
        ORDER BY symbolName ASC
        LIMIT :limit
    """)
    suspend fun findByKind(scope: String, kind: String, limit: Int = 100): List<RepoSymbolEntry>

    @Query("""
        SELECT * FROM repo_symbols
        WHERE scopePath = :scope AND filePath = :path
        ORDER BY lineNumber ASC
    """)
    suspend fun getFileSymbols(scope: String, path: String): List<RepoSymbolEntry>

    @Query("""
        SELECT * FROM repo_symbols
        WHERE scopePath = :scope AND qualifiedName = :qname
        LIMIT :limit
    """)
    suspend fun findByQualified(
        scope: String,
        qname: String,
        limit: Int = 5
    ): List<RepoSymbolEntry>

    @Query("SELECT COUNT(*) FROM repo_symbols WHERE scopePath = :scope")
    suspend fun countSymbols(scope: String): Int

    /** LRU eviction System awareness note System awareness note — System awareness note System awareness note 5000 System awareness note/scope. */
    @Query("""
        DELETE FROM repo_symbols
        WHERE id IN (
            SELECT id FROM repo_symbols
            WHERE scopePath = :scope
            ORDER BY indexedAt ASC
            LIMIT :limit
        )
    """)
    suspend fun evictOldestSymbols(scope: String, limit: Int)

    data class LanguageCount(val language: String, val cnt: Int)
}
