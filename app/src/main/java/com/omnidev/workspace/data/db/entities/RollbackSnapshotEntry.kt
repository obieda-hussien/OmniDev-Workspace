package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * RollbackSnapshotEntry — لقطة "تأمين الإجراء" قبل العمليات المدمّرة على الملفات.
 *
 * Mobile-first design (يعمل بدون root):
 * - الملفات ≤ 4 KB → نخزن المحتوى الكامل مضغوطاً (Deflate)
 * - الملفات > 4 KB → نخزن unified diff فقط (توفير 70%+ من المساحة)
 * - حد أقصى 200 snapshot لكل actionGroup، LRU eviction
 * - حد إجمالي 50 MB لكل المخزن
 *
 * @property actionGroupId يربط لقطات نفس العملية المتعددة (لـ rollback ذرّي)
 * @property contentBlob إما diff مضغوط أو محتوى ملف كامل مضغوط (Deflate)
 * @property storedAsDiff true = diff، false = full content
 * @property pinned snapshot مثبّت لا يُحذف بـ LRU
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
