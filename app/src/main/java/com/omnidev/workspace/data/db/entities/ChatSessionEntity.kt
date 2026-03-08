package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted chat session record.
 *
 * @property id Auto-generated primary key.
 * @property title Human-readable session title (derived from the first user message).
 * @property lastUpdated Unix timestamp (ms) of the most recent activity.
 * @property createdAt Unix timestamp (ms) when the session started.
 * @property isPinned Whether this session is pinned to the top of the history list.
 */
@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val lastUpdated: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false
)
