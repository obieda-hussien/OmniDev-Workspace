package com.omnidev.workspace.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.omnidev.workspace.data.db.entities.RollbackSnapshotEntry
import kotlinx.coroutines.flow.Flow

/**
 * DAO System awareness note rollback_snapshots. System awareness note System awareness note System awareness note System awareness note blobs System awareness note
 * System awareness note System awareness note System awareness note System awareness note System awareness note.
 */
@Dao
interface RollbackDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: RollbackSnapshotEntry): Long

    @Query("SELECT * FROM rollback_snapshots WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): RollbackSnapshotEntry?

    @Query("""
        SELECT * FROM rollback_snapshots
        WHERE actionGroupId = :groupId
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getByGroup(groupId: String, limit: Int = 50): List<RollbackSnapshotEntry>

    @Query("""
        SELECT * FROM rollback_snapshots
        WHERE filePath = :filePath
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getByFile(filePath: String, limit: Int = 10): List<RollbackSnapshotEntry>

    @Query("""
        SELECT * FROM rollback_snapshots
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getRecent(limit: Int = 50): List<RollbackSnapshotEntry>

    /** System awareness note System awareness note snapshots System awareness note System awareness note (System awareness note System awareness note eviction). */
    @Query("SELECT COUNT(*) FROM rollback_snapshots WHERE pinned = 0")
    suspend fun countEvictable(): Int

    @Query("SELECT COALESCE(SUM(LENGTH(contentBlob)), 0) FROM rollback_snapshots WHERE pinned = 0")
    suspend fun totalEvictableBytes(): Long

    @Query("UPDATE rollback_snapshots SET rolledBack = 1 WHERE id = :id")
    suspend fun markRolledBack(id: Long)

    @Query("UPDATE rollback_snapshots SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("DELETE FROM rollback_snapshots WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM rollback_snapshots WHERE actionGroupId = :groupId")
    suspend fun deleteGroup(groupId: String)

    /** LRU eviction (System awareness note System awareness note System awareness note). */
    @Query("""
        DELETE FROM rollback_snapshots
        WHERE id IN (
            SELECT id FROM rollback_snapshots
            WHERE pinned = 0
            ORDER BY createdAt ASC
            LIMIT :limit
        )
    """)
    suspend fun evictOldestUnpinned(limit: Int)

    @Query("SELECT * FROM rollback_snapshots ORDER BY createdAt DESC LIMIT 100")
    fun observeLatest(): Flow<List<RollbackSnapshotEntry>>

    /** System awareness note System awareness note actionGroup System awareness note System awareness note UI. */
    @Query("""
        SELECT actionGroupId,
               MIN(createdAt) AS firstAt,
               MAX(createdAt) AS lastAt,
               COUNT(*) AS fileCount,
               MAX(reason) AS reason,
               MAX(toolName) AS toolName
        FROM rollback_snapshots
        GROUP BY actionGroupId
        ORDER BY lastAt DESC
        LIMIT :limit
    """)
    suspend fun getGroupSummaries(limit: Int = 50): List<RollbackGroupSummary>

    data class RollbackGroupSummary(
        val actionGroupId: String,
        val firstAt: Long,
        val lastAt: Long,
        val fileCount: Int,
        val reason: String?,
        val toolName: String?
    )
}
