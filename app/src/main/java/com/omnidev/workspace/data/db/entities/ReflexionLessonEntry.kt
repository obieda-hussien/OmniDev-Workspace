package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ReflexionLessonEntry — [Localized] [Localized] [Localized] [Localized] Agent [Localized] (Brain 2.0).
 *
 * Mobile-first design:
 * - [Localized] ≤ 280 [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] system prompt [Localized] [Localized]
 * - [Localized] embedding 256 floats (≈ 1 KB) — hash-based[Localized] [Localized] [Localized] [Localized] [Localized]
 * - 2000 [Localized] [Localized] [Localized] ≈ 2 MB [Localized]
 * - LRU eviction [Localized] [Localized] [Localized] [Localized] [Localized]
 *
 * @property errorSignature MD5 (16 hex) [Localized] [Localized] [Localized] [Localized] [Localized]/[Localized]
 *           — [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]
 * @property successContext true [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]/[Localized] false [Localized] [Localized] [Localized]
 * @property quality [Localized] [Localized] 0..1 [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]
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
