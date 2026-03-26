package com.omnidev.workspace.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
 *
 * Version history:
 *  1 → initial schema
 *  2 → added `isPinned` column to `chat_sessions`
 *  3 → added `source` and `telegramChatId` columns to `chat_sessions`
 *  4 → added `discordChannelId` column to `chat_sessions`
 *  5 → added `whatsappJid` column to `chat_sessions`
 *  6 → added `consoleEntriesJson` column to `chat_messages`
 */
@Database(
    entities = [KnowledgeSnippet::class, ChatSessionEntity::class, ChatMessageEntity::class],
    version = 6,
    exportSchema = false
)
abstract class OmniDevDatabase : RoomDatabase() {

    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        @Volatile private var INSTANCE: OmniDevDatabase? = null

        /** Migration from v1 (no isPinned) → v2 (isPinned column added). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE chat_sessions ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** Migration from v2 → v3 (source + telegramChatId columns added). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN source TEXT NOT NULL DEFAULT 'app'")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN telegramChatId INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Migration from v3 → v4 (discordChannelId column added). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN discordChannelId TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Migration from v4 → v5 (whatsappJid column added). */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN whatsappJid TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Migration from v5 → v6 (consoleEntriesJson column added to chat_messages). */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN consoleEntriesJson TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): OmniDevDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OmniDevDatabase::class.java,
                    "omnidev_workspace.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build().also { INSTANCE = it }
            }
    }
}
