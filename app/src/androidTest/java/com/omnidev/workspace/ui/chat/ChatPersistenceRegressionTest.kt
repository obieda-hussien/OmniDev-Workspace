package com.omnidev.workspace.ui.chat

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.db.entities.ChatMessageEntity
import com.omnidev.workspace.data.db.entities.ChatSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatPersistenceRegressionTest {
    @Test fun mediaObservationExcludesOrdinaryAndOtherSessionMessages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, OmniDevDatabase::class.java).build()
        try {
            val session = db.chatSessionDao().insert(ChatSessionEntity(title = "Current"))
            val other = db.chatSessionDao().insert(ChatSessionEntity(title = "Other"))
            val dao = db.chatMessageDao()
            dao.insert(ChatMessageEntity(sessionId = session, role = "ASSISTANT", messageId = "ordinary",
                content = "a".repeat(100_000)))
            dao.insert(ChatMessageEntity(sessionId = other, role = "ASSISTANT", messageId = "media-result:other", content = "other"))
            val mediaId = dao.insert(ChatMessageEntity(sessionId = session, role = "ASSISTANT",
                messageId = "media-result:current", content = "current"))
            assertEquals(listOf(mediaId), dao.observeMediaBySession(session).first().map { it.id })
            dao.updateProgress(mediaId, "updated", "")
            assertEquals("updated", dao.observeMediaBySession(session).first().single().content)
        } finally {
            db.close()
        }
    }
}
