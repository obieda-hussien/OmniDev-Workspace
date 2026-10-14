package com.omnidev.workspace.data.repository

import com.omnidev.workspace.data.db.dao.ChatMessageDao
import com.omnidev.workspace.data.db.dao.ChatSessionDao
import com.omnidev.workspace.data.db.entities.ChatMessageEntity
import com.omnidev.workspace.data.model.AttachmentMediaType
import com.omnidev.workspace.data.model.AttachmentMeta
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.ui.chat.AgentConsoleEntry
import com.omnidev.workspace.ui.chat.AgentConsoleSerializer
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ChatRepositoryTest {
    private inline fun <reified T> unusedDao(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)
    ) { _, method, _ -> error("Unexpected second database access: ${method.name}") } as T

    private fun repository() = ChatRepository(unusedDao<ChatSessionDao>(), unusedDao<ChatMessageDao>())

    @Test fun restoresAnEmittedTranscriptWithoutAnotherDatabaseQueryOrLostMetadata() = runTest {
        val attachment = AttachmentMeta("content://test/image", "image/png", "photo.png", 512, AttachmentMediaType.IMAGE)
        val metadata = ChatMessage(MessageRole.USER, "", attachments = listOf(attachment),
            userInput = "Original composer text", userMode = "CHAT", userScopePath = "/project")
        val console = listOf(AgentConsoleEntry.PhaseEntry("Verify", "Complete", timestamp = 5, id = 6))
        val rows = listOf(
            ChatMessageEntity(id = 1, sessionId = 7, role = "USER", content = "Expanded context",
                timestamp = 1, messageId = "user", metadataJson = Json.encodeToString(metadata)),
            ChatMessageEntity(id = 2, sessionId = 7, role = "ASSISTANT", content = "الرد — response",
                timestamp = 2, messageId = "reply", replyToMessageId = "user",
                consoleEntriesJson = AgentConsoleSerializer.serialize(console))
        )
        val (messages, consoles) = repository().decodeMessages(rows)
        assertEquals(listOf("user", "reply"), messages.map { it.messageId })
        assertEquals("Expanded context", messages.first().content)
        assertEquals(metadata.userInput, messages.first().userInput)
        assertEquals(metadata.userMode, messages.first().userMode)
        assertEquals(metadata.userScopePath, messages.first().userScopePath)
        assertEquals(listOf(attachment), messages.first().attachments)
        assertEquals("user", messages.last().replyToMessageId)
        assertEquals(console, consoles[2L])
    }

    @Test fun malformedAndLegacyMetadataNeverHideStoredMessages() = runTest {
        val rows = listOf(
            ChatMessageEntity(id = 31, sessionId = 1, role = "USER", content = "legacy", metadataJson = ""),
            ChatMessageEntity(id = 32, sessionId = 1, role = "UNKNOWN", content = "keep me",
                metadataJson = "{broken", consoleEntriesJson = "{broken")
        )
        val (messages, consoles) = repository().decodeMessages(rows)
        assertEquals(listOf("31", "32"), messages.map { it.messageId })
        assertEquals(listOf("legacy", "keep me"), messages.map { it.content })
        assertTrue(messages.all { it.role == MessageRole.USER && it.attachments.isEmpty() })
        assertTrue(consoles.values.all { it.isEmpty() })
    }
}
