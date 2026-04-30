package com.omnidev.workspace.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.omnidev.workspace.data.db.entities.BuildDiagnosticEntry
import kotlinx.coroutines.flow.Flow

/**
 * DAO لقاعدة معرفة Build Doctor Pro. يتعرف على الأخطاء المتكررة عبر
 * fingerprint ويعيد استخدام الحلول الناجحة سابقاً.
 */
@Dao
interface BuildDiagnosticDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: BuildDiagnosticEntry): Long

    @Update
    suspend fun update(entry: BuildDiagnosticEntry)

    @Query("SELECT * FROM build_diagnostics WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): BuildDiagnosticEntry?

    /** البحث الأساسي: عبر fingerprint للكشف عن نفس الخطأ المتكرر. */
    @Query("SELECT * FROM build_diagnostics WHERE errorFingerprint = :fingerprint LIMIT 1")
    suspend fun findByFingerprint(fingerprint: String): BuildDiagnosticEntry?

    @Query("""
        SELECT * FROM build_diagnostics
        WHERE category = :category
        ORDER BY successfulFixCount DESC, occurrenceCount DESC
        LIMIT :limit
    """)
    suspend fun findByCategory(category: String, limit: Int = 20): List<BuildDiagnosticEntry>

    /** الحلول التي نجحت أكثر من مرة (لاقتراحها بثقة). */
    @Query("""
        SELECT * FROM build_diagnostics
        WHERE successfulFixCount >= 1 AND LENGTH(solutionDiff) > 0
        ORDER BY successfulFixCount DESC, lastFixedAt DESC
        LIMIT :limit
    """)
    suspend fun getKnownSolutions(limit: Int = 30): List<BuildDiagnosticEntry>

    @Query("""
        UPDATE build_diagnostics
        SET occurrenceCount = occurrenceCount + 1, lastSeenAt = :now
        WHERE id = :id
    """)
    suspend fun recordOccurrence(id: Long, now: Long)

    @Query("""
        UPDATE build_diagnostics
        SET successfulFixCount = successfulFixCount + 1, lastFixedAt = :now
        WHERE id = :id
    """)
    suspend fun recordSuccessfulFix(id: Long, now: Long)

    @Query("UPDATE build_diagnostics SET failedFixCount = failedFixCount + 1 WHERE id = :id")
    suspend fun recordFailedFix(id: Long)

    @Query("SELECT COUNT(*) FROM build_diagnostics")
    suspend fun count(): Int

    /** LRU eviction: حذف الأضعف (الأقدم وأقل نجاحاً). */
    @Query("""
        DELETE FROM build_diagnostics
        WHERE id IN (
            SELECT id FROM build_diagnostics
            ORDER BY successfulFixCount ASC, occurrenceCount ASC, lastSeenAt ASC
            LIMIT :limit
        )
    """)
    suspend fun evictWeakest(limit: Int)

    @Query("SELECT * FROM build_diagnostics ORDER BY lastSeenAt DESC LIMIT 100")
    fun observeRecent(): Flow<List<BuildDiagnosticEntry>>

    @Query("DELETE FROM build_diagnostics WHERE id = :id")
    suspend fun deleteById(id: Long)
}
