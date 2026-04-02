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
import com.omnidev.workspace.data.db.dao.SystemKnowledgeDao
import com.omnidev.workspace.data.db.dao.ToolExecutionDao
import com.omnidev.workspace.data.db.entities.ChatMessageEntity
import com.omnidev.workspace.data.db.entities.ChatSessionEntity
import com.omnidev.workspace.data.db.entities.KnowledgeSnippet
import com.omnidev.workspace.data.db.entities.SystemKnowledgeEntry
import com.omnidev.workspace.data.db.entities.ToolExecutionEntry

/**
 * Single Room database instance for all persisted OmniDev data:
 * - Knowledge snippets (long-term memory)
 * - Chat sessions and their message history
 * - Tool execution log (agent brain memory — ADDED v7)
 * - System knowledge base (environment awareness — ADDED v7)
 *
 * Version history:
 *  1 → initial schema
 *  2 → added `isPinned` column to `chat_sessions`
 *  3 → added `source` and `telegramChatId` columns to `chat_sessions`
 *  4 → added `discordChannelId` column to `chat_sessions`
 *  5 → added `whatsappJid` column to `chat_sessions`
 *  6 → added `consoleEntriesJson` column to `chat_messages`
 *  7 → added `tool_execution_log` and `system_knowledge` tables (Agent Brain)
 *  8 → normalize `tool_execution_log` schema to match Room entity metadata
 */
@Database(
    entities = [
        KnowledgeSnippet::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        ToolExecutionEntry::class,
        SystemKnowledgeEntry::class
    ],
    version = 8,
    exportSchema = false
)
abstract class OmniDevDatabase : RoomDatabase() {

    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun toolExecutionDao(): ToolExecutionDao
    abstract fun systemKnowledgeDao(): SystemKnowledgeDao

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

        /**
         * Migration from v6 → v7:
         * - Adds `tool_execution_log` table for permanent agent memory
         * - Adds `system_knowledge` table for environment & tool awareness
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // جدول سجل تنفيذ الأدوات
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tool_execution_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        toolName TEXT NOT NULL,
                        parametersJson TEXT NOT NULL DEFAULT '{}',
                        resultSummary TEXT NOT NULL DEFAULT '',
                        success INTEGER NOT NULL,
                        executionTimeMs INTEGER NOT NULL,
                        resultSize INTEGER NOT NULL DEFAULT 0,
                        agentContext TEXT NOT NULL DEFAULT '',
                        previousToolName TEXT NOT NULL DEFAULT '',
                        sessionId TEXT NOT NULL DEFAULT '',
                        agentMode TEXT NOT NULL DEFAULT '',
                        errorMessage TEXT NOT NULL DEFAULT '',
                        resultQuality REAL NOT NULL DEFAULT 0.5,
                        hourOfDay INTEGER NOT NULL DEFAULT 0,
                        dayOfWeek INTEGER NOT NULL DEFAULT 1,
                        learningNote TEXT NOT NULL DEFAULT '',
                        flaggedForReview INTEGER NOT NULL DEFAULT 0,
                        timestamp INTEGER NOT NULL
                    )
                """.trimIndent())

                // فهرس لتسريع البحث بالأداة والجلسة
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_log_tool ON tool_execution_log(toolName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_log_session ON tool_execution_log(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_log_time ON tool_execution_log(timestamp)")

                // جدول قاعدة المعرفة بالنظام
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS system_knowledge (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        knowledgeType TEXT NOT NULL,
                        subject TEXT NOT NULL,
                        content TEXT NOT NULL,
                        confidence REAL NOT NULL DEFAULT 1.0,
                        verificationCount INTEGER NOT NULL DEFAULT 1,
                        isValid INTEGER NOT NULL DEFAULT 1,
                        source TEXT NOT NULL DEFAULT 'agent_discovery',
                        searchTags TEXT NOT NULL DEFAULT '',
                        injectionPriority INTEGER NOT NULL DEFAULT 5,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())

                // فهرس لتسريع البحث
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sys_knowledge_type ON system_knowledge(knowledgeType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sys_knowledge_subject ON system_knowledge(subject)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sys_knowledge_priority ON system_knowledge(injectionPriority)")
            }
        }

        /**
         * Migration from v7 → v8:
         * Rebuilds `tool_execution_log` to a canonical schema so databases created from older
         * entity metadata (without defaults/indices) and migrated databases are both valid.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_tool_log_tool")
                db.execSQL("DROP INDEX IF EXISTS index_tool_log_session")
                db.execSQL("DROP INDEX IF EXISTS index_tool_log_time")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tool_execution_log_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        toolName TEXT NOT NULL,
                        parametersJson TEXT NOT NULL DEFAULT '{}',
                        resultSummary TEXT NOT NULL DEFAULT '',
                        success INTEGER NOT NULL,
                        executionTimeMs INTEGER NOT NULL,
                        resultSize INTEGER NOT NULL DEFAULT 0,
                        agentContext TEXT NOT NULL DEFAULT '',
                        previousToolName TEXT NOT NULL DEFAULT '',
                        sessionId TEXT NOT NULL DEFAULT '',
                        agentMode TEXT NOT NULL DEFAULT '',
                        errorMessage TEXT NOT NULL DEFAULT '',
                        resultQuality REAL NOT NULL DEFAULT 0.5,
                        hourOfDay INTEGER NOT NULL DEFAULT 0,
                        dayOfWeek INTEGER NOT NULL DEFAULT 1,
                        learningNote TEXT NOT NULL DEFAULT '',
                        flaggedForReview INTEGER NOT NULL DEFAULT 0,
                        timestamp INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO tool_execution_log_new (
                        id, toolName, parametersJson, resultSummary, success, executionTimeMs,
                        resultSize, agentContext, previousToolName, sessionId, agentMode,
                        errorMessage, resultQuality, hourOfDay, dayOfWeek, learningNote,
                        flaggedForReview, timestamp
                    )
                    SELECT
                        id, toolName, parametersJson, resultSummary, success, executionTimeMs,
                        resultSize, agentContext, previousToolName, sessionId, agentMode,
                        errorMessage, resultQuality, hourOfDay, dayOfWeek, learningNote,
                        flaggedForReview, timestamp
                    FROM tool_execution_log
                """.trimIndent())

                db.execSQL("DROP TABLE tool_execution_log")
                db.execSQL("ALTER TABLE tool_execution_log_new RENAME TO tool_execution_log")

                db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_log_tool ON tool_execution_log(toolName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_log_session ON tool_execution_log(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_log_time ON tool_execution_log(timestamp)")
            }
        }

        fun getInstance(context: Context): OmniDevDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OmniDevDatabase::class.java,
                    "omnidev_workspace.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8
                    )
                    .build().also { INSTANCE = it }
            }
    }
}
