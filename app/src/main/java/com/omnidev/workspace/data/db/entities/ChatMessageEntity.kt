package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted chat message belonging to a [ChatSessionEntity].
 *
 * @property id Auto-generated primary key.
 * @property sessionId Foreign key referencing the parent [ChatSessionEntity].
 * @property role Message sender role: "USER", "ASSISTANT", "SYSTEM", or "TOOL".
 * @property content The textual content of the message.
 * @property timestamp Unix timestamp (ms) when this message was created.
 * @property consoleEntriesJson JSON-serialized agent console entries associated with this message.
 *   Non-empty only for ASSISTANT messages produced by the agent/swarm pipeline.
 * @property messageId Stable UUID string for cross-referencing replies. Populated on insert.
 * @property replyToMessageId When non-null, the [messageId] of the message this is replying to.
 */
@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId"), Index("messageId")]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val consoleEntriesJson: String = "",
    val messageId: String = "",
    val replyToMessageId: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val costUSD: Double = 0.0,
    val modelId: String? = null
)
