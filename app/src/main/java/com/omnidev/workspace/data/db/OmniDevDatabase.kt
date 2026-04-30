package com.omnidev.workspace.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.omnidev.workspace.data.db.dao.BuildDiagnosticDao
import com.omnidev.workspace.data.db.dao.ChatMessageDao
import com.omnidev.workspace.data.db.dao.ChatSessionDao
import com.omnidev.workspace.data.db.dao.EpisodicMemoryDao
import com.omnidev.workspace.data.db.dao.KnowledgeDao
import com.omnidev.workspace.data.db.dao.ReflexionDao
import com.omnidev.workspace.data.db.dao.RepoIndexDao
import com.omnidev.workspace.data.db.dao.RollbackDao
import com.omnidev.workspace.data.db.dao.SystemKnowledgeDao
import com.omnidev.workspace.data.db.dao.ToolExecutionDao
import com.omnidev.workspace.data.db.entities.BuildDiagnosticEntry
import com.omnidev.workspace.data.db.entities.ChatMessageEntity
import com.omnidev.workspace.data.db.entities.ChatSessionEntity
import com.omnidev.workspace.data.db.entities.EpisodicMemoryEntry
import com.omnidev.workspace.data.db.entities.KnowledgeSnippet
import com.omnidev.workspace.data.db.entities.ReflexionLessonEntry
import com.omnidev.workspace.data.db.entities.RepoFileIndexEntry
import com.omnidev.workspace.data.db.entities.RepoSymbolEntry
import com.omnidev.workspace.data.db.entities.RollbackSnapshotEntry
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
 *  8 → normalize `tool_execution_log` and `system_knowledge` schemas to match Room entity metadata
 *  9 → added `messageId` and `replyToMessageId` columns to `chat_messages` for threaded replies
 */
@Database(
    entities = [
        KnowledgeSnippet::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        ToolExecutionEntry::class,
        SystemKnowledgeEntry::class,
        // ── Agent Brain 2.0 (v10) ─────────────────────────
        ReflexionLessonEntry::class,
        EpisodicMemoryEntry::class,
        // ── Action Insurance / Rollback (v10) ─────────────
        RollbackSnapshotEntry::class,
        // ── Live Repository Context Engine (v10) ──────────
        RepoFileIndexEntry::class,
        RepoSymbolEntry::class,
        // ── Build Doctor Pro (v10) ────────────────────────
        BuildDiagnosticEntry::class
    ],
    version = 10,
    exportSchema = false
)
abstract class OmniDevDatabase : RoomDatabase() {

    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun toolExecutionDao(): ToolExecutionDao
    abstract fun systemKnowledgeDao(): SystemKnowledgeDao
    abstract fun reflexionDao(): ReflexionDao
    abstract fun episodicMemoryDao(): EpisodicMemoryDao
    abstract fun rollbackDao(): RollbackDao
    abstract fun repoIndexDao(): RepoIndexDao
    abstract fun buildDiagnosticDao(): BuildDiagnosticDao

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

                val oldTableExists = db.scalarLong(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='tool_execution_log'"
                ) > 0

                if (oldTableExists) {
                    val existingColumns = mutableSetOf<String>()
                    db.query("PRAGMA table_info(tool_execution_log)").use { cursor ->
                        val nameIndex = cursor.getColumnIndex("name")
                        while (cursor.moveToNext()) {
                            existingColumns += cursor.getString(nameIndex)
                        }
                    }

                    val insertColumns = mutableListOf<String>()
                    val selectExpressions = mutableListOf<String>()

                    fun addColumn(column: String, fallback: String) {
                        insertColumns += column
                        selectExpressions += if (existingColumns.contains(column)) column else fallback
                    }

                    if (existingColumns.contains("id")) {
                        insertColumns += "id"
                        selectExpressions += "id"
                    }
                    addColumn("toolName", "''")
                    addColumn("parametersJson", "'{}'")
                    addColumn("resultSummary", "''")
                    addColumn("success", "0")
                    addColumn("executionTimeMs", "0")
                    addColumn("resultSize", "0")
                    addColumn("agentContext", "''")
                    addColumn("previousToolName", "''")
                    addColumn("sessionId", "''")
                    addColumn("agentMode", "''")
                    addColumn("errorMessage", "''")
                    addColumn("resultQuality", "0.5")
                    addColumn("hourOfDay", "0")
                    addColumn("dayOfWeek", "1")
                    addColumn("learningNote", "''")
                    addColumn("flaggedForReview", "0")
                    addColumn("timestamp", "0")

                    db.execSQL(
                        """
                        INSERT INTO tool_execution_log_new (${insertColumns.joinToString(", ")})
                        SELECT ${selectExpressions.joinToString(", ")}
                        FROM tool_execution_log
                        """.trimIndent()
                    )

                    val sourceCount = db.scalarLong("SELECT COUNT(*) FROM tool_execution_log")
                    val targetCount = db.scalarLong("SELECT COUNT(*) FROM tool_execution_log_new")
                    check(targetCount == sourceCount) {
                        "tool_execution_log migration row count mismatch: source=$sourceCount target=$targetCount"
                    }

                    db.execSQL("DROP TABLE tool_execution_log")
                }

                db.execSQL("ALTER TABLE tool_execution_log_new RENAME TO tool_execution_log")

                db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_log_tool ON tool_execution_log(toolName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_log_session ON tool_execution_log(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_log_time ON tool_execution_log(timestamp)")

                // Normalize system_knowledge schema to match Room metadata:
                // no SQL defaults and no indices at v8.
                db.execSQL("DROP INDEX IF EXISTS index_sys_knowledge_type")
                db.execSQL("DROP INDEX IF EXISTS index_sys_knowledge_subject")
                db.execSQL("DROP INDEX IF EXISTS index_sys_knowledge_priority")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS system_knowledge_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        knowledgeType TEXT NOT NULL,
                        subject TEXT NOT NULL,
                        content TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        verificationCount INTEGER NOT NULL,
                        isValid INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        searchTags TEXT NOT NULL,
                        injectionPriority INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())

                val systemKnowledgeTableExists = db.scalarLong(
                    "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='system_knowledge')"
                ) == 1L
                if (systemKnowledgeTableExists) {
                    val currentTimeMillisExpr = "(strftime('%s','now') * 1000)"
                    val existingColumns = mutableSetOf<String>()
                    db.query("PRAGMA table_info(system_knowledge)").use { cursor ->
                        val nameIndex = cursor.getColumnIndex("name")
                        while (cursor.moveToNext()) {
                            existingColumns += cursor.getString(nameIndex)
                        }
                    }

                    val insertColumns = mutableListOf<String>()
                    val selectExpressions = mutableListOf<String>()

                    fun addColumnWithFallback(column: String, fallback: String) {
                        insertColumns += column
                        selectExpressions += if (existingColumns.contains(column)) column else fallback
                    }

                    if (existingColumns.contains("id")) {
                        insertColumns += "id"
                        selectExpressions += "id"
                    }
                    addColumnWithFallback("knowledgeType", "''")
                    addColumnWithFallback("subject", "''")
                    addColumnWithFallback("content", "''")
                    // Align fallback values with SystemKnowledgeEntry defaults when data is absent.
                    addColumnWithFallback("confidence", "1.0")
                    addColumnWithFallback("verificationCount", "1")
                    addColumnWithFallback("isValid", "1")
                    addColumnWithFallback("source", "'agent_discovery'")
                    addColumnWithFallback("searchTags", "''")
                    addColumnWithFallback("injectionPriority", "5")
                    // If legacy rows are missing timestamps, backfill with migration-time value.
                    addColumnWithFallback("createdAt", currentTimeMillisExpr)
                    addColumnWithFallback("updatedAt", currentTimeMillisExpr)

                    db.execSQL(
                        """
                        INSERT INTO system_knowledge_new (${insertColumns.joinToString(", ")})
                        SELECT ${selectExpressions.joinToString(", ")}
                        FROM system_knowledge
                        """.trimIndent()
                    )

                    val sourceCount = db.scalarLong("SELECT COUNT(*) FROM system_knowledge")
                    val targetCount = db.scalarLong("SELECT COUNT(*) FROM system_knowledge_new")
                    check(targetCount == sourceCount) {
                        "system_knowledge migration row count mismatch: source=$sourceCount target=$targetCount"
                    }

                    db.execSQL("DROP TABLE system_knowledge")
                }

                // Always materialize canonical `system_knowledge`:
                // - if legacy table existed: copied rows then swap
                // - if missing unexpectedly: create an empty valid table instead of crashing
                db.execSQL("ALTER TABLE system_knowledge_new RENAME TO system_knowledge")
            }
        }

        /**
         * Migration from v8 → v9:
         * Adds `messageId` (stable UUID string for each message) and `replyToMessageId`
         * (nullable reference to the messageId of the message being replied to) to the
         * `chat_messages` table. Enables WhatsApp-style threaded replies.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN messageId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN replyToMessageId TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_messageId ON chat_messages(messageId)")
            }
        }

        /**
         * Migration from v9 → v10:
         * Adds the Agent Brain 2.0 + Rollback + Live Repository Context Engine + Build
         * Doctor Pro tables. All tables are mobile-friendly (≤ 1KB embeddings, LRU
         * eviction columns, indexed candidates instead of vector ops in SQL).
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ─── Agent Brain 2.0: Reflexion Lessons ────────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS reflexion_lessons (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        toolName TEXT NOT NULL DEFAULT '',
                        lesson TEXT NOT NULL,
                        errorSignature TEXT NOT NULL DEFAULT '',
                        embedding BLOB NOT NULL,
                        successContext INTEGER NOT NULL DEFAULT 0,
                        useCount INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        lastUsedAt INTEGER NOT NULL DEFAULT 0,
                        quality REAL NOT NULL DEFAULT 0.5
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_reflex_tool ON reflexion_lessons(toolName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_reflex_sig ON reflexion_lessons(errorSignature)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_reflex_used ON reflexion_lessons(lastUsedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_reflex_quality ON reflexion_lessons(quality)")

                // ─── Agent Brain 2.0: Episodic Memory ──────────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS episodic_memory (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        summary TEXT NOT NULL,
                        userIntent TEXT NOT NULL DEFAULT '',
                        finalOutcome TEXT NOT NULL DEFAULT 'SUCCESS',
                        toolsUsedCsv TEXT NOT NULL DEFAULT '',
                        embedding BLOB NOT NULL,
                        iterationsCount INTEGER NOT NULL DEFAULT 0,
                        totalTimeMs INTEGER NOT NULL DEFAULT 0,
                        sessionId TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_ep_session ON episodic_memory(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_ep_outcome ON episodic_memory(finalOutcome)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_ep_time ON episodic_memory(createdAt)")

                // ─── Action Insurance: Rollback Snapshots ──────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS rollback_snapshots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        actionGroupId TEXT NOT NULL,
                        toolName TEXT NOT NULL DEFAULT '',
                        filePath TEXT NOT NULL,
                        existedBefore INTEGER NOT NULL DEFAULT 1,
                        contentBlob BLOB NOT NULL,
                        storedAsDiff INTEGER NOT NULL DEFAULT 0,
                        originalSizeBytes INTEGER NOT NULL DEFAULT 0,
                        originalHash TEXT NOT NULL DEFAULT '',
                        postEditHash TEXT NOT NULL DEFAULT '',
                        reason TEXT NOT NULL DEFAULT '',
                        rolledBack INTEGER NOT NULL DEFAULT 0,
                        pinned INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_rb_group ON rollback_snapshots(actionGroupId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_rb_path ON rollback_snapshots(filePath)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_rb_time ON rollback_snapshots(createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_rb_rolled ON rollback_snapshots(rolledBack)")

                // ─── Live Repository Context Engine: File Index ───────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS repo_file_index (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        scopePath TEXT NOT NULL,
                        filePath TEXT NOT NULL,
                        fileSize INTEGER NOT NULL DEFAULT 0,
                        fileMtime INTEGER NOT NULL DEFAULT 0,
                        contentHash TEXT NOT NULL DEFAULT '',
                        symbolCount INTEGER NOT NULL DEFAULT 0,
                        language TEXT NOT NULL DEFAULT 'other',
                        indexedAt INTEGER NOT NULL,
                        skipReason TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_rfi_scope ON repo_file_index(scopePath)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_rfi_path ON repo_file_index(scopePath, filePath)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_rfi_lang ON repo_file_index(language)")

                // ─── Live Repository Context Engine: Symbols ──────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS repo_symbols (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        scopePath TEXT NOT NULL,
                        symbolKind TEXT NOT NULL,
                        symbolName TEXT NOT NULL,
                        qualifiedName TEXT NOT NULL DEFAULT '',
                        filePath TEXT NOT NULL,
                        lineNumber INTEGER NOT NULL DEFAULT 0,
                        snippet TEXT NOT NULL DEFAULT '',
                        language TEXT NOT NULL DEFAULT 'other',
                        visibility TEXT NOT NULL DEFAULT '',
                        fileMtime INTEGER NOT NULL DEFAULT 0,
                        indexedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_sym_scope ON repo_symbols(scopePath)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_sym_name ON repo_symbols(symbolName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_sym_kind ON repo_symbols(symbolKind)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_sym_file ON repo_symbols(filePath)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_sym_qual ON repo_symbols(qualifiedName)")

                // ─── Build Doctor Pro: Diagnostics ────────────────────────────
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS build_diagnostics (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        errorFingerprint TEXT NOT NULL,
                        category TEXT NOT NULL DEFAULT 'Unknown',
                        message TEXT NOT NULL,
                        buildCommand TEXT NOT NULL DEFAULT '',
                        solutionDiff BLOB NOT NULL,
                        explanation TEXT NOT NULL DEFAULT '',
                        occurrenceCount INTEGER NOT NULL DEFAULT 1,
                        successfulFixCount INTEGER NOT NULL DEFAULT 0,
                        failedFixCount INTEGER NOT NULL DEFAULT 0,
                        lastSeenAt INTEGER NOT NULL,
                        lastFixedAt INTEGER NOT NULL DEFAULT 0,
                        reportedFiles TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_bd_fp ON build_diagnostics(errorFingerprint)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_bd_cat ON build_diagnostics(category)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_bd_seen ON build_diagnostics(lastSeenAt)")
            }
        }

        private fun SupportSQLiteDatabase.scalarLong(sql: String): Long =
            query(sql).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
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
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10
                    )
                    .build().also { INSTANCE = it }
            }
    }
}
