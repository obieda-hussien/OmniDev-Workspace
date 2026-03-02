package com.omnidev.workspace.data.network

import com.omnidev.workspace.data.model.AttachmentMediaType
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
private data class AnthropicMessage(val role: String, val content: JsonElement)

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
private data class OpenAiMessage(val role: String, val content: JsonElement)

@Serializable
private data class OpenAiResponse(
    val choices: List<OpenAiChoice> = emptyList(),
    val usage: OpenAiUsage? = null
)

@Serializable
private data class OpenAiChoice(
    val message: OpenAiMessageContent = OpenAiMessageContent("assistant", ""),
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
private data class OpenAiMessageContent(val role: String, val content: String = "")

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
 * Vision support: when a message contains image [AttachmentMeta] with [base64Data]
 * populated, the content is sent as a multi-part content array (images + text)
 * following the provider's specification.
 */
class CompletionService {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS    = 120_000
    }

    /**
     * Returns the provider's API base URL (no trailing slash).
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

        val messages = request.messages
            .filter { it.role != MessageRole.SYSTEM }
            .map { msg ->
                val role = when (msg.role) {
                    MessageRole.USER, MessageRole.TOOL -> "user"
                    else -> "assistant"
                }
                // Build multi-part content when the message has inline image attachments
                val images = msg.attachments.filter {
                    it.mediaType == AttachmentMediaType.IMAGE && it.base64Data != null
                }
                val content: JsonElement = if (images.isEmpty()) {
                    JsonPrimitive(msg.content)
                } else {
                    buildJsonArray {
                        images.forEach { img ->
                            add(buildJsonObject {
                                put("type", "image")
                                put("source", buildJsonObject {
                                    put("type", "base64")
                                    put("media_type", img.mimeType)
                                    put("data", requireNotNull(img.base64Data) {
                                        "base64Data must be non-null for images filtered into vision payload"
                                    })
                                })
                            })
                        }
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", msg.content)
                        })
                    }
                }
                AnthropicMessage(role = role, content = content)
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

        val messages = buildList {
            request.systemPrompt?.let {
                add(OpenAiMessage("system", JsonPrimitive(it)))
            }
            addAll(request.messages.map { msg ->
                val role = when (msg.role) {
                    MessageRole.SYSTEM    -> "system"
                    MessageRole.ASSISTANT -> "assistant"
                    else                  -> "user"
                }
                // Build multi-part content when the message has inline image attachments
                val images = msg.attachments.filter {
                    it.mediaType == AttachmentMediaType.IMAGE && it.base64Data != null
                }
                val content: JsonElement = if (images.isEmpty()) {
                    JsonPrimitive(msg.content)
                } else {
                    buildJsonArray {
                        images.forEach { img ->
                            add(buildJsonObject {
                                put("type", "image_url")
                                put("image_url", buildJsonObject {
                                    put("url", "data:${img.mimeType};base64,${requireNotNull(img.base64Data) {
                                        "base64Data must be non-null for images filtered into vision payload"
                                    }}")
                                })
                            })
                        }
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", msg.content)
                        })
                    }
                }
                OpenAiMessage(role = role, content = content)
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
     * HTTP error codes are mapped to user-friendly [IOException] messages so the
     * [AgentPipeline] can surface them directly without exposing raw JSON to the user.
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
        if (responseCode in 200..299) {
            val responseBody = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            conn.disconnect()
            return responseBody
        }

        val errorBody = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
        conn.disconnect()

        // Map common HTTP error codes to actionable user-facing messages.
        val friendlyMessage = when (responseCode) {
            401 -> "API key is invalid or expired. Update it in Settings → API Keys."
            402 -> "Insufficient quota or billing issue. Check your account on the provider's dashboard."
            403 -> "Access forbidden. Your API key may not have permission to use this model."
            404 -> "Model not found (${url.host}). The selected model may not be available on your API tier."
            422 -> "Invalid request format. The provider rejected the payload (unprocessable entity). Check model parameters."
            429 -> "Rate limit exceeded. The agent will retry automatically after a short delay."
            500, 502, 503 -> "The provider's server encountered an error ($responseCode). Retrying…"
            else -> "API error $responseCode from ${url.host}: $errorBody"
        }
        throw IOException(friendlyMessage)
    }
}

