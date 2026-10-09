package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** RollbackSnapshotEntry captures file state before destructive operations without requiring root. Stores Deflate-compressed full content up to 4 KB and diffs above that threshold, with up to 200 snapshots per group and 50 MB total storage. actionGroupId links related changes; contentBlob contains compressed diff or full content; storedAsDiff selects the representation; pinned prevents LRU eviction. */
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
