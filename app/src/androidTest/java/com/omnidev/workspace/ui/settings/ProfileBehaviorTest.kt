package com.omnidev.workspace.ui.settings

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omnidev.workspace.OmniDevApp
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.db.entities.KnowledgeSnippet
import com.omnidev.workspace.data.model.ProfilePersonalization
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.data.tools.MemoryManager
import com.omnidev.workspace.data.tools.VectorMemoryManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileBehaviorTest {
    @Test fun persistedOptOutGatesCanonicalAndLegacyToolsWithoutDeletingEntries() = runBlocking {
        val context = OmniDevApp.instance.applicationContext
        val settings = SettingsRepository(context)
        val previousName = settings.observeUserName().first().orEmpty()
        val previousPersona = settings.observeUserPersona().first().orEmpty()
        val previousPreferences = settings.observeProfilePersonalization().first()
        val database = Room.inMemoryDatabaseBuilder(context, OmniDevDatabase::class.java).build()
        try {
            val preferences = ProfilePersonalization(occupation = "Developer | designer", customInstructions = "Use precise examples\nKeep code readable",
                quickAnswers = true, suggestedPrompts = false, richResponses = false, memoryEnabled = false, referenceChatHistory = false)
            settings.setUserProfile("Fixture", "Fixture bio", preferences)
            val recreated = SettingsRepository(context)
            assertEquals(preferences, recreated.observeProfilePersonalization().first())
            assertTrue(recreated.observeUserPromptContext().first().contains(preferences.customInstructions))
            val dao = database.knowledgeDao()
            val id = dao.insert(KnowledgeSnippet(category = "user_preference", content = "PRIVATE MEMORY FIXTURE", tags = "fixture"))
            val memory = MemoryManager(dao)
            assertNull(memory.buildKnowledgeContext(includeSkills = false))
            assertNull(memory.buildHistoryContext("remember our previous chat", null))
            assertTrue(memory.executeTool("remember_fact", mapOf("content" to "new fact")).isError)
            assertTrue(memory.executeTool("search_knowledge", mapOf("query" to "fixture")).isError)
            val vector = VectorMemoryManager(dao)
            assertTrue(vector.executeTool("vector_store", mapOf("content" to "new fact")).isError)
            assertTrue(vector.executeTool("vector_search", mapOf("query" to "fixture")).isError)
            assertNotNull(dao.findById(id))
            settings.setUserProfile("Fixture", "Fixture bio", preferences.copy(memoryEnabled = true))
            assertTrue(memory.buildKnowledgeContext(includeSkills = false).orEmpty().contains("PRIVATE MEMORY FIXTURE"))
            settings.setUserProfile("Fixture", "Fixture bio", preferences)
            assertFalse(memory.executeTool("delete_memory", mapOf("id" to id.toString())).isError)
            assertNull(dao.findById(id))
        } finally {
            settings.setUserProfile(previousName, previousPersona, previousPreferences)
            database.close()
        }
    }
}
