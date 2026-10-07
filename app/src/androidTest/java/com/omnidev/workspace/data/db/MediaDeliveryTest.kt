package com.omnidev.workspace.data.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.omnidev.workspace.data.db.entities.ChatMessageEntity
import com.omnidev.workspace.data.db.entities.ChatSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class MediaDeliveryTest {
    private val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, OmniDevDatabase::class.java).build()
    @After fun close() = db.close()
    @Test fun workerRetryInsertsOnlyOneReadyMessageAndDeletedChatStaysDeleted() = runBlocking {
        val session = db.chatSessionDao().insert(ChatSessionEntity(title = "Media"))
        val message = ChatMessageEntity(sessionId = session, role = "ASSISTANT", content = "Video ready", messageId = "media-result:job")
        val first = db.chatMessageDao().insertOnce(message)
        assertEquals(first, db.chatMessageDao().insertOnce(message))
        assertEquals(1, db.chatMessageDao().getBySession(session).size)
        db.chatSessionDao().deleteById(session)
        assertEquals(-1L, db.chatMessageDao().insertOnce(message))
        assertFalse(db.chatMessageDao().sessionExists(session))
    }
}
