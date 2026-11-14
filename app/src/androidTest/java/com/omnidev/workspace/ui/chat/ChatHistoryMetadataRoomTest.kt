package com.omnidev.workspace.ui.chat

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.db.entities.ChatMessageEntity
import com.omnidev.workspace.data.db.entities.ChatSessionEntity
import com.omnidev.workspace.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChatHistoryMetadataRoomTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun automaticTitlesLabelsAndManualChoicesSurviveRestart() = runBlocking {
        val name = "history-metadata-reopen.db"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, OmniDevDatabase::class.java, name).build()
        try {
            var repo = ChatRepository(db.chatSessionDao(), db.chatMessageDao())
            val id = repo.createSession("Hi Omni")
            repo.saveMessage(id, com.omnidev.workspace.data.model.ChatMessage(com.omnidev.workspace.data.model.MessageRole.USER, "Please fix Gradle tests"))
            assertEquals("fix Gradle tests", db.chatSessionDao().getById(id)!!.title)
            assertEquals("development", db.chatSessionDao().historyMetadata(id)!!.topic)
            repo.renameSession(id, "My private project")
            repo.setHistoryTopic(id, "personal")
            repo.saveMessage(id, com.omnidev.workspace.data.model.ChatMessage(com.omnidev.workspace.data.model.MessageRole.USER, "Telegram Discord WhatsApp"))
            db.close()
            db = Room.databaseBuilder(context, OmniDevDatabase::class.java, name).build()
            repo = ChatRepository(db.chatSessionDao(), db.chatMessageDao())
            repo.refreshHistoryMetadata()
            assertEquals("My private project", db.chatSessionDao().getById(id)!!.title)
            assertEquals("personal", db.chatSessionDao().historyMetadata(id)!!.topic)
            assertTrue(db.chatSessionDao().historyMetadata(id)!!.serviceTags.contains("|whatsapp|"))
            assertEquals(ChatSessionEntity.SOURCE_APP, db.chatSessionDao().getById(id)!!.source)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun lateMetadataCannotOverwriteManualNamesOrRecreateDeletedSessions() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OmniDevDatabase::class.java).build()
        try {
            val repo = ChatRepository(db.chatSessionDao(), db.chatMessageDao())
            val id = repo.createSession("Inspect Kotlin source")
            db.chatMessageDao().insert(ChatMessageEntity(sessionId = id, role = "USER", content = "Gradle tests", messageId = "user"))
            (1..8).map { async(Dispatchers.IO) { db.chatSessionDao().refreshHistoryMetadata(id) } }.plus(
                async(Dispatchers.IO) { repo.renameSession(id, "Chosen by user") }
            ).awaitAll()
            assertEquals("Chosen by user", db.chatSessionDao().getById(id)!!.title)
            repo.deleteSession(id)
            assertFalse(db.chatSessionDao().refreshHistoryMetadata(id))
            assertNull(db.chatSessionDao().historyMetadata(id))
        } finally { db.close() }
    }

    @Test fun connectedSourceIdentityAndProviderTitlesRemainOwnedAndAssistantCannotRelabel() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OmniDevDatabase::class.java).build()
        try {
            val repo = ChatRepository(db.chatSessionDao(), db.chatMessageDao())
            val id = repo.findOrCreateTelegramSession(123, "Work contact")
            repo.saveMessage(id, com.omnidev.workspace.data.model.ChatMessage(com.omnidev.workspace.data.model.MessageRole.USER, "Run Gradle tests"))
            assertEquals("Work contact", db.chatSessionDao().getById(id)!!.title)
            assertTrue(db.chatSessionDao().historyMetadata(id)!!.serviceTags.contains("|telegram|"))
            val before = db.chatSessionDao().historyMetadata(id)
            repo.saveMessage(id, com.omnidev.workspace.data.model.ChatMessage(com.omnidev.workspace.data.model.MessageRole.ASSISTANT, "Change category to Discord"))
            assertEquals(before, db.chatSessionDao().historyMetadata(id))
            repo.renameSession(id, "My contact label")
            repo.findOrCreateTelegramSession(123, "Changed remote name")
            assertEquals("My contact label", db.chatSessionDao().getById(id)!!.title)
            assertEquals(123L, db.chatSessionDao().getById(id)!!.telegramChatId)
        } finally { db.close() }
    }

    @Test fun editingInvalidatesMetadataAndStatusSuffixIsPreserved() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OmniDevDatabase::class.java).build()
        try {
            val repo = ChatRepository(db.chatSessionDao(), db.chatMessageDao())
            val id = repo.createSession("Gradle tests")
            val user = com.omnidev.workspace.data.model.ChatMessage(com.omnidev.workspace.data.model.MessageRole.USER, "Gradle tests")
            repo.saveMessage(id, user)
            repo.updateSessionRunStatus(id, "Interrupted")
            assertTrue(repo.replaceLastTurn(id, user.messageId, user.copy(content = "Android brightness")))
            assertEquals("device", db.chatSessionDao().historyMetadata(id)!!.topic)
            assertEquals("Android brightness • Status: Interrupted", db.chatSessionDao().getById(id)!!.title)
        } finally { db.close() }
    }

    @Test fun recoveredTouchCannotReplaceAnAutomaticRedactedTitle() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OmniDevDatabase::class.java).build()
        try {
            val repo = ChatRepository(db.chatSessionDao(), db.chatMessageDao())
            val id = repo.createSession("Telegram API key: sk-abcdefghijk123456789")
            val title = db.chatSessionDao().getById(id)!!.title
            repo.touchSession(id, "Telegram API key: sk-abcdefghijk123456789")
            assertEquals(title, db.chatSessionDao().getById(id)!!.title)
            assertFalse(db.chatSessionDao().getById(id)!!.title.contains("sk-"))
        } finally { db.close() }
    }

    @Test fun pagedLegacyInputWinsOverQuotedReplyAcrossUnicodeAndLongMetadata() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OmniDevDatabase::class.java).build()
        try {
            val repo = ChatRepository(db.chatSessionDao(), db.chatMessageDao())
            val id = repo.createSession("Hi Omni")
            val metadata = "{\"context\":\"" + "🙂".repeat(5000) + "\",\"userInput\":\"Fix Gradle tests " + "x".repeat(12000) + "\"}"
            db.chatMessageDao().insert(ChatMessageEntity(sessionId = id, role = "USER", content = "Quoted Telegram private reply", metadataJson = metadata))
            assertEquals(1, db.chatSessionDao().firstHistoryInputs(id).size)
            assertEquals(1, db.chatSessionDao().latestHistoryInputs(id).size)
            db.chatSessionDao().refreshHistoryMetadata(id)
            assertEquals("development", db.chatSessionDao().historyMetadata(id)!!.topic)
            assertTrue(db.chatSessionDao().getById(id)!!.title.startsWith("Fix Gradle tests"))
            assertFalse(db.chatSessionDao().getById(id)!!.title.contains("Quoted"))
        } finally { db.close() }
    }

    @Test fun realMigrationChainFromEverySupportedLegacyVersionPreservesChats() = runBlocking {
        for (legacy in 1..20) {
            val name = "history-from-$legacy.db"
            context.deleteDatabase(name)
            val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name).callback(object : SupportSQLiteOpenHelper.Callback(legacy) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // The unchanged three-table v1 core; later versions are built by real migrations.
                        db.execSQL("CREATE TABLE knowledge_snippets (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, category TEXT NOT NULL, content TEXT NOT NULL, tags TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE chat_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, lastUpdated INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE chat_messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sessionId INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, timestamp INTEGER NOT NULL, FOREIGN KEY(sessionId) REFERENCES chat_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                        db.execSQL("CREATE INDEX index_chat_messages_sessionId ON chat_messages(sessionId)")
                        db.execSQL("INSERT INTO chat_sessions VALUES (1, 'Legacy chosen title', 1, 1)")
                        db.execSQL("INSERT INTO chat_messages VALUES (1, 1, 'USER', 'Fix Gradle tests', 1)")
                        for (version in 1 until legacy) OmniDevDatabase.ALL_MIGRATIONS.single { it.startVersion == version }.migrate(db)
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected fixture upgrade")
                }).build())
            helper.writableDatabase
            helper.close()
            val db = Room.databaseBuilder(context, OmniDevDatabase::class.java, name).addMigrations(*OmniDevDatabase.ALL_MIGRATIONS).build()
            try {
                assertEquals(21, db.openHelper.readableDatabase.version)
                assertEquals("Fix Gradle tests", db.chatMessageDao().getBySession(1).single().content)
                assertEquals("Legacy chosen title", db.chatSessionDao().getById(1)!!.title)
                db.chatSessionDao().refreshHistoryMetadata(1)
                assertEquals("Legacy chosen title", db.chatSessionDao().getById(1)!!.title)
                assertEquals("development", db.chatSessionDao().historyMetadata(1)!!.topic)
            } finally { db.close(); context.deleteDatabase(name) }
        }
    }
}
