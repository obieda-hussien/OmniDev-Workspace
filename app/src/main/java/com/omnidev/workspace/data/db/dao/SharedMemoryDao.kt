package com.omnidev.workspace.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.omnidev.workspace.data.db.entities.SharedMemoryRecordEntity

@Dao
interface SharedMemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: SharedMemoryRecordEntity)

    @Query("SELECT * FROM shared_memory_records WHERE recordId = :recordId LIMIT 1")
    suspend fun findById(recordId: String): SharedMemoryRecordEntity?

    @Query(
        "SELECT * FROM shared_memory_records " +
            "WHERE tombstone = 0 AND (:namespace = '' OR namespace = :namespace) " +
            "AND (contentJson LIKE '%' || :query || '%' OR metadataJson LIKE '%' || :query || '%') " +
            "ORDER BY updatedAt DESC LIMIT :limit"
    )
    suspend fun search(query: String, namespace: String = "", limit: Int = 50): List<SharedMemoryRecordEntity>

    @Query(
        "SELECT * FROM shared_memory_records WHERE updatedAt > :sinceEpochMs " +
            "ORDER BY updatedAt ASC LIMIT :limit"
    )
    suspend fun changedSince(sinceEpochMs: Long, limit: Int = 200): List<SharedMemoryRecordEntity>

    @Query(
        "SELECT * FROM shared_memory_records " +
            "WHERE (:namespace = '' OR namespace = :namespace) " +
            "ORDER BY updatedAt DESC LIMIT :limit"
    )
    suspend fun recent(namespace: String = "", limit: Int = 100): List<SharedMemoryRecordEntity>

    @Query("SELECT COUNT(*) FROM shared_memory_records")
    suspend fun count(): Int

    @Query(
        "DELETE FROM shared_memory_records WHERE recordId IN (" +
            "SELECT recordId FROM shared_memory_records ORDER BY updatedAt ASC LIMIT :limit)"
    )
    suspend fun evictOldest(limit: Int)
}
