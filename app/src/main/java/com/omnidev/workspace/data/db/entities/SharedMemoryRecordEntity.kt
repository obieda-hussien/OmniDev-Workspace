package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "shared_memory_records",
    indices = [
        Index(value = ["namespace", "updatedAt"]),
        Index(value = ["sourcePackage", "updatedAt"]),
        Index(value = ["updatedAt"])
    ]
)
data class SharedMemoryRecordEntity(
    @PrimaryKey val recordId: String,
    val namespace: String,
    val kind: String,
    val contentJson: String,
    val metadataJson: String = "{}",
    val sourcePackage: String,
    val revision: Long,
    val updatedAt: Long,
    val tombstone: Boolean = false,
    val checksumSha256: String
)

object SharedMemoryMergePolicy {
    fun shouldAccept(
        currentRevision: Long?,
        currentUpdatedAt: Long?,
        incomingRevision: Long,
        incomingUpdatedAt: Long
    ): Boolean {
        if (currentRevision == null || currentUpdatedAt == null) return true
        return incomingRevision > currentRevision ||
            (incomingRevision == currentRevision && incomingUpdatedAt > currentUpdatedAt)
    }
}
