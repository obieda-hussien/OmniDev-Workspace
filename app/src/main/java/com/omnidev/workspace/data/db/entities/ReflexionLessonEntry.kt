package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ReflexionLessonEntry — درس مستفاد من تجربة Agent سابقة (Brain 2.0).
 *
 * Mobile-first design:
 * - الدرس ≤ 280 حرف ليبقى صالحاً للحقن في الـ system prompt دون تضخمه
 * - الـ embedding 256 floats (≈ 1 KB) — hash-based، بدون تحميل أي نموذج
 * - 2000 درس كحد أقصى ≈ 2 MB إجمالي
 * - LRU eviction حسب الجودة عند تجاوز الحد
 *
 * @property errorSignature MD5 (16 hex) لرسالة الخطأ بعد تطبيع المسارات/الأرقام
 *           — يستخدم لكشف التكرار وتجميع نفس النوع من الفشل
 * @property successContext true لو الدرس مستخلص من نجاح بطيء/ضخم، false لو من فشل
 * @property quality قيمة بين 0..1 تتحسن مع كل استخدام ناجح وتتراجع مع الفشل
 */
@Entity(
    tableName = "reflexion_lessons",
    indices = [
        Index(value = ["toolName"]),
        Index(value = ["errorSignature"]),
        Index(value = ["lastUsedAt"]),
        Index(value = ["quality"])
    ]
)
data class ReflexionLessonEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val toolName: String,
    val lesson: String,
    val errorSignature: String = "",
    /** 256-float vector serialized as little-endian bytes (≈ 1 KB). */
    val embedding: ByteArray,
    val successContext: Boolean = false,
    val useCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
    val quality: Float = 0.5f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReflexionLessonEntry) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}
