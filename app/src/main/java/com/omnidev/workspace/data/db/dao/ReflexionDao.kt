package com.omnidev.workspace.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.omnidev.workspace.data.db.entities.ReflexionLessonEntry
import kotlinx.coroutines.flow.Flow

/**
 * DAO Context note ReflexionLessonEntry. Context note Context note Android Context note:
 * - candidates pre-filtering Context note SQL (Context note Context note)
 * - cosine ranking Context note JVM Context note ≤ 100 Context note
 * - Context note atomic Context note Context note Context note blob
 */
@Dao
interface ReflexionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ReflexionLessonEntry): Long

    @Update
    suspend fun update(entry: ReflexionLessonEntry)

    @Query("SELECT * FROM reflexion_lessons WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ReflexionLessonEntry?

    /** Top-K Context note Context note Context note-Context note-Context note (Context note general retrieval). */
    @Query("""
        SELECT * FROM reflexion_lessons
        ORDER BY quality DESC, useCount DESC, createdAt DESC
        LIMIT :limit
    """)
    suspend fun getTopCandidates(limit: Int = 60): List<ReflexionLessonEntry>

    /** Context note Context note Context note Context note. */
    @Query("""
        SELECT * FROM reflexion_lessons
        WHERE toolName = :toolName
        ORDER BY quality DESC, lastUsedAt DESC
        LIMIT :limit
    """)
    suspend fun getByTool(toolName: String, limit: Int = 30): List<ReflexionLessonEntry>

    /** Context note Context note duplicates (Context note Context note Context note). */
    @Query("""
        SELECT * FROM reflexion_lessons
        WHERE errorSignature = :signature
        ORDER BY quality DESC
        LIMIT :limit
    """)
    suspend fun getBySignature(signature: String, limit: Int = 1): List<ReflexionLessonEntry>

    /** Context note Context note + Context note Context note atomic (Context note Context note Context note Context note Context note). */
    @Query("""
        UPDATE reflexion_lessons
        SET useCount = useCount + 1,
            lastUsedAt = :now,
            quality = MIN(1.0, quality + :qualityDelta)
        WHERE id = :id
    """)
    suspend fun recordUsage(id: Long, now: Long, qualityDelta: Float)

    /** Context note Context note Context note Context note Context note Context note Context note. */
    @Query("""
        UPDATE reflexion_lessons
        SET quality = MAX(0.0, quality - :penalty)
        WHERE id = :id
    """)
    suspend fun penalize(id: Long, penalty: Float)

    @Query("SELECT COUNT(*) FROM reflexion_lessons")
    suspend fun count(): Int

    /** LRU eviction: Context note Context note Context note Context note (Context note Context note quota). */
    @Query("""
        DELETE FROM reflexion_lessons
        WHERE id IN (
            SELECT id FROM reflexion_lessons
            ORDER BY quality ASC, useCount ASC, createdAt ASC
            LIMIT :limit
        )
    """)
    suspend fun evictLowestQuality(limit: Int)

    @Query("SELECT * FROM reflexion_lessons ORDER BY lastUsedAt DESC LIMIT 100")
    fun observeRecent(): Flow<List<ReflexionLessonEntry>>

    @Query("DELETE FROM reflexion_lessons WHERE id = :id")
    suspend fun deleteById(id: Long)
}
