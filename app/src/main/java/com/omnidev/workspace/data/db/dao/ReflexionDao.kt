package com.omnidev.workspace.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.omnidev.workspace.data.db.entities.ReflexionLessonEntry
import kotlinx.coroutines.flow.Flow

/** DAO for ReflexionLessonEntry. Indexed SQL prefilters candidates, JVM cosine ranking considers up to 100 rows and atomic updates avoid reading blobs. */
@Dao
interface ReflexionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ReflexionLessonEntry): Long

    @Update
    suspend fun update(entry: ReflexionLessonEntry)

    @Query("SELECT * FROM reflexion_lessons WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ReflexionLessonEntry?

    /** Top-K lessons ordered by quality, usage and recency for general retrieval. */
    @Query("""
        SELECT * FROM reflexion_lessons
        ORDER BY quality DESC, useCount DESC, createdAt DESC
        LIMIT :limit
    """)
    suspend fun getTopCandidates(limit: Int = 60): List<ReflexionLessonEntry>

    /** Lessons associated with a specific tool. */
    @Query("""
        SELECT * FROM reflexion_lessons
        WHERE toolName = :toolName
        ORDER BY quality DESC, lastUsedAt DESC
        LIMIT :limit
    """)
    suspend fun getByTool(toolName: String, limit: Int = 30): List<ReflexionLessonEntry>

    /** Detect duplicates by error fingerprint. */
    @Query("""
        SELECT * FROM reflexion_lessons
        WHERE errorSignature = :signature
        ORDER BY quality DESC
        LIMIT :limit
    """)
    suspend fun getBySignature(signature: String, limit: Int = 1): List<ReflexionLessonEntry>

    /** Atomically increase usage and quality after injection or success. */
    @Query("""
        UPDATE reflexion_lessons
        SET useCount = useCount + 1,
            lastUsedAt = :now,
            quality = MIN(1.0, quality + :qualityDelta)
        WHERE id = :id
    """)
    suspend fun recordUsage(id: Long, now: Long, qualityDelta: Float)

    /** Penalize a lesson when a task fails after its injection. */
    @Query("""
        UPDATE reflexion_lessons
        SET quality = MAX(0.0, quality - :penalty)
        WHERE id = :id
    """)
    suspend fun penalize(id: Long, penalty: Float)

    @Query("SELECT COUNT(*) FROM reflexion_lessons")
    suspend fun count(): Int

    /** Evict lower-quality lessons to enforce the quota. */
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
