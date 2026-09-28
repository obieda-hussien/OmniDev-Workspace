package com.omnidev.workspace.data.db.dao

data class ChatMessageChunk(
    val id: Long,
    val sessionId: Long,
    val role: String,
    val content: String,
    val timestamp: Long,
    val totalChars: Int
)
