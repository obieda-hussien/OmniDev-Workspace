package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** EpisodicMemoryEntry summarizes an entire previous agent task: user intent, tools and outcome. Unlike a short tool-specific Reflexion lesson, it supplies similar-task memory examples. Summary is bounded to 500 characters, intent to 200 and embeddings to 256 floats; stores up to 2000 episodes. */
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
