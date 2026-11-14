package com.omnidev.workspace.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import androidx.room.OnConflictStrategy
import com.omnidev.workspace.data.db.entities.ChatHistoryMetadataEntity
import com.omnidev.workspace.domain.history.ChatMetadataModel
import com.omnidev.workspace.data.repository.HistoryMetadataText
import com.omnidev.workspace.data.db.entities.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveHistoryMetadata(metadata: ChatHistoryMetadataEntity)

    @Query("SELECT * FROM chat_history_metadata WHERE sessionId = :id")
    suspend fun historyMetadata(id: Long): ChatHistoryMetadataEntity?

    @Query("SELECT * FROM chat_history_metadata")
    fun observeHistoryMetadata(): Flow<List<ChatHistoryMetadataEntity>>

    @Query("SELECT s.id FROM chat_sessions s LEFT JOIN chat_history_metadata h ON s.id = h.sessionId WHERE h.sessionId IS NULL OR h.modelVersion < :version OR h.lastUserRowId < COALESCE((SELECT MAX(m.id) FROM chat_messages m WHERE m.sessionId = s.id AND m.role = 'USER'), 0) ORDER BY s.lastUpdated DESC, s.id DESC LIMIT :limit")
    suspend fun pendingHistoryMetadata(version: Int, limit: Int): List<Long>

    @Query("SELECT id, substr(content, 1, 1500) AS content FROM chat_messages WHERE sessionId = :id AND role = 'USER' ORDER BY id ASC LIMIT 3")
    suspend fun firstHistoryInputs(id: Long): List<HistoryMetadataInput>

    @Query("SELECT id, substr(content, 1, 1500) AS content FROM chat_messages WHERE sessionId = :id AND role = 'USER' ORDER BY id DESC LIMIT 3")
    suspend fun latestHistoryInputs(id: Long): List<HistoryMetadataInput>

    @Query("SELECT substr(metadataJson, :offset, 2048) FROM chat_messages WHERE id = :rowId AND role = 'USER'")
    fun historyMetadataPage(rowId: Long, offset: Int): String?

    @Transaction
    suspend fun createWithMetadata(session: ChatSessionEntity, metadata: ChatHistoryMetadataEntity): Long {
        val id = insert(session)
        saveHistoryMetadata(metadata.copy(sessionId = id))
        return id
    }

    /** A short local transaction (six bounded USER inputs); no provider, tool or network call. */
    @Transaction
    suspend fun refreshHistoryMetadata(id: Long): Boolean {
        val session = getById(id) ?: return false
        val previous = historyMetadata(id) ?: ChatHistoryMetadataEntity(id)
        val inputs = (firstHistoryInputs(id) + latestHistoryInputs(id)).distinctBy { it.id }.sortedBy { it.id }
        val latest = inputs.maxOfOrNull { it.id } ?: 0L
        if (previous.modelVersion >= ChatMetadataModel.VERSION && previous.lastUserRowId >= latest) return false
        val texts = inputs.map { input -> HistoryMetadataText.original(input.content) { offset -> historyMetadataPage(input.id, offset).orEmpty() } }.ifEmpty { listOf(session.title.substringBefore(" • Status: ")) }
        val result = ChatMetadataModel.analyze(texts, session.source, session.sourceAppPackage)
        saveHistoryMetadata(previous.copy(
            topic = if (previous.topicOrigin == "manual") previous.topic else result.topic,
            generatedTitle = result.title, serviceTags = result.serviceTags,
            modelVersion = ChatMetadataModel.VERSION, lastUserRowId = latest
        ))
        if (previous.titleOrigin == "automatic" && session.source == ChatSessionEntity.SOURCE_APP) {
            val suffix = session.title.substringAfter(" • Status: ", "")
            updateTitle(id, result.title + if (suffix.isNotEmpty()) " • Status: $suffix" else "")
        }
        return true
    }

    @Transaction
    suspend fun refreshHistoryMetadataBatch(ids: List<Long>) {
        require(ids.size <= 20)
        for (id in ids) refreshHistoryMetadata(id)
    }

    @Transaction
    suspend fun renameWithOwnership(id: Long, title: String) {
        if (getById(id) == null) return
        val old = historyMetadata(id) ?: ChatHistoryMetadataEntity(id)
        saveHistoryMetadata(old.copy(titleOrigin = "manual"))
        updateTitle(id, title)
    }

    @Transaction
    suspend fun setHistoryTopic(id: Long, topic: String?) {
        if (getById(id) == null) return
        val old = historyMetadata(id) ?: ChatHistoryMetadataEntity(id)
        saveHistoryMetadata(old.copy(topic = topic ?: "uncategorized", topicOrigin = if (topic == null) "automatic" else "manual", modelVersion = 0))
        refreshHistoryMetadata(id)
    }

    @Transaction
    suspend fun setRunStatus(id: Long, label: String, timestamp: Long) {
        val session = getById(id) ?: return
        val title = session.title.substringBefore(" • Status: ").ifBlank { "New conversation" }
        updateTitleAndTimestamp(id, "$title • Status: $label", timestamp)
    }

    @Query("UPDATE chat_sessions SET title = CASE WHEN EXISTS(SELECT 1 FROM chat_history_metadata WHERE sessionId = :id AND titleOrigin IN ('manual', 'automatic')) THEN title ELSE :title END, lastUpdated = :timestamp WHERE id = :id")
    suspend fun touchProvidedTitle(id: Long, title: String, timestamp: Long)

    @Query("SELECT * FROM chat_sessions WHERE backgroundKey = :key LIMIT 1")
    suspend fun getByBackgroundKey(key: String): ChatSessionEntity?

    @Transaction
    suspend fun sessionForBackgroundRun(key: String, title: String): Long =
        getByBackgroundKey(key)?.id ?: insert(ChatSessionEntity(title = title, source = "background", backgroundKey = key))

    @Insert
    suspend fun insert(session: ChatSessionEntity): Long

    @Update
    suspend fun update(session: ChatSessionEntity)

    /** Observe all sessions: pinned first, then newest-first within each group. */
    @Query("SELECT * FROM chat_sessions ORDER BY isPinned DESC, lastUpdated DESC")
    fun observeAll(): Flow<List<ChatSessionEntity>>

    /** Snapshot of all sessions: pinned first, then newest-first. */
    @Query("SELECT * FROM chat_sessions ORDER BY isPinned DESC, lastUpdated DESC")
    suspend fun getAll(): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions WHERE title LIKE '%' || :term || '%' ORDER BY lastUpdated DESC LIMIT :limit")
    suspend fun searchTitles(term: String, limit: Int): List<ChatSessionEntity>

    @Query(
        "SELECT * FROM chat_sessions WHERE (:query = '' OR title LIKE '%' || :query || '%') " +
            "ORDER BY lastUpdated DESC, id DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun listHistorySessions(query: String, limit: Int, offset: Int): List<ChatSessionEntity>

    @Query("UPDATE chat_sessions SET lastUpdated = :timestamp, title = :title WHERE id = :id")
    suspend fun updateTitleAndTimestamp(id: Long, title: String, timestamp: Long)

    @Query("UPDATE chat_sessions SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("UPDATE chat_sessions SET isPinned = :pinned WHERE id = :id")
    suspend fun setPin(id: Long, pinned: Boolean)

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM chat_sessions")
    suspend fun deleteAll()

    @Query(
        "SELECT * FROM chat_sessions WHERE source = 'external_app' " +
            "AND sourceAppPackage = :packageName AND externalConversationId = :conversationId LIMIT 1"
    )
    suspend fun getByExternalConversation(
        packageName: String,
        conversationId: String
    ): ChatSessionEntity?

    @Query(
        "SELECT * FROM chat_sessions WHERE source = 'external_app' " +
            "AND sourceAppPackage = :packageName " +
            "AND (:beforeUpdatedAt IS NULL OR lastUpdated < :beforeUpdatedAt) " +
            "AND (:search = '' OR title LIKE '%' || :search || '%') " +
            "ORDER BY lastUpdated DESC LIMIT :limit"
    )
    suspend fun listExternalSessions(
        packageName: String,
        beforeUpdatedAt: Long?,
        search: String,
        limit: Int
    ): List<ChatSessionEntity>

    @Query(
        "UPDATE chat_sessions SET sourceAppName = :appName, title = CASE WHEN EXISTS(SELECT 1 FROM chat_history_metadata WHERE sessionId = :id AND titleOrigin IN ('manual', 'automatic')) THEN title ELSE :title END, lastUpdated = :timestamp " +
            "WHERE id = :id"
    )
    suspend fun touchExternalSession(
        id: Long,
        appName: String,
        title: String,
        timestamp: Long
    )

    /** Find an existing Telegram session by its chat ID. */
    @Query("SELECT * FROM chat_sessions WHERE telegramChatId = :chatId AND source = 'telegram' LIMIT 1")
    suspend fun getByTelegramChatId(chatId: Long): ChatSessionEntity?

    /** Find an existing Discord session by its channel ID. */
    @Query("SELECT * FROM chat_sessions WHERE discordChannelId = :channelId AND source = 'discord' LIMIT 1")
    suspend fun getByDiscordChannelId(channelId: String): ChatSessionEntity?

    /** Find an existing WhatsApp Bridge session by JID. */
    @Query("SELECT * FROM chat_sessions WHERE whatsappJid = :jid AND source = 'whatsapp_bridge' LIMIT 1")
    suspend fun getByWhatsAppJid(jid: String): ChatSessionEntity?
}

/** Bounded projection: never loads attachments, source context or tool output. */
data class HistoryMetadataInput(val id: Long, val content: String)
