package com.omnidev.workspace.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.omnidev.workspace.data.db.entities.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {

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

    /** Find an existing Telegram session by its chat ID. */
    @Query("SELECT * FROM chat_sessions WHERE telegramChatId = :chatId AND source = 'telegram' LIMIT 1")
    suspend fun getByTelegramChatId(chatId: Long): ChatSessionEntity?
}
