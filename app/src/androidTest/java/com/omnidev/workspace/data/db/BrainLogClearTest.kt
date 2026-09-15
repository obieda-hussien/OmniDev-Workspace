package com.omnidev.workspace.data.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.omnidev.workspace.data.db.entities.SystemKnowledgeEntry
import com.omnidev.workspace.data.db.entities.ToolExecutionEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class BrainLogClearTest {
    private val db = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext, OmniDevDatabase::class.java).build()
    @After fun close() = db.close()

    @Test fun clearingKnowledgeDoesNotClearActivityAndRemovesArchivedRows() = runBlocking {
        val knowledge = db.systemKnowledgeDao()
        val activity = db.toolExecutionDao()
        knowledge.insert(SystemKnowledgeEntry(knowledgeType = "TEST", subject = "a", content = "a", source = "test"))
        knowledge.insert(SystemKnowledgeEntry(knowledgeType = "TEST", subject = "b", content = "b", source = "test", isValid = false))
        activity.insert(ToolExecutionEntry(toolName = "read", success = true, executionTimeMs = 1))
        knowledge.clearAll()
        assertTrue(knowledge.observeRecent().first().isEmpty())
        assertEquals(1, activity.observeRecent().first().size)
        knowledge.insert(SystemKnowledgeEntry(knowledgeType = "TEST", subject = "c", content = "c", source = "test"))
        activity.clearAll()
        assertTrue(activity.observeRecent().first().isEmpty())
        assertEquals(1, knowledge.getCount())
    }
}
