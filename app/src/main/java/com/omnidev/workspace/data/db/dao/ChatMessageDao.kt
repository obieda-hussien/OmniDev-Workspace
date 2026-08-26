package com.omnidev.workspace.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.omnidev.workspace.data.db.entities.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {

    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeBySession(sessionId: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getBySession(sessionId: Long): List<ChatMessageEntity>

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
