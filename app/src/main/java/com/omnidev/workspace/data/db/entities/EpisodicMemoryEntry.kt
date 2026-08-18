package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * EpisodicMemoryEntry — Context note Context note (episode) Context note Context note Agent Context note (Brain 2.0).
 *
 * Context note ReflexionLessonEntry Context note Context note "Context note" Context note Context note Context note Context note episode
 * Context note Context note Context note: Context note Context note Context note Context note Context note Context note Context note.
 * Context note Context note "memory shots" Context note Context note Context note.
 *
 * Mobile-first:
 * - Context note summary ≤ 500 Context note
 * - userIntent ≤ 200 Context note (Context note Context note Context note)
 * - 256-float embedding (≈ 1 KB)
 * - 2000 episode Context note Context note ≈ 2-3 MB
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
