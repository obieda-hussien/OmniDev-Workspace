package com.omnidev.workspace.data.auth

import android.util.Log
import com.omnidev.workspace.data.model.AIModel
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.model.ModelTier
import com.omnidev.workspace.registry.ModelRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Bridges [CopilotSessionManager] and [ModelRegistry].
 *
 * After a successful GitHub Copilot Device Flow login, call [refreshModels] to:
 *  1. Fetch the full list of models available on the user's Copilot subscription
 *     via `GET https://api.githubcopilot.com/models`.
 *  2. Convert each [CopilotSessionManager.CopilotModel] to an [AIModel].
 *  3. Inject new models into [ModelRegistry.addDynamicCopilotModels] so they
 *     appear in the model selectors immediately — without requiring an app update.
 *
 * This mirrors the approach used by opencode and VS Code: they fetch the `/models`
 * endpoint on startup and offer every model the user's subscription grants access to.
 */
object CopilotModelRefresher {

    private const val TAG = "CopilotModelRefresher"

    /**
     * Fetches and registers all Copilot models available to [oauthToken]'s account.
     *
     * This is a suspend function that runs on [Dispatchers.IO].  Safe to call from
     * any coroutine.  Errors are logged but not rethrown — the static model list in
     * [ModelRegistry] is always the fallback.
     *
     * @param oauthToken The GitHub OAuth token (`gho_…`) obtained via Device Flow.
     */
    suspend fun refreshModels(oauthToken: String) {
        doRefresh(oauthToken)
    }

    /**
     * Fire-and-forget variant.  Launches [refreshModels] on [Dispatchers.IO] inside
     * the provided [scope].  Returns immediately without waiting for completion.
     *
     * @param oauthToken The GitHub OAuth token.
     * @param scope      The [CoroutineScope] to launch the work in (e.g. an app-level scope).
     */
    fun refreshModelsAsync(oauthToken: String, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) { doRefresh(oauthToken) }
    }

    /**
     * Synchronous variant for use inside [Dispatchers.IO] blocks.
     */
    private suspend fun doRefresh(oauthToken: String) {
        try {
            val copilotModels = CopilotSessionManager.fetchAndStoreAvailableModels(oauthToken)
            if (copilotModels.isEmpty()) {
                Log.d(TAG, "No models returned from Copilot API — static list used")
                return
            }

            val aiModels = copilotModels.map { it.toAIModel() }
            ModelRegistry.addDynamicCopilotModels(aiModels)
            Log.i(TAG, "Registered ${aiModels.size} Copilot models: ${aiModels.take(5).map { it.id }}")
        } catch (e: Exception) {
            Log.w(TAG, "Copilot model refresh failed (static list used): ${e.message}")
        }
    }

    // ── Conversion ────────────────────────────────────────────────────────────

    /**
     * Converts a raw [CopilotSessionManager.CopilotModel] (from the API response)
     * to an [AIModel] compatible with the app's ModelRegistry / model selectors.
     *
     * The model id is stored with a `copilot/` prefix (consistent with the static
     * Copilot models already in [ModelRegistry]) so [CompletionService] strips it
     * correctly when forwarding requests to `api.githubcopilot.com`.
     */
    private fun CopilotSessionManager.CopilotModel.toAIModel(): AIModel {
        // Infer tier from vendor / model name keywords
        val tier = inferTier(id, vendor)
        val ctx = contextWindow.takeIf { it > 0 } ?: 128_000
        val name = if (isPreview) "$displayName [Preview]" else displayName

        return AIModel(
            id = "copilot/$id",
            displayName = "$name (Copilot)",
            provider = ModelProvider.GITHUB_COPILOT,
            tier = tier,
            contextWindow = ctx,
            maxOutputTokens = minOf(ctx / 4, 64_000).coerceAtLeast(4096),
            supportsVision = supportsVision,
            supportsFunctionCalling = supportsFunctionCalling,
            supportsStructuredOutput = supportsFunctionCalling,
            shortDescription = buildDescription(this),
            isLatest = false   // dynamic models never override the "latest" flag
        )
    }

    private fun inferTier(id: String, vendor: String?): ModelTier {
        val key = (id + (vendor ?: "")).lowercase()
        return when {
            "opus" in key || "o1" in key || "o3" in key   -> ModelTier.ORCHESTRATOR
            "sonnet" in key || "gpt-4" in key || "pro" in key
                    || "gemini-2" in key || "gemini-3" in key -> ModelTier.EXECUTOR
            else                                              -> ModelTier.FAST
        }
    }

    private fun buildDescription(m: CopilotSessionManager.CopilotModel): String {
        val parts = mutableListOf<String>()
        if (!m.vendor.isNullOrBlank()) parts += m.vendor
        if (m.isPreview) parts += "PREVIEW"
        if (m.supportsVision) parts += "vision"
        parts += "${m.contextWindow / 1000}K ctx"
        return parts.joinToString(" · ") + " via GitHub Copilot"
    }
}
