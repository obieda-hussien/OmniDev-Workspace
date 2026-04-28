package com.omnidev.workspace.registry

import com.omnidev.workspace.data.model.AIModel
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.model.ModelRole
import com.omnidev.workspace.data.model.ModelTier
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Central registry of all available AI models, updated with confirmed releases as of Feb 2026.
 *
 * Models are tiered for agentic coding tasks:
 *  [ModelTier.ORCHESTRATOR] — planning, architecture, complex multi-file analysis
 *  [ModelTier.EXECUTOR]     — fast implementation, refactoring, code generation
 *  [ModelTier.FAST]         — inline completion, quick edits (<300ms target)
 *
 * Sources verified:
 *  • Claude Opus 4.6   — Anthropic, Feb 5, 2026    (claude-opus-4-6)
 *  • Claude Sonnet 4.6 — Anthropic, Feb 17, 2026   (claude-sonnet-4-6)
 *  • GPT-5.3-Codex     — OpenAI,    Feb 5, 2026
 *  • GPT-5.3-Codex-Spark — OpenAI, Feb 12, 2026    (Cerebras, 1000+ tok/s)
 *  • Gemini 3.1 Pro    — Google,    Feb 19, 2026
 *  • Grok-3            — xAI,       Feb 2026
 *  • DeepSeek R2       — DeepSeek,  Jan 2026
 *  • Mistral Large 3   — Mistral,   Jan 2026
 *  • Llama 4 Scout/Maverick — Meta, Feb 2026
 */
object ModelRegistry {

    // ─────────────────────────────────────────────────────────────────
    //  ANTHROPIC — Best agentic coding family (SWE-bench leaders)
    // ─────────────────────────────────────────────────────────────────


    // ─────────────────────────────────────────────────────────────────
    //  OPENAI — Strong code generation and structured output
    // ─────────────────────────────────────────────────────────────────


    // ─────────────────────────────────────────────────────────────────
    //  GOOGLE GEMINI — Largest context windows, native video support
    // ─────────────────────────────────────────────────────────────────


    // ─────────────────────────────────────────────────────────────────
    //  xAI (GROK) — Huge context, real-time data awareness
    // ─────────────────────────────────────────────────────────────────


    // ─────────────────────────────────────────────────────────────────
    //  DEEPSEEK — Open-source powerhouse, excellent at code
    // ─────────────────────────────────────────────────────────────────


    // ─────────────────────────────────────────────────────────────────
    //  MISTRAL AI — European leader, Codestral for code
    // ─────────────────────────────────────────────────────────────────


    // ─────────────────────────────────────────────────────────────────
    //  GROQ — Ultra-fast LPU inference (world's fastest tokens/sec)
    // ─────────────────────────────────────────────────────────────────


    // ─────────────────────────────────────────────────────────────────
    //  CEREBRAS — Wafer-scale chip, world-record inference speed
    // ─────────────────────────────────────────────────────────────────


    // ─────────────────────────────────────────────────────────────────
    //  TOGETHER AI — Open-source models on fast GPU clusters
    // ─────────────────────────────────────────────────────────────────


    // ─────────────────────────────────────────────────────────────────
    //  COHERE — Enterprise RAG and structured output specialist
    // ─────────────────────────────────────────────────────────────────


    // ─────────────────────────────────────────────────────────────────
    //  FIREWORKS AI — Fast, cheap open-source hosting
    // ─────────────────────────────────────────────────────────────────


    // ─────────────────────────────────────────────────────────────────
    //  NVIDIA NIM — Enterprise-grade model inference with NIM microservices
    // ─────────────────────────────────────────────────────────────────


    // ─────────────────────────────────────────────────────────────────
    //  GITHUB COPILOT — BYOK via api.githubcopilot.com (OpenAI-compatible)
    //
    //  Endpoint: https://api.githubcopilot.com/chat/completions
    //  Auth: GitHub PAT with `copilot` scope OR a Copilot API key.
    //  The API is OpenAI-compatible: set Authorization: Bearer <token>.
    // ─────────────────────────────────────────────────────────────────


    // ─────────────────────────────────────────────────────────────────
    //  GITHUB MODELS — Marketplace AI via models.inference.ai.azure.com
    //  Auth: GitHub token (Device Flow) stored under ModelProvider.GITHUB_MODELS
    //  Endpoint: https://models.inference.ai.azure.com/chat/completions
    //  All models use the OpenAI-compatible format.
    // ─────────────────────────────────────────────────────────────────


    // ─────────────────────────────────────────────────────────────────
    //  OPENROUTER — Unified API gateway for all providers
    // ─────────────────────────────────────────────────────────────────


    // ─────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────

    /** All models flattened into a single ordered list, newest/most capable first. */
    // ── Dynamic Copilot models (fetched from api.githubcopilot.com/models) ────
    //
    // Populated by CopilotModelRefresher after a successful GitHub Copilot login.
    // Any model fetched from the Copilot API that is NOT already in the static
    // `githubCopilotModels` list is added here so it appears in the model selector.
    // Thread-safe: backed by CopyOnWriteArrayList.
    private val _dynamicCopilotModels = CopyOnWriteArrayList<AIModel>()

    // Static set of Copilot model IDs — computed once for fast duplicate detection.
    private val staticCopilotModelIds: Set<String> = emptySet()

    /**
     * Injects dynamically fetched Copilot models into the registry.
     * Existing static models are preserved; duplicates (same `id`) are skipped.
     *
     * Call this from [com.omnidev.workspace.data.auth.CopilotModelRefresher] after
     * a successful [fetchAndStoreAvailableModels].
     */
    fun addDynamicCopilotModels(models: List<AIModel>) {
        val newModels = models.filter { it.id !in staticCopilotModelIds }
        _dynamicCopilotModels.addAll(newModels)
    }

    /** Clears all dynamically added Copilot models (e.g. after logout). */
    fun clearDynamicCopilotModels() {
        _dynamicCopilotModels.clear()
    }

    val allModels: List<AIModel> get() = buildList {
        addAll(_dynamicCopilotModels)   // ← dynamically fetched Copilot models
        add(
            AIModel(
                id = "local-edge-model",
                displayName = "Local Edge Model (BYOM)",
                provider = ModelProvider.LOCAL_EDGE,
                tier = ModelTier.EXECUTOR,
                contextWindow = 4096,
                maxOutputTokens = 2048,
                supportsFunctionCalling = false,
                costPer1MInputTokens = null,
                costPer1MOutputTokens = null,
                shortDescription = "Supports all GGUF formats including BitNet i2_s quantized models. No internet or API key required.",
                isLatest = true
            )
        )
    }

    /** Models grouped by their provider for UI dropdown grouping. */
    val modelsByProvider: Map<ModelProvider, List<AIModel>> get() =
        allModels.groupBy { it.provider }

    /** Models grouped by tier for intelligent routing. */
    val modelsByTier: Map<ModelTier, List<AIModel>> get() =
        allModels.groupBy { it.tier }

    /** The latest (flagship) models from each provider — ideal for auto-selection. */
    val latestByProvider: Map<ModelProvider, AIModel> get() = buildMap {
        modelsByProvider.forEach { (provider, models) ->
            val latest = models.firstOrNull { it.isLatest } ?: models.first()
            put(provider, latest)
        }
    }

    private fun generateFallbackModel(id: String): AIModel {
        val parts = id.split("::", limit = 2)
        val providerName = if (parts.size == 2) parts[0] else null
        val realId = if (parts.size == 2) parts[1] else id

        val lowerId = realId.lowercase(java.util.Locale.US)

        val provider = if (providerName != null) {
            runCatching { ModelProvider.valueOf(providerName) }.getOrNull() ?: ModelProvider.OPEN_ROUTER
        } else {
            when {
                "openrouter" in lowerId -> ModelProvider.OPEN_ROUTER
                "claude" in lowerId || "anthropic" in lowerId -> ModelProvider.ANTHROPIC
                "gpt" in lowerId || "openai" in lowerId || lowerId.startsWith("o1") ||
                    lowerId.startsWith("o3") || lowerId.startsWith("o4") -> ModelProvider.OPENAI
                "gemini" in lowerId || "google" in lowerId || "gemma" in lowerId -> ModelProvider.GEMINI
                "grok" in lowerId || "xai" in lowerId -> ModelProvider.XAI
                "deepseek" in lowerId -> ModelProvider.DEEPSEEK
                "mistral" in lowerId || "mixtral" in lowerId -> ModelProvider.MISTRAL
                "groq" in lowerId -> ModelProvider.GROQ
                "cerebras" in lowerId -> ModelProvider.CEREBRAS
                "cohere" in lowerId || "command" in lowerId -> ModelProvider.COHERE
                "fireworks" in lowerId -> ModelProvider.FIREWORKS
                "together" in lowerId -> ModelProvider.TOGETHER
                "perplexity" in lowerId || "sonar" in lowerId -> ModelProvider.PERPLEXITY
                "nvidia" in lowerId || "nemotron" in lowerId -> ModelProvider.NVIDIA
                "minimax" in lowerId -> ModelProvider.MINIMAX
                "vercel" in lowerId -> ModelProvider.VERCEL_AI_GATEWAY
                "huggingface" in lowerId || "hf" in lowerId -> ModelProvider.HUGGING_FACE
                "copilot" in lowerId -> ModelProvider.GITHUB_COPILOT
                "llama" in lowerId -> ModelProvider.TOGETHER
                else -> ModelProvider.LOCAL_EDGE
            }
        }

        val tier = when {
            lowerId.contains("opus") ||
                lowerId.contains("ultra") ||
                lowerId.contains("max") ||
                lowerId.contains("pro") ||
                lowerId.contains("70b") ||
                lowerId.contains("72b") ||
                lowerId.contains("405b") ||
                lowerId.contains("r2") ||
                lowerId.contains("gpt-5") ||
                lowerId.contains("gemini-3") ||
                lowerId.contains("grok-4") -> ModelTier.ORCHESTRATOR

            lowerId.contains("haiku") ||
                lowerId.contains("flash") ||
                lowerId.contains("mini") ||
                lowerId.contains("nano") ||
                lowerId.contains("8b") ||
                lowerId.contains("7b") ||
                lowerId.contains("turbo") ||
                lowerId.contains("fast") ||
                lowerId.contains("instant") -> ModelTier.FAST

            else -> ModelTier.EXECUTOR
        }

        return AIModel(
            id = realId,
            displayName = realId,
            provider = provider,
            tier = tier,
            contextWindow = 128000,
            maxOutputTokens = 8192,
            supportsFunctionCalling = true
        )
    }

    /**
     * Retrieves a model by its unique [id].
     * Generates a fallback dynamically based on id if not found.
     */
    fun getModelById(id: String): AIModel =
        allModels.firstOrNull { it.id == id } ?: generateFallbackModel(id)

    /**
     * Safely retrieves a model by [id], returning a generated fallback if not found.
     */
    fun findModelById(id: String): AIModel? =
        allModels.firstOrNull { it.id == id } ?: generateFallbackModel(id)

    /**
     * Retrieves all models of a specific [tier], sorted by provider order.
     */
    fun getModelsForTier(tier: ModelTier): List<AIModel> =
        modelsByTier[tier] ?: emptyList()

    /**
     * Retrieves the best model for a given [tier] from a preferred [provider].
     * Falls back to the globally best model in that tier if the provider has none.
     */
    fun getBestModelForTier(tier: ModelTier, preferredProvider: ModelProvider? = null): AIModel {
        val tieredModels = getModelsForTier(tier)
        if (preferredProvider != null) {
            val providerModel = tieredModels.firstOrNull { it.provider == preferredProvider }
            if (providerModel != null) return providerModel
        }
        return tieredModels.firstOrNull() ?: generateFallbackModel("claude-sonnet-4-6")
    }

    /** Returns the recommended default model for a given [role]. */
    fun getDefaultModelForRole(role: ModelRole): AIModel = when (role) {
        ModelRole.CHAT -> getModelById("claude-sonnet-4-6")
        ModelRole.AGENT -> getModelById("claude-sonnet-4-6")
        ModelRole.SWARM_ORCHESTRATOR -> getModelById("claude-opus-4-6")
        ModelRole.SWARM_WORKER -> getModelById("deepseek-r2")
    }
}

