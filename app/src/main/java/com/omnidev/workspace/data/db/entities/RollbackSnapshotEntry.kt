package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * RollbackSnapshotEntry — Context note "Context note Context note" Context note Context note Context note Context note Context note.
 *
 * Mobile-first design (Context note Context note root):
 * - Context note ≤ 4 KB → Context note Context note Context note Context note (Deflate)
 * - Context note > 4 KB → Context note unified diff Context note (Context note 70%+ Context note Context note)
 * - Context note Context note 200 snapshot Context note actionGroupContext note LRU eviction
 * - Context note Context note 50 MB Context note Context note
 *
 * @property actionGroupId Context note Context note Context note Context note Context note (Context note rollback Context note)
 * @property contentBlob Context note diff Context note Context note Context note Context note Context note Context note (Deflate)
 * @property storedAsDiff true = diffContext note false = full content
 * @property pinned snapshot Context note Context note Context note Context note LRU
 */
@Entity(
    tableName = "rollback_snapshots",
    indices = [
        Index(value = ["actionGroupId"]),
        Index(value = ["filePath"]),
        Index(value = ["createdAt"]),
        Index(value = ["rolledBack"])
    ]
)
data class RollbackSnapshotEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val actionGroupId: String,
    val toolName: String,
    val filePath: String,
    val existedBefore: Boolean,
    val contentBlob: ByteArray,
    val storedAsDiff: Boolean,
    val originalSizeBytes: Long,
    val originalHash: String = "",
    val postEditHash: String = "",
    val reason: String = "",
    val rolledBack: Boolean = false,
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RollbackSnapshotEntry) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}
