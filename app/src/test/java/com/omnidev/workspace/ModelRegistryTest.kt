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

    @Test
    fun `allModels contains models from core providers`() {
        val providers = ModelRegistry.allModels.map { it.provider }.distinct()
        assertTrue("Should have Anthropic models", ModelProvider.ANTHROPIC in providers)
        assertTrue("Should have OpenAI models", ModelProvider.OPENAI in providers)
        assertTrue("Should have Gemini models", ModelProvider.GEMINI in providers)
        assertTrue("Should have Groq models", ModelProvider.GROQ in providers)
        assertTrue("Should have OpenRouter models", ModelProvider.OPEN_ROUTER in providers)
        // New 2026 providers
        assertTrue("Should have DeepSeek models", ModelProvider.DEEPSEEK in providers)
        assertTrue("Should have xAI models", ModelProvider.XAI in providers)
        assertTrue("Should have Mistral models", ModelProvider.MISTRAL in providers)
        assertTrue("Should have Cerebras models", ModelProvider.CEREBRAS in providers)
        assertTrue("Should have GitHub Copilot models", ModelProvider.GITHUB_COPILOT in providers)
    }

    @Test
    fun `registry has at least 50 models`() {
        assertTrue(
            "Registry should have at least 50 models, got ${ModelRegistry.allModels.size}",
            ModelRegistry.allModels.size >= 50
        )
    }

    @Test
    fun `modelsByProvider groups correctly`() {
        val grouped = ModelRegistry.modelsByProvider
        assertTrue("Should have more than 5 providers", grouped.size > 5)
        grouped.forEach { (provider, models) ->
            assertTrue("Provider $provider should have models", models.isNotEmpty())
            models.forEach { model ->
                assertEquals("Model ${model.id} should belong to $provider", provider, model.provider)
            }
        }
    }

    @Test
    fun `modelsByTier covers all tiers`() {
        val tiered = ModelRegistry.modelsByTier
        ModelTier.entries.forEach { tier ->
            val models = tiered[tier]
            assertNotNull("Should have models for tier $tier", models)
            assertTrue("Tier $tier should have at least one model", models!!.isNotEmpty())
        }
    }

    @Test
    fun `getModelById returns correct model for 2026 flagship`() {
        val model = ModelRegistry.getModelById("claude-opus-4-6")
        assertEquals("Claude Opus 4.6", model.displayName)
        assertEquals(ModelProvider.ANTHROPIC, model.provider)
        assertEquals(ModelTier.ORCHESTRATOR, model.tier)
        assertTrue(model.supportsVision)
        assertTrue(model.supportsThinking)
        assertTrue(model.isLatest)
        assertEquals(1_000_000, model.contextWindow)
    }

    @Test
    fun `getModelById returns DeepSeek R2`() {
        val model = ModelRegistry.getModelById("deepseek-r2")
        assertEquals(ModelProvider.DEEPSEEK, model.provider)
        assertEquals(ModelTier.ORCHESTRATOR, model.tier)
        assertTrue(model.supportsThinking)
        assertNotNull(model.costPer1MInputTokens)
    }

    @Test
    fun `getModelById returns Cerebras ultra-fast model`() {
        val model = ModelRegistry.getModelById("cerebras/llama-4-maverick-17b")
        assertEquals(ModelProvider.CEREBRAS, model.provider)
        assertEquals(ModelTier.FAST, model.tier)
        assertNotNull(model.speedTokensPerSecond)
        assertTrue(model.speedTokensPerSecond!! >= 1000)
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
    fun `default models are assigned for all roles and in registry`() {
        ModelRole.entries.forEach { role ->
            val defaultModel = ModelRegistry.getDefaultModelForRole(role)
            assertNotNull("Default model for $role should not be null", defaultModel)
            assertTrue("Default model should be in allModels", defaultModel in ModelRegistry.allModels)
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
    fun `gemini models support video`() {
        val geminiModels = ModelRegistry.modelsByProvider[ModelProvider.GEMINI] ?: emptyList()
        assertTrue("Should have Gemini models", geminiModels.isNotEmpty())
        assertTrue(
            "At least one Gemini model should support video",
            geminiModels.any { it.supportsVideo }
        )
    }

    @Test
    fun `all models have positive context windows`() {
        ModelRegistry.allModels.forEach { model ->
            assertTrue(
                "Model ${model.id} should have positive contextWindow",
                model.contextWindow > 0
            )
        }
    }

    @Test
    fun `getBestModelForTier returns a model for each tier`() {
        ModelTier.entries.forEach { tier ->
            val model = ModelRegistry.getBestModelForTier(tier)
            assertEquals("Model ${model.id} should be tier $tier", tier, model.tier)
        }
    }

    @Test
    fun `latestByProvider has one entry per provider`() {
        val latest = ModelRegistry.latestByProvider
        ModelRegistry.modelsByProvider.keys.forEach { provider ->
            assertNotNull("Should have latest model for $provider", latest[provider])
        }
    }

    @Test
    fun `models with pricing have positive costs`() {
        ModelRegistry.allModels
            .filter { it.costPer1MInputTokens != null }
            .forEach { model ->
                assertTrue(
                    "Model ${model.id} input cost should be positive",
                    model.costPer1MInputTokens!! > 0
                )
                assertTrue(
                    "Model ${model.id} output cost should be positive",
                    model.costPer1MOutputTokens!! > 0
                )
            }
    }

    @Test
    fun `models with speed have positive token rate`() {
        ModelRegistry.allModels
            .filter { it.speedTokensPerSecond != null }
            .forEach { model ->
                assertTrue(
                    "Model ${model.id} speed should be positive",
                    model.speedTokensPerSecond!! > 0
                )
            }
    }

    @Test
    fun `FAST tier models have speed populated and exceed reasonable threshold`() {
        val fastModels = ModelRegistry.getModelsForTier(ModelTier.FAST)
        assertTrue("Should have FAST tier models", fastModels.isNotEmpty())
        // At least half of FAST models should advertise a speed
        val withSpeed = fastModels.count { it.speedTokensPerSecond != null }
        assertTrue(
            "Most FAST tier models should have speedTokensPerSecond, got $withSpeed/${fastModels.size}",
            withSpeed >= fastModels.size / 2
        )
        // All populated speed values should be at least 100 t/s to qualify as "fast"
        fastModels.filter { it.speedTokensPerSecond != null }.forEach { model ->
            assertTrue(
                "FAST model ${model.id} speed ${model.speedTokensPerSecond} should be >= 100 t/s",
                model.speedTokensPerSecond!! >= 100
            )
        }
    }

    @Test
    fun `GitHub Copilot models exist and are routed to GITHUB_COPILOT provider`() {
        val copilotModels = ModelRegistry.modelsByProvider[ModelProvider.GITHUB_COPILOT]
        assertNotNull("Should have GitHub Copilot models", copilotModels)
        assertTrue("Should have at least 2 GitHub Copilot models", copilotModels!!.size >= 2)
        copilotModels.forEach { model ->
            assertTrue(
                "Copilot model ${model.id} should start with 'copilot/'",
                model.id.startsWith("copilot/")
            )
            assertEquals(ModelProvider.GITHUB_COPILOT, model.provider)
        }
    }
}

