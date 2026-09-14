package com.omnidev.workspace.data.network

import com.omnidev.workspace.data.model.AIModel
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.model.ModelTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Per-provider API adapter that fetches the **live** catalogue of model names and
 * context metadata from each provider's public REST endpoint.
 *
 * This replaces the static [com.omnidev.workspace.registry.ModelRegistry] default
 * list with a dynamic discovery mechanism, so that new models added by providers
 * become immediately available inside OmniDev without a code release.
 *
 * Usage:
 * ```
 * val fetcher = ProviderModelFetcher()
 * val models = fetcher.fetchModels(ModelProvider.OPEN_ROUTER, apiKey)
 * ```
 *
 * Each provider returns `Result<List<AIModel>>` — the UI layer should fall back to
 * the static registry list when the call fails (no key, offline, rate-limited, etc.).
 *
 * Only OpenRouter and Groq expose a truly free, well-documented `GET /models`
 * endpoint returning context windows and pricing, which is why they are the
 * primary sources for dynamic enumeration. Other providers still have handlers
 * but fall through to a best-effort parse.
 */
class ProviderModelFetcher {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Fetches the list of models supported by [provider]. Returns [Result.failure]
     * when the request fails — callers should then fall back to the static
     * [com.omnidev.workspace.registry.ModelRegistry] default list.
     *
     * @param provider Which provider's catalogue to query.
     * @param apiKey Optional API key. Required for some providers (OpenAI, Groq, etc.);
     *   optional for OpenRouter (unauthenticated calls are allowed but rate-limited).
     */
    suspend fun fetchModels(
        provider: ModelProvider,
        apiKey: String? = null
    ): Result<List<AIModel>> = withContext(Dispatchers.IO) {
        runCatching {
            when (provider) {
                ModelProvider.ZENMUX -> fetchCompatibleCatalog(provider, "https://zenmux.ai/api/v1", apiKey)
                ModelProvider.Z_AI -> listOf(AIModel(id = "Z_AI::glm-5.3", displayName = "GLM-5.3", provider = provider,
                    contextWindow = 32_768, supportsThinking = true, shortDescription = "Z.ai API; conservative context limit. Use a custom profile to override."))
                ModelProvider.CUSTOM_OPENAI -> emptyList()
                ModelProvider.OPEN_ROUTER -> fetchOpenRouter(apiKey)
                ModelProvider.GROQ -> fetchGroq(apiKey)
                ModelProvider.OPENAI -> fetchOpenAI(apiKey)
                ModelProvider.ANTHROPIC -> fetchAnthropic(apiKey)
                ModelProvider.GEMINI -> fetchGemini(apiKey)
                ModelProvider.MISTRAL -> fetchMistral(apiKey)
                ModelProvider.DEEPSEEK -> fetchDeepSeek(apiKey)
                ModelProvider.TOGETHER -> fetchTogether(apiKey)
                ModelProvider.FIREWORKS -> fetchFireworks(apiKey)
                ModelProvider.CEREBRAS -> fetchCerebras(apiKey)
                ModelProvider.COHERE -> fetchCohere(apiKey)
                ModelProvider.PERPLEXITY -> fetchPerplexity(apiKey)
                ModelProvider.NVIDIA -> fetchNvidia(apiKey)
                ModelProvider.XAI -> fetchXai(apiKey)
                ModelProvider.MINIMAX -> fetchMinimax(apiKey)
                ModelProvider.VERCEL_AI_GATEWAY -> fetchVercelAiGateway(apiKey)
                ModelProvider.HUGGING_FACE -> fetchHuggingFace(apiKey)
                ModelProvider.GITHUB_COPILOT,
                ModelProvider.GITHUB_MODELS,
                ModelProvider.LOCAL_EDGE -> emptyList()
            }
        }
    }

    // ──────────────────────────────────────────────
    //  OpenRouter — /api/v1/models
    // ──────────────────────────────────────────────

    @Serializable
    private data class OpenRouterResponse(val data: List<OpenRouterModel>)

    @Serializable
    private data class OpenRouterModel(
        val id: String,
        val name: String? = null,
        @SerialName("context_length") val contextLength: Int? = null,
        @SerialName("top_provider") val topProvider: OpenRouterTopProvider? = null,
        val pricing: OpenRouterPricing? = null,
        val description: String? = null,
        val architecture: OpenRouterArchitecture? = null
    )

    @Serializable
    private data class OpenRouterTopProvider(
        @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
        @SerialName("context_length") val contextLength: Int? = null
    )

    @Serializable
    private data class OpenRouterPricing(
        val prompt: String? = null,
        val completion: String? = null
    )

    @Serializable
    private data class OpenRouterArchitecture(
        val modality: String? = null,
        @SerialName("input_modalities") val inputModalities: List<String>? = null
    )

    private fun fetchOpenRouter(apiKey: String?): List<AIModel> {
        val headers = buildMap<String, String> {
            put("Accept", "application/json")
            if (!apiKey.isNullOrBlank()) put("Authorization", "Bearer $apiKey")
        }
        val body = httpGet("https://openrouter.ai/api/v1/models", headers)
        val parsed = json.decodeFromString(OpenRouterResponse.serializer(), body)
        return parsed.data.map { m ->
            val ctx = m.contextLength ?: m.topProvider?.contextLength ?: 8_192
            val out = m.topProvider?.maxCompletionTokens ?: 4_096
            val vision = (m.architecture?.inputModalities?.contains("image") == true) ||
                (m.architecture?.modality?.contains("image", true) == true)
            AIModel(
                id = "${ModelProvider.OPEN_ROUTER.name}::${m.id}",
                displayName = m.name ?: m.id,
                provider = ModelProvider.OPEN_ROUTER,
                tier = inferTier(m.id),
                contextWindow = ctx,
                maxOutputTokens = out,
                supportsVision = vision,
                supportsFunctionCalling = true,
                costPer1MInputTokens = m.pricing?.prompt?.toDoubleOrNull()?.let { it * 1_000_000.0 },
                costPer1MOutputTokens = m.pricing?.completion?.toDoubleOrNull()?.let { it * 1_000_000.0 },
                shortDescription = m.description?.take(140),
                isLatest = false
            )
        }
    }

    // ──────────────────────────────────────────────
    //  Groq — /openai/v1/models
    // ──────────────────────────────────────────────

    @Serializable
    private data class GroqListResponse(val data: List<GroqModel>)

    @Serializable
    private data class GroqModel(
        val id: String,
        @SerialName("context_window") val contextWindow: Int? = null,
        @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
        @SerialName("owned_by") val ownedBy: String? = null,
        val active: Boolean? = null
    )

    private fun fetchGroq(apiKey: String?): List<AIModel> {
        require(!apiKey.isNullOrBlank()) { "Groq API key is required to enumerate models." }
        val headers = mapOf(
            "Accept" to "application/json",
            "Authorization" to "Bearer $apiKey"
        )
        val body = httpGet("https://api.groq.com/openai/v1/models", headers)
        val parsed = json.decodeFromString(GroqListResponse.serializer(), body)
        return parsed.data
            .filter { it.active != false }
            .map { m ->
                AIModel(
                    id = "${ModelProvider.GROQ.name}::${m.id}",
                    displayName = m.id,
                    provider = ModelProvider.GROQ,
                    tier = inferTier(m.id),
                    contextWindow = m.contextWindow ?: 32_768,
                    maxOutputTokens = m.maxCompletionTokens ?: 4_096,
                    supportsVision = m.id.contains("vision", true) || m.id.contains("llava", true),
                    supportsFunctionCalling = true,
                    shortDescription = "Groq LPU (${m.ownedBy ?: "unknown"})",
                    isLatest = false
                )
            }
    }

    // ──────────────────────────────────────────────
    //  OpenAI — /v1/models
    // ──────────────────────────────────────────────

    @Serializable
    private data class OpenAiListResponse(val data: List<OpenAiModel>)

    @Serializable
    private data class OpenAiModel(
        val id: String,
        @SerialName("owned_by") val ownedBy: String? = null
    )

    private fun fetchOpenAI(apiKey: String?): List<AIModel> {
        require(!apiKey.isNullOrBlank()) { "OpenAI API key is required." }
        val body = httpGet(
            "https://api.openai.com/v1/models",
            mapOf("Authorization" to "Bearer $apiKey")
        )
        val parsed = json.decodeFromString(OpenAiListResponse.serializer(), body)
        return parsed.data
            .filter { it.id.startsWith("gpt") || it.id.startsWith("o") || it.id.startsWith("chatgpt") }
            .map { m ->
                AIModel(
                    id = "${ModelProvider.OPENAI.name}::${m.id}",
                    displayName = m.id,
                    provider = ModelProvider.OPENAI,
                    tier = inferTier(m.id),
                    contextWindow = guessOpenAiContext(m.id),
                    maxOutputTokens = 16_384,
                    supportsVision = m.id.contains("gpt-4") || m.id.contains("o4") || m.id.contains("o3"),
                    supportsFunctionCalling = true,
                    shortDescription = "Owned by ${m.ownedBy ?: "openai"}"
                )
            }
    }

    private fun guessOpenAiContext(id: String): Int = when {
        id.contains("gpt-4.1") || id.contains("gpt-5") -> 1_000_000
        id.contains("gpt-4o") -> 128_000
        id.contains("o1") || id.contains("o3") || id.contains("o4") -> 200_000
        id.contains("gpt-4-turbo") -> 128_000
        id.contains("gpt-4") -> 8_192
        id.contains("gpt-3.5") -> 16_385
        else -> 32_768
    }

    // ──────────────────────────────────────────────
    //  Anthropic — /v1/models
    // ──────────────────────────────────────────────

    @Serializable
    private data class AnthropicListResponse(val data: List<AnthropicModel>)

    @Serializable
    private data class AnthropicModel(
        val id: String,
        @SerialName("display_name") val displayName: String? = null,
        val type: String? = null
    )

    private fun fetchAnthropic(apiKey: String?): List<AIModel> {
        require(!apiKey.isNullOrBlank()) { "Anthropic API key is required." }
        val body = httpGet(
            "https://api.anthropic.com/v1/models",
            mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01"
            )
        )
        val parsed = json.decodeFromString(AnthropicListResponse.serializer(), body)
        return parsed.data.map { m ->
            AIModel(
                id = "${ModelProvider.ANTHROPIC.name}::${m.id}",
                displayName = m.displayName ?: m.id,
                provider = ModelProvider.ANTHROPIC,
                tier = inferTier(m.id),
                contextWindow = if (m.id.contains("opus") || m.id.contains("sonnet")) 200_000 else 200_000,
                maxOutputTokens = if (m.id.contains("opus")) 32_000 else 64_000,
                supportsVision = true,
                supportsThinking = m.id.contains("opus") || m.id.contains("sonnet"),
                supportsFunctionCalling = true
            )
        }
    }

    // ──────────────────────────────────────────────
    //  Google Gemini — /v1beta/models
    // ──────────────────────────────────────────────

    @Serializable
    private data class GeminiListResponse(val models: List<GeminiModel> = emptyList())

    @Serializable
    private data class GeminiModel(
        val name: String,
        @SerialName("displayName") val displayName: String? = null,
        @SerialName("inputTokenLimit") val inputTokenLimit: Int? = null,
        @SerialName("outputTokenLimit") val outputTokenLimit: Int? = null,
        @SerialName("supportedGenerationMethods") val methods: List<String>? = null
    )

    private fun fetchGemini(apiKey: String?): List<AIModel> {
        require(!apiKey.isNullOrBlank()) { "Gemini API key is required." }
        val body = httpGet(
            "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey",
            mapOf("Accept" to "application/json")
        )
        val parsed = json.decodeFromString(GeminiListResponse.serializer(), body)
        return parsed.models
            .filter { it.methods?.contains("generateContent") == true }
            .map { m ->
                val id = m.name.removePrefix("models/")
                AIModel(
                    id = "${ModelProvider.GEMINI.name}::${id}",
                    displayName = m.displayName ?: id,
                    provider = ModelProvider.GEMINI,
                    tier = inferTier(id),
                    contextWindow = m.inputTokenLimit ?: 1_000_000,
                    maxOutputTokens = m.outputTokenLimit ?: 8_192,
                    supportsVision = true,
                    supportsFunctionCalling = true
                )
            }
    }

    // ──────────────────────────────────────────────
    //  Mistral — /v1/models
    // ──────────────────────────────────────────────

    private fun fetchMistral(apiKey: String?): List<AIModel> {
        require(!apiKey.isNullOrBlank()) { "Mistral API key is required." }
        val body = httpGet(
            "https://api.mistral.ai/v1/models",
            mapOf("Authorization" to "Bearer $apiKey")
        )
        val parsed = json.decodeFromString(OpenAiListResponse.serializer(), body)
        return parsed.data.map { m ->
            AIModel(
                id = "${ModelProvider.MISTRAL.name}::${m.id}",
                displayName = m.id,
                provider = ModelProvider.MISTRAL,
                tier = inferTier(m.id),
                contextWindow = 128_000,
                maxOutputTokens = 8_192,
                supportsVision = m.id.contains("pixtral", true),
                supportsFunctionCalling = true
            )
        }
    }

    // ──────────────────────────────────────────────
    //  DeepSeek — /v1/models (OpenAI-compatible)
    // ──────────────────────────────────────────────

    private fun fetchDeepSeek(apiKey: String?): List<AIModel> {
        require(!apiKey.isNullOrBlank()) { "DeepSeek API key is required." }
        val body = httpGet(
            "https://api.deepseek.com/v1/models",
            mapOf("Authorization" to "Bearer $apiKey")
        )
        val parsed = json.decodeFromString(OpenAiListResponse.serializer(), body)
        return parsed.data.map { m ->
            AIModel(
                id = "${ModelProvider.DEEPSEEK.name}::${m.id}",
                displayName = m.id,
                provider = ModelProvider.DEEPSEEK,
                tier = inferTier(m.id),
                contextWindow = 128_000,
                maxOutputTokens = 8_192,
                supportsFunctionCalling = true,
                supportsThinking = m.id.contains("reasoner") || m.id.contains("r1") || m.id.contains("r2")
            )
        }
    }

    // ──────────────────────────────────────────────
    //  Together AI — /v1/models
    // ──────────────────────────────────────────────

    private fun fetchTogether(apiKey: String?): List<AIModel> {
        require(!apiKey.isNullOrBlank()) { "Together API key is required." }
        val body = httpGet(
            "https://api.together.xyz/v1/models",
            mapOf("Authorization" to "Bearer $apiKey")
        )
        // Together returns a raw JSON array (not the OpenAI shape)
        val models = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(TogetherModel.serializer()),
            body
        )
        return models.map { m ->
            AIModel(
                id = "${ModelProvider.TOGETHER.name}::${m.id}",
                displayName = m.displayName ?: m.id,
                provider = ModelProvider.TOGETHER,
                tier = inferTier(m.id),
                contextWindow = m.contextLength ?: 32_768,
                maxOutputTokens = 8_192,
                supportsFunctionCalling = true
            )
        }
    }

    @Serializable
    private data class TogetherModel(
        val id: String,
        @SerialName("display_name") val displayName: String? = null,
        @SerialName("context_length") val contextLength: Int? = null,
        val type: String? = null
    )

    // ──────────────────────────────────────────────
    //  Fireworks — /v1/accounts/fireworks/models
    // ──────────────────────────────────────────────

    private fun fetchFireworks(apiKey: String?): List<AIModel> {
        require(!apiKey.isNullOrBlank()) { "Fireworks API key is required." }
        val body = httpGet(
            "https://api.fireworks.ai/inference/v1/models",
            mapOf("Authorization" to "Bearer $apiKey")
        )
        val parsed = json.decodeFromString(OpenAiListResponse.serializer(), body)
        return parsed.data.map { m ->
            AIModel(
                id = "${ModelProvider.FIREWORKS.name}::${m.id}",
                displayName = m.id,
                provider = ModelProvider.FIREWORKS,
                tier = inferTier(m.id),
                contextWindow = 128_000,
                maxOutputTokens = 8_192,
                supportsFunctionCalling = true
            )
        }
    }

    // ──────────────────────────────────────────────
    //  Cerebras — /v1/models
    // ──────────────────────────────────────────────

    private fun fetchCerebras(apiKey: String?): List<AIModel> {
        require(!apiKey.isNullOrBlank()) { "Cerebras API key is required." }
        val body = httpGet(
            "https://api.cerebras.ai/v1/models",
            mapOf("Authorization" to "Bearer $apiKey")
        )
        val parsed = json.decodeFromString(OpenAiListResponse.serializer(), body)
        return parsed.data.map { m ->
            AIModel(
                id = "${ModelProvider.CEREBRAS.name}::${m.id}",
                displayName = m.id,
                provider = ModelProvider.CEREBRAS,
                tier = ModelTier.FAST, // Cerebras focuses on ultra-fast inference
                contextWindow = 128_000,
                maxOutputTokens = 8_192,
                supportsFunctionCalling = true,
                speedTokensPerSecond = 2_000
            )
        }
    }

    // ──────────────────────────────────────────────
    //  Cohere — /v1/models
    // ──────────────────────────────────────────────

    @Serializable
    private data class CohereListResponse(val models: List<CohereModel> = emptyList())

    @Serializable
    private data class CohereModel(
        val name: String,
        val endpoints: List<String>? = null,
        @SerialName("context_length") val contextLength: Int? = null
    )

    private fun fetchCohere(apiKey: String?): List<AIModel> {
        require(!apiKey.isNullOrBlank()) { "Cohere API key is required." }
        val body = httpGet(
            "https://api.cohere.com/v1/models",
            mapOf("Authorization" to "Bearer $apiKey")
        )
        val parsed = json.decodeFromString(CohereListResponse.serializer(), body)
        return parsed.models
            .filter { it.endpoints?.contains("chat") == true }
            .map { m ->
                AIModel(
                    id = "${ModelProvider.COHERE.name}::${m.name}",
                    displayName = m.name,
                    provider = ModelProvider.COHERE,
                    tier = inferTier(m.name),
                    contextWindow = m.contextLength ?: 128_000,
                    maxOutputTokens = 4_096,
                    supportsFunctionCalling = true
                )
            }
    }

    // ──────────────────────────────────────────────
    //  Perplexity — static fallback (no public /models endpoint)
    // ──────────────────────────────────────────────

    private fun fetchPerplexity(apiKey: String?): List<AIModel> = listOf(
        "sonar", "sonar-pro", "sonar-reasoning", "sonar-reasoning-pro",
        "sonar-deep-research"
    ).map { id ->
        AIModel(
            id = "${ModelProvider.PERPLEXITY.name}::${id}",
            displayName = id,
            provider = ModelProvider.PERPLEXITY,
            tier = inferTier(id),
            contextWindow = 128_000,
            maxOutputTokens = 8_192,
            supportsFunctionCalling = false,
            shortDescription = "Perplexity Sonar (search-grounded)"
        )
    }

    // ──────────────────────────────────────────────
    //  NVIDIA NIM — /v1/models
    // ──────────────────────────────────────────────

    private fun fetchNvidia(apiKey: String?): List<AIModel> {
        require(!apiKey.isNullOrBlank()) { "NVIDIA NIM API key is required." }
        val body = httpGet(
            "https://integrate.api.nvidia.com/v1/models",
            mapOf("Authorization" to "Bearer $apiKey")
        )
        val parsed = json.decodeFromString(OpenAiListResponse.serializer(), body)
        return parsed.data.map { m ->
            AIModel(
                id = "${ModelProvider.NVIDIA.name}::${m.id}",
                displayName = m.id,
                provider = ModelProvider.NVIDIA,
                tier = inferTier(m.id),
                contextWindow = 128_000,
                maxOutputTokens = 8_192,
                supportsFunctionCalling = true
            )
        }
    }

    // ──────────────────────────────────────────────
    //  xAI — /v1/models
    // ──────────────────────────────────────────────

    private fun fetchXai(apiKey: String?): List<AIModel> {
        require(!apiKey.isNullOrBlank()) { "xAI API key is required." }
        val body = httpGet(
            "https://api.x.ai/v1/models",
            mapOf("Authorization" to "Bearer $apiKey")
        )
        val parsed = json.decodeFromString(OpenAiListResponse.serializer(), body)
        return parsed.data.map { m ->
            AIModel(
                id = "${ModelProvider.XAI.name}::${m.id}",
                displayName = m.id,
                provider = ModelProvider.XAI,
                tier = inferTier(m.id),
                contextWindow = 256_000,
                maxOutputTokens = 8_192,
                supportsVision = m.id.contains("vision", true) || m.id.contains("grok-4", true),
                supportsFunctionCalling = true
            )
        }
    }

    // ──────────────────────────────────────────────
    //  MiniMax — /v1/models
    // ──────────────────────────────────────────────

    private fun fetchMinimax(apiKey: String?): List<AIModel> = listOf(
        "minimax-text-01", "minimax-text-01-vision", "abab6.5s-chat", "abab6.5t-chat", "abab6.5g-chat"
    ).map { id ->
        AIModel(
            id = "${ModelProvider.MINIMAX.name}::${id}",
            displayName = id,
            provider = ModelProvider.MINIMAX,
            tier = inferTier(id),
            contextWindow = 128_000,
            maxOutputTokens = 8_192,
            supportsFunctionCalling = true
        )
    }

    // ──────────────────────────────────────────────
    //  Vercel AI Gateway — /v1/models
    // ──────────────────────────────────────────────

    private fun fetchVercelAiGateway(apiKey: String?): List<AIModel> {
        require(!apiKey.isNullOrBlank()) { "Vercel API key is required." }
        val body = httpGet(
            "https://ai-gateway.vercel.sh/v1/models",
            mapOf("Authorization" to "Bearer $apiKey")
        )
        val parsed = json.decodeFromString(OpenAiListResponse.serializer(), body)
        return parsed.data.map { m ->
            AIModel(
                id = "${ModelProvider.VERCEL_AI_GATEWAY.name}::${m.id}",
                displayName = m.id,
                provider = ModelProvider.VERCEL_AI_GATEWAY,
                tier = inferTier(m.id),
                contextWindow = 128_000,
                maxOutputTokens = 8_192,
                supportsFunctionCalling = true
            )
        }
    }

    // ──────────────────────────────────────────────
    //  Hugging Face — /v1/models
    // ──────────────────────────────────────────────

    private fun fetchHuggingFace(apiKey: String?): List<AIModel> {
        require(!apiKey.isNullOrBlank()) { "Hugging Face API key is required." }
        val body = httpGet(
            "https://router.huggingface.co/v1/models",
            mapOf("Authorization" to "Bearer $apiKey")
        )
        val parsed = json.decodeFromString(OpenAiListResponse.serializer(), body)
        return parsed.data.map { m ->
            AIModel(
                id = "${ModelProvider.HUGGING_FACE.name}::${m.id}",
                displayName = m.id,
                provider = ModelProvider.HUGGING_FACE,
                tier = inferTier(m.id),
                contextWindow = 128_000,
                maxOutputTokens = 8_192,
                supportsFunctionCalling = true
            )
        }
    }

    // ──────────────────────────────────────────────
    //  Shared helpers
    // ──────────────────────────────────────────────

    /**
     * Heuristic tier inference from model id — used when the provider does not
     * explicitly expose a "tier" or "size" field.
     */
    private fun inferTier(id: String): ModelTier {
        val lower = id.lowercase()
        return when {
            lower.contains("opus") ||
                lower.contains("ultra") ||
                lower.contains("max") ||
                lower.contains("pro") ||
                lower.contains("70b") ||
                lower.contains("72b") ||
                lower.contains("405b") ||
                lower.contains("r2") ||
                lower.contains("gpt-5") ||
                lower.contains("gemini-3") ||
                lower.contains("grok-4") -> ModelTier.ORCHESTRATOR

            lower.contains("haiku") ||
                lower.contains("flash") ||
                lower.contains("mini") ||
                lower.contains("nano") ||
                lower.contains("8b") ||
                lower.contains("7b") ||
                lower.contains("turbo") ||
                lower.contains("fast") ||
                lower.contains("instant") -> ModelTier.FAST

            else -> ModelTier.EXECUTOR
        }
    }

    fun fetchCompatibleCatalog(provider: ModelProvider, baseUrl: String, apiKey: String?): List<AIModel> {
        val body = httpGet("${ProviderEndpoint.normalize(baseUrl)}/models",
            if (apiKey.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey"))
        val data = org.json.JSONObject(body).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val model = data.optJSONObject(i) ?: return@mapNotNull null
            val id = model.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AIModel(id = "${provider.name}::$id", displayName = model.optString("name", id), provider = provider,
                contextWindow = model.optInt("context_length", 32_768).coerceAtLeast(4096),
                supportsThinking = true)
        }
    }

    private fun httpGet(url: String, headers: Map<String, String>): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw java.io.IOException("GET $url failed with HTTP $code: ${body.take(240)}")
            }
            body
        } finally {
            connection.disconnect()
        }
    }
}
