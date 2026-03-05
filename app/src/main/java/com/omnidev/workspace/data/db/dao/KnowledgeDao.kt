package com.omnidev.workspace.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.omnidev.workspace.data.db.entities.KnowledgeSnippet
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeDao {

    @Insert
    suspend fun insert(snippet: KnowledgeSnippet): Long

    @Update
    suspend fun update(snippet: KnowledgeSnippet)

    /** Full-text-style search across content and tags using LIKE. */
    @Query(
        "SELECT * FROM knowledge_snippets " +
        "WHERE content LIKE '%' || :query || '%' " +
        "OR tags LIKE '%' || :query || '%' " +
        "ORDER BY createdAt DESC LIMIT 10"
    )
    suspend fun search(query: String): List<KnowledgeSnippet>

    /** Load all snippets for a given category (used for context hydration). */
    @Query("SELECT * FROM knowledge_snippets WHERE category = :category ORDER BY createdAt DESC")
    suspend fun findByCategory(category: String): List<KnowledgeSnippet>

    /** Observe all snippets ordered newest-first (for a management UI). */
    @Query("SELECT * FROM knowledge_snippets ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<KnowledgeSnippet>>

    @Query("DELETE FROM knowledge_snippets WHERE id = :id")
    suspend fun deleteById(id: Long)
}
