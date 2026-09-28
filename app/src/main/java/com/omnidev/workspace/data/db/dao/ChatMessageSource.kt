package com.omnidev.workspace.data.db.dao

/** Lean, bounded projection for recall; excludes attachments, console logs and model metadata. */
data class ChatMessageSource(
    val id: Long,
    val sessionId: Long,
    val role: String,
    val content: String,
    val timestamp: Long
)
