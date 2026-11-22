package com.omnidev.workspace.data.network

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.db.entities.ChatSessionEntity
import com.omnidev.workspace.data.model.ModelRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AutoModelPinRoomTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun modelPinSurvivesDatabaseReopenAndSessionDeletionCleansItsRows() = runBlocking {
        val filename = "auto-affinity-reopen.db"
        context.deleteDatabase(filename)
        var db = Room.databaseBuilder(context, OmniDevDatabase::class.java, filename).build()
        try {
            val id = db.chatSessionDao().insert(ChatSessionEntity(title = "Original chat"))
            val other = db.chatSessionDao().insert(ChatSessionEntity(title = "Another chat"))
            val scope = AutoModelPinScope.forSession(id, ModelRole.CHAT)!!
            val another = AutoModelPinScope.forSession(other, ModelRole.CHAT)!!
            val agent = AutoModelPinScope.forSession(id, ModelRole.AGENT)!!
            val store = RoomAutoModelPinStore(db.chatSessionDao())
            assertNull(store.read(scope))
            assertEquals("GEMINI::first", store.claim(scope, "GEMINI::first"))
            assertEquals("OPENAI::agent", store.claim(agent, "OPENAI::agent"))
            assertEquals("OPENAI::other", store.claim(another, "OPENAI::other"))
            db.close()
            db = Room.databaseBuilder(context, OmniDevDatabase::class.java, filename).build()
            val restored = RoomAutoModelPinStore(db.chatSessionDao())
            assertEquals("GEMINI::first", restored.read(scope))
            assertEquals("GEMINI::first", restored.claim(scope, "OPENAI::different"))
            assertEquals("OPENAI::agent", restored.read(agent))
            db.chatSessionDao().deleteById(id)
            assertNull(restored.read(scope))
            assertNull(restored.read(agent))
            assertEquals("OPENAI::other", restored.read(another))
        } finally {
            db.close()
            context.deleteDatabase(filename)
        }
    }

    @Test fun simultaneousClaimsShareExactlyOneCommittedModel() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OmniDevDatabase::class.java).build()
        try {
            val id = db.chatSessionDao().insert(ChatSessionEntity(title = "Concurrent chat"))
            val scope = AutoModelPinScope.forSession(id, ModelRole.AGENT)!!
            val outcomes = (1..12).map { index -> async(Dispatchers.IO) {
                RoomAutoModelPinStore(db.chatSessionDao()).claim(scope, "GEMINI::model-$index")
            } }.awaitAll()
            assertEquals(1, outcomes.toSet().size)
            assertEquals(outcomes.first(), RoomAutoModelPinStore(db.chatSessionDao()).read(scope))
        } finally { db.close() }
    }

    @Test fun migrationFrom21PreservesSessionAndCreatesPinStorage() = runBlocking {
        val filename = "auto-affinity-migrate.db"
        context.deleteDatabase(filename)
        var db = Room.databaseBuilder(context, OmniDevDatabase::class.java, filename).build()
        try {
            val id = db.chatSessionDao().insert(ChatSessionEntity(title = "Legacy conversation"))
            db.openHelper.writableDatabase.execSQL("DROP TABLE auto_model_pins")
            db.openHelper.writableDatabase.execSQL("PRAGMA user_version = 21")
            db.close()
            db = Room.databaseBuilder(context, OmniDevDatabase::class.java, filename)
                .addMigrations(*OmniDevDatabase.ALL_MIGRATIONS).build()
            assertEquals("Legacy conversation", db.chatSessionDao().getById(id)!!.title)
            val scope = AutoModelPinScope.forSession(id, ModelRole.CHAT)!!
            assertEquals("GEMINI::new", RoomAutoModelPinStore(db.chatSessionDao()).claim(scope, "GEMINI::new"))
            assertEquals(22, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
            context.deleteDatabase(filename)
        }
    }
}
