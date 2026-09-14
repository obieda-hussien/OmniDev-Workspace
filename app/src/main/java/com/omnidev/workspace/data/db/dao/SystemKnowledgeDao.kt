package com.omnidev.workspace.data.db.dao

import androidx.room.*
import com.omnidev.workspace.data.db.entities.SystemKnowledgeEntry
import kotlinx.coroutines.flow.Flow

/**
 * SystemKnowledgeDao — واجهة الوصول لقاعدة معرفة النظام
 */
@Dao
interface SystemKnowledgeDao {

    // Serialize read/merge/write across all engine instances, not just one coroutine.
    @Transaction
    suspend fun merge(entry: SystemKnowledgeEntry): Long {
        val existing = getBySubject(entry.subject).firstOrNull {
            it.knowledgeType == entry.knowledgeType && it.source == entry.source &&
                (entry.knowledgeType !in listOf("PATTERN", "TOOL_DEPENDENCY") || it.content == entry.content)
        }
        if (existing == null) return insert(entry)
        if (existing.content != entry.content || existing.confidence != entry.confidence ||
            existing.injectionPriority != entry.injectionPriority || existing.searchTags != entry.searchTags) {
            update(entry.copy(id = existing.id, createdAt = existing.createdAt,
                verificationCount = existing.verificationCount))
        }
        return existing.id
    }

    @Query("SELECT knowledgeType, COUNT(*) AS count FROM system_knowledge WHERE isValid = 1 GROUP BY knowledgeType")
    suspend fun countsByType(): List<KnowledgeTypeCount>

    @Query("SELECT * FROM system_knowledge WHERE knowledgeType = :type AND isValid = 1 ORDER BY injectionPriority, confidence DESC, updatedAt DESC LIMIT :limit")
    suspend fun getPromptByType(type: String, limit: Int = 8): List<SystemKnowledgeEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SystemKnowledgeEntry): Long

    @Update
    suspend fun update(entry: SystemKnowledgeEntry)

    @Query("SELECT * FROM system_knowledge WHERE isValid = 1 ORDER BY injectionPriority ASC, confidence DESC")
    suspend fun getAllValid(): List<SystemKnowledgeEntry>

    @Query("SELECT * FROM system_knowledge WHERE knowledgeType = :type AND isValid = 1 ORDER BY confidence DESC")
    suspend fun getByType(type: String): List<SystemKnowledgeEntry>

    @Query("SELECT * FROM system_knowledge WHERE subject = :subject AND isValid = 1")
    suspend fun getBySubject(subject: String): List<SystemKnowledgeEntry>

    @Query("SELECT * FROM system_knowledge WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SystemKnowledgeEntry?

    @Query("""
        SELECT * FROM system_knowledge 
        WHERE isValid = 1 AND (
            subject LIKE '%' || :query || '%' OR 
            content LIKE '%' || :query || '%' OR 
            searchTags LIKE '%' || :query || '%'
        )
        ORDER BY confidence DESC, injectionPriority ASC
        LIMIT :limit
    """)
    suspend fun search(query: String, limit: Int = 20): List<SystemKnowledgeEntry>

    @Query("""
        SELECT * FROM system_knowledge 
        WHERE isValid = 1 AND injectionPriority <= :maxPriority
        ORDER BY injectionPriority ASC, confidence DESC
        LIMIT :limit
    """)
    suspend fun getForSystemPrompt(maxPriority: Int = 3, limit: Int = 15): List<SystemKnowledgeEntry>

    @Query("UPDATE system_knowledge SET isValid = 0 WHERE subject = :subject AND knowledgeType = :type")
    suspend fun invalidate(subject: String, type: String)

    @Query("UPDATE system_knowledge SET isValid = 0, updatedAt = :now WHERE id = :id")
    suspend fun invalidateById(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE system_knowledge SET verificationCount = verificationCount + 1, updatedAt = :now WHERE id = :id")
    suspend fun incrementVerification(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE system_knowledge SET confidence = :confidence, updatedAt = :now WHERE id = :id")
    suspend fun updateConfidence(id: Long, confidence: Float, now: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM system_knowledge WHERE isValid = 1")
    suspend fun getCount(): Int

    @Query("SELECT * FROM system_knowledge ORDER BY updatedAt DESC LIMIT 5")
    fun observeRecent(): Flow<List<SystemKnowledgeEntry>>

    @Query("SELECT * FROM system_knowledge WHERE isValid = 1 ORDER BY updatedAt DESC LIMIT :limit")
    fun observeAllValid(limit: Int = 300): Flow<List<SystemKnowledgeEntry>>

    @Query("DELETE FROM system_knowledge WHERE isValid = 0 AND updatedAt < :before")
    suspend fun cleanupInvalid(before: Long)
}

data class KnowledgeTypeCount(val knowledgeType: String, val count: Int)
