package com.omnidev.workspace.data.db.dao

import androidx.room.*
import com.omnidev.workspace.data.db.entities.SystemKnowledgeEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface SystemKnowledgeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SystemKnowledgeEntry): Long

    @Update
    suspend fun update(entry: SystemKnowledgeEntry)

    @Query("SELECT * FROM system_knowledge WHERE category = :category AND key = :key LIMIT 1")
    suspend fun getEntryByCategoryAndKey(category: String, key: String): SystemKnowledgeEntry?

    @Query("SELECT * FROM system_knowledge ORDER BY timestamp DESC")
    fun getAllKnowledgeFlow(): Flow<List<SystemKnowledgeEntry>>

    @Query("SELECT * FROM system_knowledge WHERE category = :category")
    suspend fun getByCategory(category: String): List<SystemKnowledgeEntry>

    @Query("SELECT COUNT(*) FROM system_knowledge")
    suspend fun getKnowledgeCount(): Int

    @Transaction
    suspend fun upsertKnowledge(category: String, key: String, content: String, confidence: Float = 1.0f) {
        val existing = getEntryByCategoryAndKey(category, key)
        if (existing != null) {
            update(existing.copy(
                content = content,
                confidence = confidence,
                timestamp = System.currentTimeMillis()
            ))
        } else {
            insert(SystemKnowledgeEntry(
                category = category,
                key = key,
                content = content,
                confidence = confidence
            ))
        }
    }

    @Query("""
        DELETE FROM system_knowledge
        WHERE id NOT IN (
            SELECT MAX(id)
            FROM system_knowledge
            GROUP BY category, key
        )
    """)
    suspend fun purgeDuplicates(): Int

    @Query("DELETE FROM system_knowledge")
    suspend fun clearAll()

    @Query("SELECT * FROM system_knowledge WHERE isValid = 1 ORDER BY timestamp DESC")
    suspend fun getAllValid(): List<SystemKnowledgeEntry>

    @Query("SELECT * FROM system_knowledge WHERE (category = :type OR knowledgeType = :type) AND isValid = 1 ORDER BY confidence DESC")
    suspend fun getByType(type: String): List<SystemKnowledgeEntry>

    @Query("SELECT * FROM system_knowledge WHERE (key = :subject OR subject = :subject) AND isValid = 1")
    suspend fun getBySubject(subject: String): List<SystemKnowledgeEntry>

    @Query("SELECT * FROM system_knowledge WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SystemKnowledgeEntry?

    @Query("""
        SELECT * FROM system_knowledge 
        WHERE key LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%'
        ORDER BY confidence DESC
        LIMIT :limit
    """)
    suspend fun search(query: String, limit: Int = 20): List<SystemKnowledgeEntry>

    @Query("""
        SELECT * FROM system_knowledge 
        WHERE (injectionPriority <= :maxPriority OR 1=1)
        ORDER BY confidence DESC
        LIMIT :limit
    """)
    suspend fun getForSystemPrompt(maxPriority: Int = 3, limit: Int = 15): List<SystemKnowledgeEntry>

    @Query("DELETE FROM system_knowledge WHERE key = :subject AND category = :type")
    suspend fun invalidate(subject: String, type: String)

    @Query("DELETE FROM system_knowledge WHERE id = :id")
    suspend fun invalidateById(id: Long, )

    @Query("UPDATE system_knowledge SET timestamp = :id WHERE id = :id")
    suspend fun incrementVerification(id: Long, )

    @Query("UPDATE system_knowledge SET confidence = :confidence WHERE id = :id")
    suspend fun updateConfidence(id: Long, confidence: Float, )

    @Query("SELECT COUNT(*) FROM system_knowledge")
    suspend fun getCount(): Int

    @Query("SELECT * FROM system_knowledge ORDER BY timestamp DESC LIMIT 5")
    fun observeRecent(): Flow<List<SystemKnowledgeEntry>>

    @Query("SELECT * FROM system_knowledge ORDER BY timestamp DESC LIMIT :limit")
    fun observeAllValid(limit: Int = 300): Flow<List<SystemKnowledgeEntry>>

    @Query("DELETE FROM system_knowledge WHERE timestamp < :before")
    suspend fun cleanupInvalid(before: Long)
}
