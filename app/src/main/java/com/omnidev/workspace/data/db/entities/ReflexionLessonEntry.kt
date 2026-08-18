package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ReflexionLessonEntry — Context note Context note Context note Context note Agent Context note (Brain 2.0).
 *
 * Mobile-first design:
 * - Context note ≤ 280 Context note Context note Context note Context note Context note Context note system prompt Context note Context note
 * - Context note embedding 256 floats (≈ 1 KB) — hash-basedContext note Context note Context note Context note Context note
 * - 2000 Context note Context note Context note ≈ 2 MB Context note
 * - LRU eviction Context note Context note Context note Context note Context note
 *
 * @property errorSignature MD5 (16 hex) Context note Context note Context note Context note Context note/Context note
 *           — Context note Context note Context note Context note Context note Context note Context note Context note
 * @property successContext true Context note Context note Context note Context note Context note Context note/Context note false Context note Context note Context note
 * @property quality Context note Context note 0..1 Context note Context note Context note Context note Context note Context note Context note Context note
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
