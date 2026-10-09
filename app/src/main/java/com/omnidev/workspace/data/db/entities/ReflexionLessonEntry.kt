package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** ReflexionLessonEntry stores a lesson from an earlier agent run, bounded to 280 characters for prompt injection. Uses 256-float hash-based embeddings without loading a model, up to 2000 lessons and quality-based eviction. errorSignature is a 16-hex MD5 after path/number normalization; successContext distinguishes lessons from successful runs and failures; quality rises on successful reuse and falls on failure. */
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
