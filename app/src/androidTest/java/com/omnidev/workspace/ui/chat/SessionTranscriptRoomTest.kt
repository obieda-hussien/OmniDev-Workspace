package com.omnidev.workspace.ui.chat

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.db.entities.ChatSessionEntity
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class SessionTranscriptRoomTest {
    private val db = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext, OmniDevDatabase::class.java
    ).build()
    private val repo = ChatRepository(db.chatSessionDao(), db.chatMessageDao())

    @After fun close() = db.close()

    @Test fun exportsAllRowsAndKeepsEachConsoleWithItsOwnAiResponseEvenWhenTimestampsTie() = runBlocking {
        val sessionId = db.chatSessionDao().insert(ChatSessionEntity(title = "Full season"))
        repo.saveMessage(sessionId, ChatMessage(MessageRole.USER, "أول رسالة", timestamp = 1000L))
        repo.saveMessage(sessionId, ChatMessage(MessageRole.ASSISTANT, "First reply", timestamp = 1000L),
            listOf(AgentConsoleEntry.ToolEntry("search", "q=one", 1, timestamp = 1001L)))
        repo.saveMessage(sessionId, ChatMessage(MessageRole.ASSISTANT, "Second reply", timestamp = 1000L),
            listOf(AgentConsoleEntry.ToolEntry("read_file", "p=two", 2, timestamp = 1002L)))
        val transcript = requireNotNull(repo.loadSessionExport(sessionId))
        assertEquals("Full season", transcript.title)
        assertEquals(listOf("أول رسالة", "First reply", "Second reply"), transcript.turns.map { it.content })
        assertEquals(0, transcript.turns[0].consoleEntries.size)
        assertEquals("search", (transcript.turns[1].consoleEntries.single() as AgentConsoleEntry.ToolEntry).toolName)
        assertEquals("read_file", (transcript.turns[2].consoleEntries.single() as AgentConsoleEntry.ToolEntry).toolName)
        val output = SessionTranscriptExporter.render(transcript)
        assertTrue(output.indexOf("RUN    search") < output.indexOf("First reply"))
        assertTrue(output.indexOf("RUN    read_file") < output.indexOf("Second reply"))
        assertEquals(2, Regex("AGENT CONSOLE").findAll(output).count())
    }

    @Test fun deletedConversationCannotBeExported() = runBlocking {
        val id = db.chatSessionDao().insert(ChatSessionEntity(title = "Gone"))
        db.chatSessionDao().deleteById(id)
        assertNull(repo.loadSessionExport(id))
    }
}
