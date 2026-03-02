package com.omnidev.workspace.registry

import com.omnidev.workspace.data.model.AIModel
import com.omnidev.workspace.data.model.ModelProvider

/**
 * Hardcoded registry of all available AI models, grouped by provider.
 *
 * This serves as the single source of truth for model capabilities, context windows,
 * and feature support flags. Models are organized by provider for easy lookup and
 * UI grouping in the settings dropdowns.
 */
object ModelRegistry {

    // ──────────────────────────────────────────────
    //  Anthropic Models
    // ──────────────────────────────────────────────

    private val anthropicModels = listOf(
        AIModel(
            id = "claude-sonnet-4-20250514",
            displayName = "Claude Sonnet 4",
            provider = ModelProvider.ANTHROPIC,
            contextWindow = 200_000,
            supportsVision = true,
            supportsThinking = true,
            maxOutputTokens = 16_000
        ),
        AIModel(
            id = "claude-opus-4-20250514",
            displayName = "Claude Opus 4",
            provider = ModelProvider.ANTHROPIC,
            contextWindow = 200_000,
            supportsVision = true,
            supportsThinking = true,
            maxOutputTokens = 32_000
        ),
        AIModel(
            id = "claude-3-5-haiku-20241022",
            displayName = "Claude 3.5 Haiku",
            provider = ModelProvider.ANTHROPIC,
            contextWindow = 200_000,
            supportsVision = true,
            supportsThinking = false,
            maxOutputTokens = 8_192
        )
    )

    // ──────────────────────────────────────────────
    //  OpenAI Models
    // ──────────────────────────────────────────────

    private val openAIModels = listOf(
        AIModel(
            id = "gpt-4o",
            displayName = "GPT-4o",
            provider = ModelProvider.OPENAI,
            contextWindow = 128_000,
            supportsVision = true,
            supportsThinking = false,
            maxOutputTokens = 16_384
        ),
        AIModel(
            id = "gpt-4o-mini",
            displayName = "GPT-4o Mini",
            provider = ModelProvider.OPENAI,
            contextWindow = 128_000,
            supportsVision = true,
            supportsThinking = false,
            maxOutputTokens = 16_384
        ),
        AIModel(
            id = "o1",
            displayName = "o1",
            provider = ModelProvider.OPENAI,
            contextWindow = 200_000,
            supportsVision = true,
            supportsThinking = true,
            maxOutputTokens = 100_000
        ),
        AIModel(
            id = "o3-mini",
            displayName = "o3-mini",
            provider = ModelProvider.OPENAI,
            contextWindow = 200_000,
            supportsVision = false,
            supportsThinking = true,
            maxOutputTokens = 100_000
        )
    )

    // ──────────────────────────────────────────────
    //  Google Gemini Models
    // ──────────────────────────────────────────────

    private val geminiModels = listOf(
        AIModel(
            id = "gemini-2.5-flash",
            displayName = "Gemini 2.5 Flash",
            provider = ModelProvider.GEMINI,
            contextWindow = 1_000_000,
            supportsVision = true,
            supportsVideo = true,
            supportsThinking = true,
            maxOutputTokens = 65_536
        ),
        AIModel(
            id = "gemini-2.5-pro",
            displayName = "Gemini 2.5 Pro",
            provider = ModelProvider.GEMINI,
            contextWindow = 1_000_000,
            supportsVision = true,
            supportsVideo = true,
            supportsThinking = true,
            maxOutputTokens = 65_536
        ),
        AIModel(
            id = "gemini-2.0-flash",
            displayName = "Gemini 2.0 Flash",
            provider = ModelProvider.GEMINI,
            contextWindow = 1_000_000,
            supportsVision = true,
            supportsVideo = true,
            supportsThinking = false,
            maxOutputTokens = 8_192
        )
    )

    // ──────────────────────────────────────────────
    //  Groq Models
    // ──────────────────────────────────────────────

    private val groqModels = listOf(
        AIModel(
            id = "llama-3.3-70b-versatile",
            displayName = "Llama 3.3 70B",
            provider = ModelProvider.GROQ,
            contextWindow = 128_000,
            supportsVision = false,
            supportsThinking = false,
            maxOutputTokens = 32_768
        ),
        AIModel(
            id = "llama-3.1-8b-instant",
            displayName = "Llama 3.1 8B Instant",
            provider = ModelProvider.GROQ,
            contextWindow = 128_000,
            supportsVision = false,
            supportsThinking = false,
            maxOutputTokens = 8_192
        ),
        AIModel(
            id = "mixtral-8x7b-32768",
            displayName = "Mixtral 8x7B",
            provider = ModelProvider.GROQ,
            contextWindow = 32_768,
            supportsVision = false,
            supportsThinking = false,
            maxOutputTokens = 32_768
        )
    )

    // ──────────────────────────────────────────────
    //  OpenRouter Models
    // ──────────────────────────────────────────────

    private val openRouterModels = listOf(
        AIModel(
            id = "anthropic/claude-sonnet-4",
            displayName = "Claude Sonnet 4 (via OpenRouter)",
            provider = ModelProvider.OPEN_ROUTER,
            contextWindow = 200_000,
            supportsVision = true,
            supportsThinking = true,
            maxOutputTokens = 16_000
        ),
        AIModel(
            id = "openai/gpt-4o",
            displayName = "GPT-4o (via OpenRouter)",
            provider = ModelProvider.OPEN_ROUTER,
            contextWindow = 128_000,
            supportsVision = true,
            supportsThinking = false,
            maxOutputTokens = 16_384
        ),
        AIModel(
            id = "google/gemini-2.5-flash",
            displayName = "Gemini 2.5 Flash (via OpenRouter)",
            provider = ModelProvider.OPEN_ROUTER,
            contextWindow = 1_000_000,
            supportsVision = true,
            supportsVideo = true,
            supportsThinking = true,
            maxOutputTokens = 65_536
        ),
        AIModel(
            id = "deepseek/deepseek-r1",
            displayName = "DeepSeek R1 (via OpenRouter)",
            provider = ModelProvider.OPEN_ROUTER,
            contextWindow = 128_000,
            supportsVision = false,
            supportsThinking = true,
            maxOutputTokens = 8_192
        )
    )

    // ──────────────────────────────────────────────
    //  Public API
    // ──────────────────────────────────────────────

    /** All models flattened into a single list. */
    val allModels: List<AIModel> = buildList {
        addAll(anthropicModels)
        addAll(openAIModels)
        addAll(geminiModels)
        addAll(groqModels)
        addAll(openRouterModels)
    }

    /** Models grouped by their provider for UI dropdown grouping. */
    val modelsByProvider: Map<ModelProvider, List<AIModel>> =
        allModels.groupBy { it.provider }

    /**
     * Retrieves a model by its unique [id].
     * @throws IllegalArgumentException if no model matches the given ID.
     */
    fun getModelById(id: String): AIModel =
        allModels.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown model ID: $id")

    /**
     * Safely retrieves a model by [id], returning null if not found.
     */
    fun findModelById(id: String): AIModel? =
        allModels.firstOrNull { it.id == id }

    /** Returns the default model for a given [role]. */
    fun getDefaultModelForRole(role: com.omnidev.workspace.data.model.ModelRole): AIModel =
        when (role) {
            com.omnidev.workspace.data.model.ModelRole.CHAT ->
                getModelById("claude-sonnet-4-20250514")
            com.omnidev.workspace.data.model.ModelRole.AGENT ->
                getModelById("claude-sonnet-4-20250514")
            com.omnidev.workspace.data.model.ModelRole.SWARM_ORCHESTRATOR ->
                getModelById("claude-opus-4-20250514")
            com.omnidev.workspace.data.model.ModelRole.SWARM_WORKER ->
                getModelById("claude-sonnet-4-20250514")
        }
}
