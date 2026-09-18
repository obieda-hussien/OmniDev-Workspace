package com.omnidev.workspace.data.repository

import com.omnidev.workspace.data.db.dao.ChatMessageDao
import com.omnidev.workspace.data.db.dao.ChatSessionDao
import com.omnidev.workspace.data.db.entities.ChatMessageEntity
import com.omnidev.workspace.data.db.entities.ChatSessionEntity
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.ui.chat.AgentConsoleEntry
import com.omnidev.workspace.ui.chat.AgentConsoleSerializer
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Repository that persists chat sessions and their messages to Room.
 *
 * Provides a clean API for [ChatViewModel] to:
 * - Create new sessions and save messages to them.
 * - Load past sessions for the history drawer.
 * - Restore a previous session's message history, including per-message agent console entries.
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
        private const val MAX_STORED_MESSAGE_CHARS = 100_000
        private const val MAX_STORED_SOURCE_CONTEXT_CHARS = 250_000
        private const val SESSION_STATUS_SEPARATOR = " • Status: "
        private const val DEFAULT_SESSION_TITLE = "New conversation"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private fun metadata(message: ChatMessage): String = json.encodeToString(message.copy(
        content = "", toolCalls = emptyList(), toolResults = emptyList(), thinkingContent = null,
        attachments = message.attachments.map { it.copy(base64Data = null) }))
    private fun decodeMetadata(raw: String): ChatMessage? =
        runCatching { json.decodeFromString<ChatMessage>(raw) }.getOrNull()

    suspend fun updateRun(rowId: Long, message: ChatMessage, entries: List<AgentConsoleEntry>) {
        if (rowId < 0) return
        messageDao.updateChatRun(rowId, message.content.take(MAX_STORED_MESSAGE_CHARS),
            AgentConsoleSerializer.serialize(entries), metadata(message))
    }

    suspend fun updateMetadata(sessionId: Long, message: ChatMessage) =
        messageDao.updateMetadata(sessionId, message.messageId, metadata(message))

    /** Observe all sessions ordered newest-first (for the navigation drawer). */
    fun observeMessages(sessionId: Long) = messageDao.observeBySession(sessionId)

    fun observeSessions(): Flow<List<ChatSessionEntity>> = sessionDao.observeAll()

    /**
     * Creates a new session with the given title and returns its generated ID.
     */
    suspend fun createSession(title: String): Long =
        sessionDao.insert(ChatSessionEntity(title = title))

    /**
     * Resolve a durable conversation owned by an external application.
     *
     * [conversationId] is client-stable (for example one AndroidIDE project chat). Reusing it
     * continues the same Workspace history row, including Agent Console data.
     */
    suspend fun getOrCreateExternalSession(
        packageName: String,
        appName: String,
        conversationId: String,
        topicTitle: String
    ): Long {
        val existing = sessionDao.getByExternalConversation(packageName, conversationId)
        if (existing != null) {
            sessionDao.touchExternalSession(
                id = existing.id,
                appName = appName,
                title = topicTitle.ifBlank { existing.title },
                timestamp = System.currentTimeMillis()
            )
            return existing.id
        }

        return sessionDao.insert(
            ChatSessionEntity(
                title = topicTitle.ifBlank { DEFAULT_SESSION_TITLE },
                source = ChatSessionEntity.SOURCE_EXTERNAL_APP,
                sourceAppPackage = packageName,
                sourceAppName = appName,
                externalConversationId = conversationId
            )
        )
    }

    /**
     * Updates the session title and last-updated timestamp.
     */
    suspend fun touchSession(sessionId: Long, title: String) {
        sessionDao.updateTitleAndTimestamp(sessionId, title, System.currentTimeMillis())
    }

    /**
     * Persists the latest run status into the session title so it is visible in history.
     * Existing status suffixes are replaced; the original title is preserved.
     */
    suspend fun updateSessionRunStatus(sessionId: Long, statusLabel: String) {
        val session = sessionDao.getById(sessionId) ?: return
        val baseTitle = session.title.substringBefore(SESSION_STATUS_SEPARATOR).trim()
        val normalizedBase = baseTitle.ifBlank { DEFAULT_SESSION_TITLE }
        sessionDao.updateTitleAndTimestamp(
            id = sessionId,
            title = "$normalizedBase$SESSION_STATUS_SEPARATOR$statusLabel",
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Persists a [ChatMessage] to the given session, optionally with agent console entries.
     * Content is truncated to [MAX_STORED_MESSAGE_CHARS] chars to keep DB size manageable.
     * The full text is always visible in the live in-memory UI state.
     *
     * The [ChatMessage.messageId] is preserved exactly as-is so callers can later look up
     * the message by its stable UUID (e.g. to resolve a reply reference).
     *
     * @param consoleEntries Agent console entries to persist alongside this message (assistant only).
     */
    suspend fun saveMessage(
        sessionId: Long,
        message: ChatMessage,
        consoleEntries: List<AgentConsoleEntry> = emptyList(),
        sourceContextJson: String = ""
    ): Long =
        messageDao.insert(
            ChatMessageEntity(
                sessionId = sessionId,
                role = message.role.name,
                content = message.content.take(MAX_STORED_MESSAGE_CHARS),
                timestamp = message.timestamp,
                consoleEntriesJson = AgentConsoleSerializer.serialize(consoleEntries),
                messageId = message.messageId,
                replyToMessageId = message.replyToMessageId,
                metadataJson = metadata(message),
                sourceContextJson = sourceContextJson.take(MAX_STORED_SOURCE_CONTEXT_CHARS)
            )
        )

    /**
     * Loads all messages for a session and converts them to [ChatMessage] domain objects.
     * Also returns a map from message timestamp → agent console entries for assistant messages
     * that had console data saved.
     *
     * @return Pair of (messages, messageConsoleEntries map keyed by message timestamp).
     */
    suspend fun loadMessages(sessionId: Long): Pair<List<ChatMessage>, Map<Long, List<AgentConsoleEntry>>> {
        val entities = messageDao.getBySession(sessionId)
        val messages = entities.map { entity ->
            ChatMessage(
                role = runCatching { MessageRole.valueOf(entity.role) }.getOrDefault(MessageRole.USER),
                content = entity.content,
                timestamp = entity.timestamp,
                messageId = entity.messageId.ifBlank { entity.id.toString() },
                replyToMessageId = entity.replyToMessageId,
                executionRequest = decodeMetadata(entity.metadataJson)?.executionRequest,
                attachments = decodeMetadata(entity.metadataJson)?.attachments.orEmpty()
            )
        }
        val consoleMap = entities
            .filter { it.consoleEntriesJson.isNotBlank() }
            .associate { entity ->
                entity.timestamp to AgentConsoleSerializer.deserialize(entity.consoleEntriesJson)
            }
        return messages to consoleMap
    }

    /**
     * Looks up a single [ChatMessage] by its stable [messageId] UUID.
     * Returns null when no message with that ID exists in the given session.
     */
    suspend fun getMessageById(messageId: String): ChatMessage? {
        val entity = messageDao.getByMessageId(messageId) ?: return null
        return ChatMessage(
            role = runCatching { MessageRole.valueOf(entity.role) }.getOrDefault(MessageRole.USER),
            content = entity.content,
            timestamp = entity.timestamp,
            messageId = entity.messageId.ifBlank { entity.id.toString() },
            replyToMessageId = entity.replyToMessageId,
                executionRequest = decodeMetadata(entity.metadataJson)?.executionRequest,
                attachments = decodeMetadata(entity.metadataJson)?.attachments.orEmpty()
        )
    }

    /**
     * Searches messages in the given session whose content contains [query] (case-insensitive
     * substring match via SQL LIKE). Returns matching [ChatMessage] objects ordered by time.
     */

    suspend fun searchAllMessages(query: String, limit: Int = 10): List<ChatMessage> =
        messageDao.searchAllByContent(query, limit).map { entity ->
            ChatMessage(
                role = runCatching { MessageRole.valueOf(entity.role) }.getOrDefault(MessageRole.USER),
                content = entity.content,
                timestamp = entity.timestamp,
                messageId = entity.messageId.ifBlank { entity.id.toString() },
                replyToMessageId = entity.replyToMessageId,
                executionRequest = decodeMetadata(entity.metadataJson)?.executionRequest,
                attachments = decodeMetadata(entity.metadataJson)?.attachments.orEmpty()
            )
        }

    suspend fun searchMessages(sessionId: Long, query: String): List<ChatMessage> =
        messageDao.searchByContent(sessionId, query).map { entity ->
            ChatMessage(
                role = runCatching { MessageRole.valueOf(entity.role) }.getOrDefault(MessageRole.USER),
                content = entity.content,
                timestamp = entity.timestamp,
                messageId = entity.messageId.ifBlank { entity.id.toString() },
                replyToMessageId = entity.replyToMessageId,
                executionRequest = decodeMetadata(entity.metadataJson)?.executionRequest,
                attachments = decodeMetadata(entity.metadataJson)?.attachments.orEmpty()
            )
        }

    /** Deletes a session and all its messages (cascade delete handles messages). */
    suspend fun deleteSession(sessionId: Long) = sessionDao.deleteById(sessionId)

    /** Deletes all sessions (cascade delete handles their messages). */
    suspend fun deleteAllSessions() = sessionDao.deleteAll()

    /** Deletes multiple sessions by their IDs. */
    suspend fun deleteSelectedSessions(ids: Set<Long>) {
        ids.forEach { sessionDao.deleteById(it) }
    }

    /** Toggles the pinned state for the given session. */
    suspend fun togglePin(sessionId: Long) {
        val session = sessionDao.getById(sessionId) ?: return
        sessionDao.setPin(sessionId, !session.isPinned)
    }

    /** Renames the given session. */
    suspend fun renameSession(sessionId: Long, newTitle: String) {
        sessionDao.updateTitle(sessionId, newTitle)
    }

    /**
     * Finds an existing Telegram session for [telegramChatId], or creates a new one
     * if none exists. Updates the title and timestamp on each call to keep it current —
     * Telegram chat titles can change, and the timestamp keeps the session visible at the top
     * of the sorted history list after each new message.
     * Returns the session's primary-key ID.
     */
    suspend fun findOrCreateTelegramSession(telegramChatId: Long, title: String): Long {
        val existing = sessionDao.getByTelegramChatId(telegramChatId)
        if (existing != null) {
            sessionDao.updateTitleAndTimestamp(existing.id, title, System.currentTimeMillis())
            return existing.id
        }
        return sessionDao.insert(
            ChatSessionEntity(
                title = title,
                source = ChatSessionEntity.SOURCE_TELEGRAM,
                telegramChatId = telegramChatId
            )
        )
    }

    suspend fun findOrCreateDiscordSession(discordChannelId: String, title: String): Long {
        val existing = sessionDao.getByDiscordChannelId(discordChannelId)
        if (existing != null) {
            sessionDao.updateTitleAndTimestamp(existing.id, title, System.currentTimeMillis())
            return existing.id
        }
        return sessionDao.insert(
            ChatSessionEntity(
                title = "💬 Discord: $title",
                source = ChatSessionEntity.SOURCE_DISCORD,
                discordChannelId = discordChannelId
            )
        )
    }

    suspend fun findOrCreateWhatsAppBridgeSession(jid: String, title: String): Long {
        val existing = sessionDao.getByWhatsAppJid(jid)
        if (existing != null) {
            sessionDao.updateTitleAndTimestamp(existing.id, title, System.currentTimeMillis())
            return existing.id
        }
        return sessionDao.insert(
            ChatSessionEntity(
                title = "💬 WhatsApp: $title",
                source = ChatSessionEntity.SOURCE_WHATSAPP_BRIDGE,
                whatsappJid = jid
            )
        )
    }
}
