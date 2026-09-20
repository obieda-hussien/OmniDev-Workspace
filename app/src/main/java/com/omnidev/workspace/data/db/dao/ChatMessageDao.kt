package com.omnidev.workspace.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Transaction
import androidx.room.Query
import com.omnidev.workspace.data.db.entities.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {

    @Query("UPDATE chat_messages SET content = :content, consoleEntriesJson = :console WHERE id = :id")
    suspend fun updateProgress(id: Long, content: String, console: String)

    @Query("UPDATE chat_messages SET content = :content, consoleEntriesJson = :console, metadataJson = :metadata WHERE id = :id")
    suspend fun updateChatRun(id: Long, content: String, console: String, metadata: String)

    @Query("UPDATE chat_messages SET metadataJson = :metadata WHERE sessionId = :sessionId AND messageId = :messageId")
    suspend fun updateMetadata(sessionId: Long, messageId: String, metadata: String)

    @Insert
    suspend fun insertRow(message: ChatMessageEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM chat_sessions WHERE id = :sessionId)")
    suspend fun sessionExists(sessionId: Long): Boolean

    /**
     * Late responses may arrive after a session was deleted. Check and insert in
     * one transaction so deletion cannot race the foreign-key check. -1 means
     * the parent no longer exists; never recreate a user-deleted conversation.
     */
    @Transaction
    suspend fun insert(message: ChatMessageEntity): Long {
        if (!sessionExists(message.sessionId)) return -1L
        return insertRow(message)
    }

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeBySession(sessionId: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getBySession(sessionId: Long): List<ChatMessageEntity>

    @Query(
        "SELECT * FROM chat_messages WHERE sessionId = :sessionId " +
            "AND (:beforeMessageId IS NULL OR id < :beforeMessageId) " +
            "ORDER BY id DESC LIMIT :limit"
    )
    suspend fun getPageBySession(
        sessionId: Long,
        beforeMessageId: Long?,
        limit: Int
    ): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getByMessageId(messageId: String): ChatMessageEntity?

    @Query(
        "SELECT * FROM chat_messages WHERE sessionId = :sessionId AND content LIKE '%' || :query || '%' ORDER BY timestamp ASC"
    )
    suspend fun searchByContent(sessionId: Long, query: String): List<ChatMessageEntity>

    @Query(
        "SELECT * FROM chat_messages WHERE content LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun searchAllByContent(query: String, limit: Int): List<ChatMessageEntity>


    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)
}
