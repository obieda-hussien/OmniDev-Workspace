package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * EpisodicMemoryEntry — [Localized] [Localized] (episode) [Localized] [Localized] Agent [Localized] (Brain 2.0).
 *
 * [Localized] ReflexionLessonEntry [Localized] [Localized] "[Localized]" [Localized] [Localized] [Localized] [Localized] episode
 * [Localized] [Localized] [Localized]: [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
 * [Localized] [Localized] "memory shots" [Localized] [Localized] [Localized].
 *
 * Mobile-first:
 * - [Localized] summary ≤ 500 [Localized]
 * - userIntent ≤ 200 [Localized] ([Localized] [Localized] [Localized])
 * - 256-float embedding (≈ 1 KB)
 * - 2000 episode [Localized] [Localized] ≈ 2-3 MB
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
