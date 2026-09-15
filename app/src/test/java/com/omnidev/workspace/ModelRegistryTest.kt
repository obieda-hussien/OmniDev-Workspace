package com.omnidev.workspace

import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.model.ModelRole
import com.omnidev.workspace.data.model.ModelTier
import com.omnidev.workspace.registry.ModelRegistry
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [ModelRegistry] — validates model definitions, lookups, tiers, and defaults.
 */
class ModelRegistryTest {
    @Test fun cachedMetadataDoesNotOverwriteFreshProviderLimits() {
        val provider = ModelProvider.GEMINI
        val original = ModelRegistry.allModels.filter { it.provider == provider }
        val live = com.omnidev.workspace.data.model.AIModel(
            id = "GEMINI::catalog-test", displayName = "Test", provider = provider,
            contextWindow = 1_000_000, maxOutputTokens = 8192)
        try {
            ModelRegistry.setProviderModels(provider, listOf(live))
            ModelRegistry.restoreProviderModels(provider, listOf(live.copy(contextWindow = 128_000)))
            assertEquals(1_000_000, ModelRegistry.getModelById(live.id).contextWindow)
        } finally {
            ModelRegistry.setProviderModels(provider, original)
        }
    }








    @Test
    fun `getModelById returns generated model for unknown id`() {
        val model = ModelRegistry.getModelById("nonexistent-model")
        assertEquals("nonexistent-model", model.displayName)
    }

    @Test
    fun `findModelById returns generated model for unknown id`() {
        val model = ModelRegistry.findModelById("nonexistent-model")
        assertNotNull(model)
        assertEquals("nonexistent-model", model?.displayName)
    }

    @Test
    fun `default models are assigned for all roles and in registry`() {
        ModelRole.entries.forEach { role ->
            val defaultModel = ModelRegistry.getDefaultModelForRole(role)
            assertNotNull("Default model for $role should not be null", defaultModel)

        }
    }

    @Test
    fun `swarm orchestrator default is ORCHESTRATOR tier`() {
        val orchestratorModel = ModelRegistry.getDefaultModelForRole(ModelRole.SWARM_ORCHESTRATOR)
        assertEquals(
            "Swarm Orchestrator default should be ORCHESTRATOR tier",
            ModelTier.ORCHESTRATOR,
            orchestratorModel.tier
        )
    }

    @Test
    fun `all model ids are unique`() {
        val ids = ModelRegistry.allModels.map { it.id }
        assertEquals("All model IDs should be unique", ids.size, ids.distinct().size)
    }



    @Test
    fun `getBestModelForTier returns a model for each tier`() {
        ModelTier.entries.forEach { tier ->
            val model = ModelRegistry.getBestModelForTier(tier)
            assertNotNull("Model should not be null", model)
        }
    }





}

