package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** Local, bounded presentation metadata. Source identity and completion evidence live elsewhere. */
@Entity(
    tableName = "chat_history_metadata",
    foreignKeys = [ForeignKey(entity = ChatSessionEntity::class, parentColumns = ["id"],
        childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)]
)
data class ChatHistoryMetadataEntity(
    @PrimaryKey val sessionId: Long,
    val topic: String = "uncategorized",
    val topicOrigin: String = "automatic",
    val serviceTags: String = "",
    val generatedTitle: String = "",
    val titleOrigin: String = "provided",
    val modelVersion: Int = 0,
    val lastUserRowId: Long = 0
)
