package com.omnidev.workspace.data.routing

import com.omnidev.workspace.data.model.AIModel
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.model.ModelRole
import com.omnidev.workspace.data.model.ModelTier

/**
 * Explicit, opt-in automatic routing. Unknown pricing is NOT considered free.
 * This deliberately does not invent trial quotas from a provider name or account tier.
 * No local model is proposed unless the user configured and actually loaded it.
 */
object AutoModelRouter {
    const val FREE_ONLY = "AUTO_FREE_ONLY"
    const val FREE_FIRST_WITH_PAID = "AUTO_FREE_FIRST_WITH_PAID"

    fun isAuto(selection: String): Boolean =
        selection == FREE_ONLY || selection == FREE_FIRST_WITH_PAID

    fun isExplicitlyFree(model: AIModel): Boolean =
        (model.costPer1MInputTokens == 0.0 && model.costPer1MOutputTokens == 0.0) ||
            (model.provider == ModelProvider.OPEN_ROUTER &&
                model.id.substringAfter("::").endsWith(":free", ignoreCase = true)) ||
            model.provider == ModelProvider.LOCAL_EDGE

    fun candidates(
        models: List<AIModel>,
        connected: Set<ModelProvider>,
        role: ModelRole,
        selection: String,
        localReady: Boolean,
        requiresVision: Boolean = false
    ): List<AIModel> {
        if (!isAuto(selection)) return emptyList()
        return models.asSequence()
            .filter { it.contextWindow > 0 && it.maxOutputTokens > 0 }
            .filter { it.provider != ModelProvider.GITHUB_MODELS } // Retired service.
            .filter { model ->
                if (model.provider == ModelProvider.LOCAL_EDGE) localReady
                else model.provider in connected
            }
            .filter { it.supportsFunctionCalling || (role == ModelRole.CHAT && it.provider == ModelProvider.LOCAL_EDGE) }
            .filter { !requiresVision || it.supportsVision }
            .filter { selection == FREE_FIRST_WITH_PAID || isExplicitlyFree(it) }
            // The completion service looks up models by ID, so avoid ambiguous identities.
            .distinctBy { it.id }
            .sortedWith(compareBy<AIModel>(
                { if (isExplicitlyFree(it)) 0 else 1 },
                { tierPenalty(role, it.tier) },
                { if (it.isLatest) 0 else 1 },
                { if (it.costPer1MInputTokens != null && it.costPer1MOutputTokens != null)
                    it.costPer1MInputTokens!! + it.costPer1MOutputTokens!! else Double.MAX_VALUE },
                { it.id }
            ))
            .toList()
    }

    private fun tierPenalty(role: ModelRole, tier: ModelTier): Int = when (role) {
        ModelRole.CHAT -> when (tier) {
            ModelTier.FAST -> 0
            ModelTier.EXECUTOR -> 1
            ModelTier.ORCHESTRATOR -> 2
        }
        ModelRole.AGENT, ModelRole.SWARM_WORKER -> when (tier) {
            ModelTier.EXECUTOR -> 0
            ModelTier.ORCHESTRATOR -> 1
            ModelTier.FAST -> 2
        }
        ModelRole.SWARM_ORCHESTRATOR -> when (tier) {
            ModelTier.ORCHESTRATOR -> 0
            ModelTier.EXECUTOR -> 1
            ModelTier.FAST -> 2
        }
    }
}
