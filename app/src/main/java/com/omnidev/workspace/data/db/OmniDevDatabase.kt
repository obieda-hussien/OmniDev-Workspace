package com.omnidev.workspace.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.omnidev.workspace.data.db.dao.ChatMessageDao
import com.omnidev.workspace.data.db.dao.ChatSessionDao
import com.omnidev.workspace.data.db.dao.KnowledgeDao
import com.omnidev.workspace.data.db.entities.ChatMessageEntity
import com.omnidev.workspace.data.db.entities.ChatSessionEntity
import com.omnidev.workspace.data.db.entities.KnowledgeSnippet

/**
 * Single Room database instance for all persisted OmniDev data:
 * - Knowledge snippets (long-term memory)
 * - Chat sessions and their message history
 */
@Database(
    entities = [KnowledgeSnippet::class, ChatSessionEntity::class, ChatMessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class OmniDevDatabase : RoomDatabase() {

    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        @Volatile private var INSTANCE: OmniDevDatabase? = null

        fun getInstance(context: Context): OmniDevDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OmniDevDatabase::class.java,
                    "omnidev_workspace.db"
                ).build().also { INSTANCE = it }
            }
    }
}
