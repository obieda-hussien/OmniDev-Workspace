package com.omnidev.workspace.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.omnidev.workspace.data.db.entities.EpisodicMemoryEntry

/**
 * DAO لـ episodic_memory. كذلك يعتمد two-stage retrieval:
 *   - SQL pre-filter (outcome / recent) لجلب candidates سريعاً
 *   - cosine ranking في JVM (≤ 80 candidate)
 */
@Dao
interface EpisodicMemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: EpisodicMemoryEntry): Long

    @Query("SELECT * FROM episodic_memory WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): EpisodicMemoryEntry?

    @Query("""
        SELECT * FROM episodic_memory
        WHERE finalOutcome = :outcome
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getByOutcome(outcome: String, limit: Int = 60): List<EpisodicMemoryEntry>

    @Query("SELECT * FROM episodic_memory ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 80): List<EpisodicMemoryEntry>

    @Query("""
        SELECT * FROM episodic_memory
        WHERE sessionId = :sessionId
        ORDER BY createdAt DESC
    """)
    suspend fun getBySession(sessionId: String): List<EpisodicMemoryEntry>

    @Query("SELECT COUNT(*) FROM episodic_memory")
    suspend fun count(): Int

    /** LRU eviction للأقدم (حماية ميزانية الـ 2000 episode). */
    @Query("""
        DELETE FROM episodic_memory
        WHERE id IN (
            SELECT id FROM episodic_memory
            ORDER BY createdAt ASC
            LIMIT :limit
        )
    """)
    suspend fun evictOldest(limit: Int)

    @Query("DELETE FROM episodic_memory WHERE id = :id")
    suspend fun deleteById(id: Long)
}
