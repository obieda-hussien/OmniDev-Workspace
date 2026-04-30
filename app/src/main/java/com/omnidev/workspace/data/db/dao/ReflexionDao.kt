package com.omnidev.workspace.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.omnidev.workspace.data.db.entities.ReflexionLessonEntry
import kotlinx.coroutines.flow.Flow

/**
 * DAO لجدول ReflexionLessonEntry. مُحسَّن لأجهزة Android الضعيفة:
 * - candidates pre-filtering في SQL (سريع، مُفهرس)
 * - cosine ranking في JVM على ≤ 100 صف
 * - تحديثات atomic بدون قراءة الـ blob
 */
@Dao
interface ReflexionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ReflexionLessonEntry): Long

    @Update
    suspend fun update(entry: ReflexionLessonEntry)

    @Query("SELECT * FROM reflexion_lessons WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ReflexionLessonEntry?

    /** Top-K مرتبة حسب جودة-استخدام-وقت (لـ general retrieval). */
    @Query("""
        SELECT * FROM reflexion_lessons
        ORDER BY quality DESC, useCount DESC, createdAt DESC
        LIMIT :limit
    """)
    suspend fun getTopCandidates(limit: Int = 60): List<ReflexionLessonEntry>

    /** دروس مرتبطة بأداة معينة. */
    @Query("""
        SELECT * FROM reflexion_lessons
        WHERE toolName = :toolName
        ORDER BY quality DESC, lastUsedAt DESC
        LIMIT :limit
    """)
    suspend fun getByTool(toolName: String, limit: Int = 30): List<ReflexionLessonEntry>

    /** للكشف عن duplicates (نفس بصمة الخطأ). */
    @Query("""
        SELECT * FROM reflexion_lessons
        WHERE errorSignature = :signature
        ORDER BY quality DESC
        LIMIT :limit
    """)
    suspend fun getBySignature(signature: String, limit: Int = 1): List<ReflexionLessonEntry>

    /** زيادة استخدام + جودة بشكل atomic (عند الحقن أو بعد النجاح). */
    @Query("""
        UPDATE reflexion_lessons
        SET useCount = useCount + 1,
            lastUsedAt = :now,
            quality = MIN(1.0, quality + :qualityDelta)
        WHERE id = :id
    """)
    suspend fun recordUsage(id: Long, now: Long, qualityDelta: Float)

    /** عقوبة عند فشل المهمة بعد حقن الدرس. */
    @Query("""
        UPDATE reflexion_lessons
        SET quality = MAX(0.0, quality - :penalty)
        WHERE id = :id
    """)
    suspend fun penalize(id: Long, penalty: Float)

    @Query("SELECT COUNT(*) FROM reflexion_lessons")
    suspend fun count(): Int

    /** LRU eviction: حذف أقل الدروس جودة (لتطبيق الـ quota). */
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
