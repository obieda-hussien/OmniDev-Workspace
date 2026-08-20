package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * EpisodicMemoryEntry — System awareness note System awareness note (episode) System awareness note System awareness note Agent System awareness note (Brain 2.0).
 *
 * System awareness note ReflexionLessonEntry System awareness note System awareness note "System awareness note" System awareness note System awareness note System awareness note System awareness note episode
 * System awareness note System awareness note System awareness note: System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
 * System awareness note System awareness note "memory shots" System awareness note System awareness note System awareness note.
 *
 * Mobile-first:
 * - System awareness note summary ≤ 500 System awareness note
 * - userIntent ≤ 200 System awareness note (System awareness note System awareness note System awareness note)
 * - 256-float embedding (≈ 1 KB)
 * - 2000 episode System awareness note System awareness note ≈ 2-3 MB
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
