package com.omnidev.workspace.data.routing

import com.omnidev.workspace.data.model.AIModel
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.model.ModelRole
import com.omnidev.workspace.data.model.ModelTier
import org.junit.Assert.*
import org.junit.Test

class AutoModelRouterTest {
    private fun model(
        id: String,
        provider: ModelProvider = ModelProvider.OPEN_ROUTER,
        input: Double? = null,
        output: Double? = null,
        tools: Boolean = true,
        vision: Boolean = false,
        tier: ModelTier = ModelTier.EXECUTOR
    ) = AIModel(
        id = id, displayName = id, provider = provider, tier = tier,
        contextWindow = 128000, maxOutputTokens = 4096,
        supportsFunctionCalling = tools, supportsVision = vision,
        costPer1MInputTokens = input, costPer1MOutputTokens = output
    )

    @Test fun freeOnlyDoesNotAssumeUnknownPricingIsFree() {
        val free = model("OPEN_ROUTER::sample:free")
        val unknown = model("OPEN_ROUTER::unknown")
        val paid = model("OPEN_ROUTER::paid", input = 0.3, output = 0.6)
        val result = AutoModelRouter.candidates(
            listOf(unknown, paid, free), setOf(ModelProvider.OPEN_ROUTER),
            ModelRole.CHAT, AutoModelRouter.FREE_ONLY, false
        )
        assertEquals(listOf(free.id), result.map { it.id })
    }

    @Test fun paidFallbackIsOptInAndFreeIsPreferred() {
        val paid = model("OPEN_ROUTER::paid", input = 0.1, output = 0.2)
        val free = model("OPEN_ROUTER::free:free", tier = ModelTier.ORCHESTRATOR)
        val result = AutoModelRouter.candidates(
            listOf(paid, free), setOf(ModelProvider.OPEN_ROUTER),
            ModelRole.CHAT, AutoModelRouter.FREE_FIRST_WITH_PAID, false
        )
        assertEquals(listOf(free.id, paid.id), result.map { it.id })
    }

    @Test fun localIsNeverSuggestedWithoutConfiguredLoadedEngine() {
        val local = model("local-edge-model", ModelProvider.LOCAL_EDGE, input = 0.0, output = 0.0, tools = false)
        val disconnected = AutoModelRouter.candidates(
            listOf(local), emptySet(), ModelRole.CHAT, AutoModelRouter.FREE_ONLY, false
        )
        assertTrue(disconnected.isEmpty())
        assertEquals(local.id, AutoModelRouter.candidates(
            listOf(local), emptySet(), ModelRole.CHAT, AutoModelRouter.FREE_ONLY, true
        ).single().id)
        assertTrue(AutoModelRouter.candidates(
            listOf(local), emptySet(), ModelRole.AGENT, AutoModelRouter.FREE_ONLY, true
        ).isEmpty())
    }

    @Test fun toolAndVisionCapabilitiesAreEnforced() {
        val noTools = model("OPEN_ROUTER::t:free", tools = false, vision = true)
        val noVision = model("OPEN_ROUTER::v:free", vision = false)
        val eligible = model("OPEN_ROUTER::good:free", vision = true)
        assertEquals(eligible.id, AutoModelRouter.candidates(
            listOf(noTools, noVision, eligible),
            setOf(ModelProvider.OPEN_ROUTER), ModelRole.AGENT,
            AutoModelRouter.FREE_ONLY, false, true
        ).single().id)
    }

    @Test fun discontinuedProviderIsExcluded() {
        val retired = model("GITHUB_MODELS::free", ModelProvider.GITHUB_MODELS, 0.0, 0.0)
        assertTrue(AutoModelRouter.candidates(
            listOf(retired), setOf(ModelProvider.GITHUB_MODELS),
            ModelRole.CHAT, AutoModelRouter.FREE_ONLY, false
        ).isEmpty())
    }
}
