package com.omnidev.workspace.data.network

import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.model.TokenUsage
import com.omnidev.workspace.registry.ModelRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// ─── JSON (de)serialisation config ───────────────────────────────────────────
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

// ─── Anthropic Messages API DTOs ─────────────────────────────────────────────

@Serializable
private data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<AnthropicMessage>,
    val system: String? = null
)

@Serializable
private data class AnthropicMessage(val role: String, val content: String)

@Serializable
private data class AnthropicResponse(
    val content: List<AnthropicContent> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
    val usage: AnthropicUsage? = null
)

@Serializable
private data class AnthropicContent(val type: String = "text", val text: String = "")

@Serializable
private data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0
)

// ─── OpenAI-compatible Chat Completions API DTOs ─────────────────────────────

@Serializable
private data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null
)

@Serializable
private data class OpenAiMessage(val role: String, val content: String)

@Serializable
private data class OpenAiResponse(
    val choices: List<OpenAiChoice> = emptyList(),
    val usage: OpenAiUsage? = null
)

@Serializable
private data class OpenAiChoice(
    val message: OpenAiMessage = OpenAiMessage("assistant", ""),
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
private data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0
)

// ─── CompletionService ────────────────────────────────────────────────────────

/**
 * Real HTTP completion service that routes [CompletionRequest]s to the appropriate
 * AI provider endpoint using [HttpURLConnection] and kotlinx-serialization-json.
 *
 * Supported providers:
 *  - **Anthropic** — `api.anthropic.com/v1/messages` (proprietary format)
 *  - **All others** — OpenAI-compatible `chat/completions` endpoint
 *
 * No extra Gradle dependencies needed — uses only the Java standard library
 * and the `kotlinx-serialization-json` library already present in the project.
 */
class CompletionService {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS    = 120_000
    }

    /**
     * Returns the provider's API base URL (no trailing slash).
     * For GitHub Copilot BYOK the endpoint is the documented `api.githubcopilot.com` path.
     */
    private fun baseUrlFor(provider: ModelProvider): String = when (provider) {
        ModelProvider.ANTHROPIC     -> "https://api.anthropic.com"
        ModelProvider.OPENAI        -> "https://api.openai.com/v1"
        ModelProvider.GEMINI        -> "https://generativelanguage.googleapis.com/v1beta/openai"
        ModelProvider.XAI           -> "https://api.x.ai/v1"
        ModelProvider.DEEPSEEK      -> "https://api.deepseek.com/v1"
        ModelProvider.MISTRAL       -> "https://api.mistral.ai/v1"
        ModelProvider.GROQ          -> "https://api.groq.com/openai/v1"
        ModelProvider.CEREBRAS      -> "https://api.cerebras.ai/v1"
        ModelProvider.TOGETHER      -> "https://api.together.xyz/v1"
        ModelProvider.COHERE        -> "https://api.cohere.ai/compatibility/v1"
        ModelProvider.FIREWORKS     -> "https://api.fireworks.ai/inference/v1"
        ModelProvider.NVIDIA        -> "https://integrate.api.nvidia.com/v1"
        ModelProvider.GITHUB_COPILOT -> "https://api.githubcopilot.com"
        ModelProvider.OPEN_ROUTER   -> "https://openrouter.ai/api/v1"
        ModelProvider.PERPLEXITY    -> "https://api.perplexity.ai"
    }

    /**
     * Executes the completion request, dispatching to the provider-specific HTTP call.
     *
     * @throws IOException if the API call fails (including non-2xx responses).
     * @throws CancellationException on coroutine cancellation.
     */
    suspend operator fun invoke(request: CompletionRequest): CompletionResponse =
        withContext(Dispatchers.IO) {
            val model = ModelRegistry.findModelById(request.modelId)
                ?: throw IOException("Unknown model ID: '${request.modelId}'")

            val apiKey = request.apiKey
                ?: throw IOException(
                    "No API key configured for ${model.provider.displayName}. " +
                        "Add one via the Providers screen (Settings → API Keys)."
                )

            if (model.provider == ModelProvider.ANTHROPIC) {
                callAnthropic(request, model.provider, apiKey)
            } else {
                callOpenAiCompatible(request, model.provider, apiKey)
            }
        }

    // ── Anthropic Messages API ────────────────────────────────────────────────

    private fun callAnthropic(
        request: CompletionRequest,
        provider: ModelProvider,
        apiKey: String
    ): CompletionResponse {
        val url = URL("${baseUrlFor(provider)}/v1/messages")

        // Anthropic's messages array must NOT contain a "system" role entry;
        // the system prompt goes in the top-level "system" field instead.
        val messages = request.messages
            .filter { it.role != MessageRole.SYSTEM }
            .map { msg ->
                AnthropicMessage(
                    role = when (msg.role) {
                        MessageRole.USER, MessageRole.TOOL -> "user"
                        else -> "assistant"
                    },
                    content = msg.content
                )
            }

        val body = json.encodeToString(
            AnthropicRequest.serializer(),
            AnthropicRequest(
                model = request.modelId,
                maxTokens = request.maxTokens,
                messages = messages,
                system = request.systemPrompt
            )
        )

        val responseJson = postJson(
            url = url,
            body = body,
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01"
            )
        )

        val parsed = json.decodeFromString(AnthropicResponse.serializer(), responseJson)
        val textContent = parsed.content
            .filter { it.type == "text" }
            .joinToString("") { it.text }

        return CompletionResponse(
            content = textContent,
            finishReason = parsed.stopReason,
            tokensUsed = parsed.usage?.let {
                TokenUsage(
                    promptTokens = it.inputTokens,
                    completionTokens = it.outputTokens,
                    totalTokens = it.inputTokens + it.outputTokens
                )
            }
        )
    }

    // ── OpenAI-compatible Chat Completions API ────────────────────────────────

    private fun callOpenAiCompatible(
        request: CompletionRequest,
        provider: ModelProvider,
        apiKey: String
    ): CompletionResponse {
        val url = URL("${baseUrlFor(provider)}/chat/completions")

        // OpenAI format uses a "system" role message as the first entry.
        val messages = buildList {
            request.systemPrompt?.let { add(OpenAiMessage("system", it)) }
            addAll(request.messages.map { msg ->
                OpenAiMessage(
                    role = when (msg.role) {
                        MessageRole.SYSTEM    -> "system"
                        MessageRole.ASSISTANT -> "assistant"
                        else                  -> "user" // USER and TOOL both map to "user"
                    },
                    content = msg.content
                )
            })
        }

        val body = json.encodeToString(
            OpenAiRequest.serializer(),
            OpenAiRequest(
                model = request.modelId,
                messages = messages,
                maxTokens = request.maxTokens,
                temperature = request.temperature
            )
        )

        // GitHub Copilot BYOK requires these additional headers per the official docs.
        val extraHeaders: Map<String, String> = if (provider == ModelProvider.GITHUB_COPILOT) {
            mapOf(
                "Editor-Version" to "OmniDevWorkspace/1.0",
                "Copilot-Integration-Id" to "chat-panel"
            )
        } else emptyMap()

        val responseJson = postJson(
            url = url,
            body = body,
            headers = mapOf("Authorization" to "Bearer $apiKey") + extraHeaders
        )

        val parsed = json.decodeFromString(OpenAiResponse.serializer(), responseJson)
        val choice = parsed.choices.firstOrNull()

        return CompletionResponse(
            content = choice?.message?.content ?: "",
            finishReason = choice?.finishReason,
            tokensUsed = parsed.usage?.let {
                TokenUsage(
                    promptTokens = it.promptTokens,
                    completionTokens = it.completionTokens,
                    totalTokens = it.totalTokens
                )
            }
        )
    }

    // ── Shared HTTP helper ─────────────────────────────────────────────────────

    /**
     * Makes a POST request with a JSON body and returns the response body string.
     *
     * @throws IOException on non-2xx responses (error body included in the message).
     */
    private fun postJson(
        url: URL,
        body: String,
        headers: Map<String, String>
    ): String {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }

        val responseCode = conn.responseCode
        val responseBody = if (responseCode in 200..299) {
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } else {
            val errorBody = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText()
                ?: "HTTP $responseCode"
            conn.disconnect()
            throw IOException("API error $responseCode from ${url.host}: $errorBody")
        }

        conn.disconnect()
        return responseBody
    }
}
