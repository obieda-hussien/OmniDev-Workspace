package com.omnidev.workspace.data.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.omnidev.workspace.data.db.entities.*
import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class LastTurnReplacementTest {
    private val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, OmniDevDatabase::class.java).build()
    private val dao get() = db.chatMessageDao()
    @After fun close() = db.close()

    @Test fun replacementDeletesAllOutputsAndKeepsPreviousTurnsAndOtherSessions() = runBlocking {
        val session = db.chatSessionDao().insert(ChatSessionEntity(title = "Agent"))
        val other = db.chatSessionDao().insert(ChatSessionEntity(title = "Other"))
        suspend fun add(role: String, id: String, sessionId: Long = session) = dao.insert(ChatMessageEntity(sessionId = sessionId, role = role, content = id, messageId = id))
        add("USER", "u1"); add("ASSISTANT", "a1"); val row = add("USER", "u2")
        add("ASSISTANT", "media"); add("TOOL", "tool"); add("ASSISTANT", "answer"); add("ASSISTANT", "handoff"); add("USER", "other", other)
        val revised = ChatMessageEntity(sessionId = session, role = "USER", content = "edited", messageId = "revision")
        assertTrue(dao.replaceLastTurn("u2", revised))
        val rows = dao.getBySession(session)
        assertEquals(listOf("u1", "a1", "revision"), rows.map { it.messageId }); assertEquals(row, rows.last().id)
        assertEquals("edited", rows.last().content); assertEquals(1, dao.getBySession(other).size)
        dao.insert(ChatMessageEntity(sessionId = session, role = "ASSISTANT", content = "new", messageId = "new"))
        assertTrue(dao.replaceLastTurn("revision", revised.copy(messageId = "revision2")))
        assertEquals(listOf("u1", "a1", "revision2"), dao.getBySession(session).map { it.messageId })
    }

    @Test fun staleRequestCannotEditAnEarlierUserOrDeleteOutputs() = runBlocking {
        val session = db.chatSessionDao().insert(ChatSessionEntity(title = "Chat"))
        for (id in listOf("first", "last")) dao.insert(ChatMessageEntity(sessionId = session, role = "USER", content = id, messageId = id))
        dao.insert(ChatMessageEntity(sessionId = session, role = "ASSISTANT", content = "answer"))
        assertFalse(dao.replaceLastTurn("first", ChatMessageEntity(sessionId = session, role = "USER", content = "bad")))
        assertEquals(3, dao.getBySession(session).size)
        db.chatSessionDao().deleteById(session)
        assertFalse(dao.replaceLastTurn("last", ChatMessageEntity(sessionId = session, role = "USER", content = "bad")))
    }

    @Test fun lateMediaCannotResurrectReplacedTurnButNewAndEarlierOriginsStillDeliverOnce() = runBlocking {
        val session = db.chatSessionDao().insert(ChatSessionEntity(title = "Media"))
        dao.insert(ChatMessageEntity(sessionId = session, role = "USER", content = "older", messageId = "older", timestamp = 10))
        dao.insert(ChatMessageEntity(sessionId = session, role = "USER", content = "old", messageId = "old", timestamp = 20))
        val media = ChatMessageEntity(sessionId = session, role = "ASSISTANT", content = "ready", messageId = "media-result:old")
        assertTrue(dao.replaceLastTurn("old", ChatMessageEntity(sessionId = session, role = "USER", content = "new", messageId = "new", timestamp = 100)))
        assertEquals(-1L, dao.insertMediaResult(media, "old", 25))
        assertEquals(-1L, dao.insertMediaResult(media, null, 25))
        assertTrue(dao.insertMediaResult(media.copy(messageId = "media-result:earlier"), "older", 15) > 0)
        val inserted = dao.insertMediaResult(media.copy(messageId = "media-result:new"), "new", 110)
        assertTrue(inserted > 0); assertEquals(inserted, dao.insertMediaResult(media.copy(messageId = "media-result:new"), "new", 110))
    }

    @Test fun delayedMediaFromEarlierTurnsSurvivesLatestTurnReplacement() = runBlocking {
        val session = db.chatSessionDao().insert(ChatSessionEntity(title = "Media"))
        dao.insert(ChatMessageEntity(sessionId = session, role = "USER", content = "earlier", messageId = "earlier"))
        dao.insert(ChatMessageEntity(sessionId = session, role = "USER", content = "latest", messageId = "latest"))
        dao.insert(ChatMessageEntity(sessionId = session, role = "ASSISTANT", content = "answer", messageId = "answer"))
        dao.insertMediaResult(ChatMessageEntity(sessionId = session, role = "ASSISTANT", content = "earlier file", messageId = "media-result:earlier", replyToMessageId = "earlier"), "earlier", 100)
        assertTrue(dao.replaceLastTurn("latest", ChatMessageEntity(sessionId = session, role = "USER", content = "edited", messageId = "edited")))
        assertEquals(setOf("earlier", "edited", "media-result:earlier"), dao.getBySession(session).map { it.messageId }.toSet())
    }

    @Test fun userTextModeReplyAndAttachmentsSurviveReloadWithoutNeedingSchemaMigration() = runBlocking {
        val session = db.chatSessionDao().insert(ChatSessionEntity(title = "Team"))
        val repo = ChatRepository(db.chatSessionDao(), dao)
        val attachment = AttachmentMeta("file:///screen.png", "image/png", "screen.png", 20, AttachmentMediaType.IMAGE)
        val user = ChatMessage(MessageRole.USER, "decorated", messageId = "original", userInput = "original", userMode = "SWARM", userScopePath = "/original/project", attachments = listOf(attachment))
        repo.saveMessage(session, user)
        val revised = user.copy(messageId = "revision", content = "edited decorated", userInput = "edited", replyToMessageId = "earlier")
        assertTrue(repo.replaceLastTurn(session, "original", revised))
        val restored = repo.loadMessages(session).first.single()
        assertEquals("edited", restored.userInput); assertEquals("SWARM", restored.userMode); assertEquals("/original/project", restored.userScopePath)
        assertEquals(listOf(attachment), restored.attachments); assertEquals("earlier", restored.replyToMessageId)
        assertNull(repo.getMessageById("original"))
    }
}
