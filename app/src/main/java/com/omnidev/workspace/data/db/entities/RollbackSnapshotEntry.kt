package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * RollbackSnapshotEntry — [Localized] "[Localized] [Localized]" [Localized] [Localized] [Localized] [Localized] [Localized].
 *
 * Mobile-first design ([Localized] [Localized] root):
 * - [Localized] ≤ 4 KB → [Localized] [Localized] [Localized] [Localized] (Deflate)
 * - [Localized] > 4 KB → [Localized] unified diff [Localized] ([Localized] 70%+ [Localized] [Localized])
 * - [Localized] [Localized] 200 snapshot [Localized] actionGroup[Localized] LRU eviction
 * - [Localized] [Localized] 50 MB [Localized] [Localized]
 *
 * @property actionGroupId [Localized] [Localized] [Localized] [Localized] [Localized] ([Localized] rollback [Localized])
 * @property contentBlob [Localized] diff [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] (Deflate)
 * @property storedAsDiff true = diff[Localized] false = full content
 * @property pinned snapshot [Localized] [Localized] [Localized] [Localized] LRU
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
