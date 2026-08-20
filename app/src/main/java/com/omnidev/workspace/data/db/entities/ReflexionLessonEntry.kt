package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ReflexionLessonEntry — System awareness note System awareness note System awareness note System awareness note Agent System awareness note (Brain 2.0).
 *
 * Mobile-first design:
 * - System awareness note ≤ 280 System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note system prompt System awareness note System awareness note
 * - System awareness note embedding 256 floats (≈ 1 KB) — hash-basedSystem awareness note System awareness note System awareness note System awareness note System awareness note
 * - 2000 System awareness note System awareness note System awareness note ≈ 2 MB System awareness note
 * - LRU eviction System awareness note System awareness note System awareness note System awareness note System awareness note
 *
 * @property errorSignature MD5 (16 hex) System awareness note System awareness note System awareness note System awareness note System awareness note/System awareness note
 *           — System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note
 * @property successContext true System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note/System awareness note false System awareness note System awareness note System awareness note
 * @property quality System awareness note System awareness note 0..1 System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note
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
