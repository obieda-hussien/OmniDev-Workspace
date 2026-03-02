package com.omnidev.workspace.data.repository

import com.omnidev.workspace.data.db.dao.ChatMessageDao
import com.omnidev.workspace.data.db.dao.ChatSessionDao
import com.omnidev.workspace.data.db.entities.ChatMessageEntity
import com.omnidev.workspace.data.db.entities.ChatSessionEntity
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import kotlinx.coroutines.flow.Flow

/**
 * Repository that persists chat sessions and their messages to Room.
 *
 * Provides a clean API for [ChatViewModel] to:
 * - Create new sessions and save messages to them.
 * - Load past sessions for the history drawer.
 * - Restore a previous session's message history.
 */
class ChatRepository(
    private val sessionDao: ChatSessionDao,
    private val messageDao: ChatMessageDao
) {
    companion object {
        /**
         * Maximum number of characters stored per message. Truncation prevents unbounded
         * database growth from very long AI responses; the full text is always visible in
         * the live UI — only the persisted copy is capped.
         */
        private const val MAX_STORED_MESSAGE_CHARS = 10_000
    }

    /** Observe all sessions ordered newest-first (for the navigation drawer). */
    fun observeSessions(): Flow<List<ChatSessionEntity>> = sessionDao.observeAll()

    /**
     * Creates a new session with the given title and returns its generated ID.
     */
    suspend fun createSession(title: String): Long =
        sessionDao.insert(ChatSessionEntity(title = title))

    /**
     * Updates the session title and last-updated timestamp.
     */
    suspend fun touchSession(sessionId: Long, title: String) {
        sessionDao.updateTitleAndTimestamp(sessionId, title, System.currentTimeMillis())
    }

    /**
     * Persists a [ChatMessage] to the given session.
     * Content is truncated to [MAX_STORED_MESSAGE_CHARS] chars to keep DB size manageable.
     * The full text is always visible in the live in-memory UI state.
     */
    suspend fun saveMessage(sessionId: Long, message: ChatMessage): Long =
        messageDao.insert(
            ChatMessageEntity(
                sessionId = sessionId,
                role = message.role.name,
                content = message.content.take(MAX_STORED_MESSAGE_CHARS),
                timestamp = message.timestamp
            )
        )

    /**
     * Loads all messages for a session and converts them to [ChatMessage] domain objects.
     */
    suspend fun loadMessages(sessionId: Long): List<ChatMessage> =
        messageDao.getBySession(sessionId).map { entity ->
            ChatMessage(
                role = runCatching { MessageRole.valueOf(entity.role) }.getOrDefault(MessageRole.USER),
                content = entity.content,
                timestamp = entity.timestamp
            )
        }

    /** Deletes a session and all its messages (cascade delete handles messages). */
    suspend fun deleteSession(sessionId: Long) = sessionDao.deleteById(sessionId)
}
