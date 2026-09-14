package com.omnidev.workspace.data.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.omnidev.workspace.data.db.entities.SystemKnowledgeEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class KnowledgePersistenceTest {
    private val db = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext, OmniDevDatabase::class.java
    ).build()
    private val dao get() = db.systemKnowledgeDao()

    @After fun close() = db.close()

    @Test fun concurrentDiscoveryKeepsOneSnapshot() = runBlocking {
        val entry = SystemKnowledgeEntry(knowledgeType = "SYSTEM_INFO", subject = "memory", content = "512 MB", source = "auto_discovery")
        (1..100).map { async(Dispatchers.IO) { dao.merge(entry) } }.awaitAll()
        assertEquals(1, dao.getCount())
        val original = dao.getAllValid().single()
        dao.merge(entry.copy(content = "1024 MB"))
        assertEquals(original.id, dao.getAllValid().single().id)
        assertEquals("1024 MB", dao.getAllValid().single().content)
    }

    @Test fun migrationArchivesDuplicatesButKeepsDistinctLearnedPatterns() = runBlocking {
        val entry = SystemKnowledgeEntry(knowledgeType = "SYSTEM_INFO", subject = "memory", content = "old", source = "auto_discovery")
        repeat(20) { dao.insert(entry) }
        dao.insert(entry.copy(content = "latest"))
        dao.insert(entry.copy(knowledgeType = "PATTERN", content = "pattern A"))
        dao.insert(entry.copy(knowledgeType = "PATTERN", content = "pattern B"))
        OmniDevDatabase.MIGRATION_13_14.migrate(db.openHelper.writableDatabase)
        assertEquals(3, dao.getCount())
        assertEquals("latest", dao.getByType("SYSTEM_INFO").single().content)
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM system_knowledge").use {
            it.moveToFirst()
            assertEquals(23, it.getInt(0)) // No historical rows destroyed.
        }
    }
}
