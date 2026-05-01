package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * EpisodicMemoryEntry — حلقة كاملة (episode) من مهمة Agent سابقة (Brain 2.0).
 *
 * بعكس ReflexionLessonEntry الذي يخزن "درساً" قصيراً مرتبطاً بأداة، الـ episode
 * يلخّص مهمة بأكملها: ما طلبه المستخدم، الأدوات المستخدمة، النتيجة النهائية.
 * يُسترجع لإحضار "memory shots" مشابهة لمهام جديدة.
 *
 * Mobile-first:
 * - الـ summary ≤ 500 حرف
 * - userIntent ≤ 200 حرف (مفتاح البحث الدلالي)
 * - 256-float embedding (≈ 1 KB)
 * - 2000 episode كحد أقصى ≈ 2-3 MB
 */
@Entity(
    tableName = "episodic_memory",
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["finalOutcome"]),
        Index(value = ["createdAt"])
    ]
)
data class EpisodicMemoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val summary: String,
    val userIntent: String,
    /** SUCCESS / FAILURE / ABANDONED */
    val finalOutcome: String,
    /** comma-separated list of tools used in this episode (max 10 stored). */
    val toolsUsedCsv: String,
    val embedding: ByteArray,
    val iterationsCount: Int = 0,
    val totalTimeMs: Long = 0,
    val sessionId: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EpisodicMemoryEntry) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}
