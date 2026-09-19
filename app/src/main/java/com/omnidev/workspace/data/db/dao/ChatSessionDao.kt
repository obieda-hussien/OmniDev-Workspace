package com.omnidev.workspace.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import com.omnidev.workspace.data.db.entities.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {

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
        "UPDATE chat_sessions SET sourceAppName = :appName, title = :title, lastUpdated = :timestamp " +
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
