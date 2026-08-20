package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * RollbackSnapshotEntry — System awareness note "System awareness note System awareness note" System awareness note System awareness note System awareness note System awareness note System awareness note.
 *
 * Mobile-first design (System awareness note System awareness note root):
 * - System awareness note ≤ 4 KB → System awareness note System awareness note System awareness note System awareness note (Deflate)
 * - System awareness note > 4 KB → System awareness note unified diff System awareness note (System awareness note 70%+ System awareness note System awareness note)
 * - System awareness note System awareness note 200 snapshot System awareness note actionGroupSystem awareness note LRU eviction
 * - System awareness note System awareness note 50 MB System awareness note System awareness note
 *
 * @property actionGroupId System awareness note System awareness note System awareness note System awareness note System awareness note (System awareness note rollback System awareness note)
 * @property contentBlob System awareness note diff System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note (Deflate)
 * @property storedAsDiff true = diffSystem awareness note false = full content
 * @property pinned snapshot System awareness note System awareness note System awareness note System awareness note LRU
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
