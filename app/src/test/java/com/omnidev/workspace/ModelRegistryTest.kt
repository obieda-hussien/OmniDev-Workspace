package com.omnidev.workspace

import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.model.ModelRole
import com.omnidev.workspace.registry.ModelRegistry
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [ModelRegistry] — validates model definitions, lookups, and defaults.
 */
class ModelRegistryTest {

    @Test
    fun `allModels contains models from all providers`() {
        val providers = ModelRegistry.allModels.map { it.provider }.distinct()
        assertTrue("Should have Anthropic models", ModelProvider.ANTHROPIC in providers)
        assertTrue("Should have OpenAI models", ModelProvider.OPENAI in providers)
        assertTrue("Should have Gemini models", ModelProvider.GEMINI in providers)
        assertTrue("Should have Groq models", ModelProvider.GROQ in providers)
        assertTrue("Should have OpenRouter models", ModelProvider.OPEN_ROUTER in providers)
    }

    @Test
    fun `modelsByProvider groups correctly`() {
        val grouped = ModelRegistry.modelsByProvider
        assertEquals(5, grouped.size)
        grouped.forEach { (provider, models) ->
            assertTrue("Provider $provider should have models", models.isNotEmpty())
            models.forEach { model ->
                assertEquals("Model ${model.id} should belong to $provider", provider, model.provider)
            }
        }
    }

    @Test
    fun `getModelById returns correct model`() {
        val model = ModelRegistry.getModelById("claude-sonnet-4-20250514")
        assertEquals("Claude Sonnet 4", model.displayName)
        assertEquals(ModelProvider.ANTHROPIC, model.provider)
        assertTrue(model.supportsVision)
        assertTrue(model.supportsThinking)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `getModelById throws for unknown id`() {
        ModelRegistry.getModelById("nonexistent-model")
    }

    @Test
    fun `findModelById returns null for unknown id`() {
        assertNull(ModelRegistry.findModelById("nonexistent-model"))
    }

    @Test
    fun `default models are assigned for all roles`() {
        ModelRole.entries.forEach { role ->
            val defaultModel = ModelRegistry.getDefaultModelForRole(role)
            assertNotNull("Default model for $role should not be null", defaultModel)
            assertTrue("Default model should be in allModels", defaultModel in ModelRegistry.allModels)
        }
    }

    @Test
    fun `all model ids are unique`() {
        val ids = ModelRegistry.allModels.map { it.id }
        assertEquals("All model IDs should be unique", ids.size, ids.distinct().size)
    }

    @Test
    fun `gemini models support video`() {
        val geminiModels = ModelRegistry.modelsByProvider[ModelProvider.GEMINI] ?: emptyList()
        assertTrue("Should have Gemini models", geminiModels.isNotEmpty())
        assertTrue(
            "At least one Gemini model should support video",
            geminiModels.any { it.supportsVideo }
        )
    }
}
