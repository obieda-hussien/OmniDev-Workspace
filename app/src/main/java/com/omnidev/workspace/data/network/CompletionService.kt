package com.omnidev.workspace.data.network

import com.omnidev.workspace.data.localllm.LocalEngineHolder
import com.omnidev.workspace.data.auth.CopilotSessionManager
import com.omnidev.workspace.data.model.AttachmentMediaType
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.model.TokenUsage
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.registry.ModelRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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
    val system: String? = null,
    val stream: Boolean = false,
    val tools: List<AnthropicToolDef>? = null
)

@Serializable
private data class AnthropicMessage(
    val role: String,
    val content: JsonElement,
    @SerialName("cache_control") val cacheControl: JsonElement? = null
)

@Serializable
private data class AnthropicResponse(
    val content: List<AnthropicContent> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
    val usage: AnthropicUsage? = null
)

@Serializable
private data class AnthropicContent(
    val type: String = "text",
    val text: String = "",
    // tool_use fields
    val id: String? = null,
    val name: String? = null,
    val input: JsonElement? = null
)

@Serializable
private data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0
)

// ─── Anthropic Native Tool Calling DTOs ──────────────────────────────────────

@Serializable
private data class AnthropicToolDef(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonElement
)

// ─── Anthropic SSE streaming DTOs ────────────────────────────────────────────

@Serializable
private data class AnthropicStreamMessage(
    val usage: AnthropicUsage? = null
)

@Serializable
private data class AnthropicStreamEvent(
    val type: String = "",
    val index: Int? = null,
    val delta: AnthropicStreamDelta? = null,
    @SerialName("content_block") val contentBlock: AnthropicStreamContentBlock? = null,
    val message: AnthropicStreamMessage? = null,
    val usage: AnthropicUsage? = null
)

@Serializable
private data class AnthropicStreamContentBlock(
    val type: String = "",
    val id: String? = null,
    val name: String? = null
)

@Serializable
private data class AnthropicStreamDelta(
    val type: String = "",
    val text: String? = null,
    @SerialName("partial_json") val partialJson: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null
)

// ─── OpenAI-compatible Chat Completions API DTOs ─────────────────────────────

@Serializable
private data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    val stream: Boolean = false,
    val tools: List<OpenAiToolDef>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null
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
    val message: OpenAiResponseMessage = OpenAiResponseMessage("assistant"),
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
private data class OpenAiResponseMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiToolCall>? = null
)

@Serializable
private data class OpenAiMessageContent(val role: String, val content: String = "")

@Serializable
private data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0
)

// ─── OpenAI Native Tool Calling DTOs ─────────────────────────────────────────

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class OpenAiToolDef(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val type: String = "function",
    val function: OpenAiFunction
)

@Serializable
private data class OpenAiFunction(
    val name: String,
    val description: String,
    val parameters: JsonElement
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class OpenAiToolCall(
    val id: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val type: String = "function",
    val function: OpenAiToolCallFunction
)

@Serializable
private data class OpenAiToolCallFunction(
    val name: String,
    val arguments: String
)

// ─── OpenAI SSE streaming DTOs ────────────────────────────────────────────────

@Serializable
private data class OpenAiStreamChunk(
    val choices: List<OpenAiStreamChoice> = emptyList(),
    val usage: OpenAiUsage? = null
)

@Serializable
private data class OpenAiStreamChoice(
    val delta: OpenAiStreamDelta = OpenAiStreamDelta(),
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
private data class OpenAiStreamDelta(
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiStreamToolCallDelta>? = null
)

@Serializable
private data class OpenAiStreamToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: OpenAiStreamToolCallFunctionDelta? = null
)

@Serializable
private data class OpenAiStreamToolCallFunctionDelta(
    val name: String? = null,
    val arguments: String? = null
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

    // ── Retry helper ──────────────────────────────────────────────────────────

    /**
     * Executes [block] and retries up to [maxRetries] times with exponential backoff
     * when the call throws a retryable [IOException] (HTTP 429 or 5xx).
     *
     * Delays: 1 s → 2 s → 4 s → … capped at 30 s.
     * [CancellationException] is always rethrown immediately.
     */
    private suspend fun <T> withRetry(maxRetries: Int = 3, block: suspend () -> T): T {
        for (attempt in 0..maxRetries) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (!isRetryableError(e) || attempt >= maxRetries) throw e
                // Exponential backoff: 1s, 2s, 4s (with default maxRetries=3)
                val backoffMs = minOf(1_000L shl attempt, 30_000L)
                delay(backoffMs)
            }
        }
        error("withRetry: loop exited without returning — this should never happen")
    }

    private fun isRetryableError(e: IOException): Boolean {
        val msg = e.message ?: return false
        return msg.contains("429") || msg.contains("Rate limit") ||
            msg.contains("500") || msg.contains("502") || msg.contains("503") ||
            msg.contains("Retrying")
    }

    // ── GitHub Copilot session token ──────────────────────────────────────────
    // Delegated entirely to CopilotSessionManager, which:
    //  • Maintains a persistent cache in SharedPreferences (survives app restarts)
    //  • Keeps an in-memory fast-path to avoid I/O on every request
    //  • Uses a coroutine Mutex to prevent concurrent exchange races
    //  • Exchanges the OAuth token via GET https://api.github.com/copilot_internal/v2/token
    //    with the "vscode-chat" Copilot-Integration-Id — same as VS Code and opencode.

    /**
     * Returns the effective API key for a request.
     * For GITHUB_COPILOT the raw OAuth token is exchanged for a short-lived session
     * token via [CopilotSessionManager].  For all other providers the key is returned
     * as-is.
     *
     * NOTE: This is a blocking wrapper around a suspend function — it must only be
     * called from an existing coroutine context (all call-sites are inside suspend funs).
     */
    private suspend fun resolveApiKey(provider: ModelProvider, rawKey: String): String =
        if (provider == ModelProvider.GITHUB_COPILOT)
            CopilotSessionManager.getSessionToken(rawKey)
        else
            rawKey

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
        ModelProvider.COHERE        -> "https://api.cohere.ai/compatibility/v1"
        ModelProvider.TOGETHER      -> "https://api.together.xyz/v1"
        ModelProvider.FIREWORKS     -> "https://api.fireworks.ai/inference/v1"
        ModelProvider.PERPLEXITY    -> "https://api.perplexity.ai"
        ModelProvider.NVIDIA        -> "https://integrate.api.nvidia.com/v1"
        ModelProvider.MINIMAX       -> "https://api.minimax.chat/v1"
        ModelProvider.VERCEL_AI_GATEWAY -> "https://ai-gateway.vercel.sh/v1"
        ModelProvider.HUGGING_FACE  -> "https://router.huggingface.co/v1"
        ModelProvider.GITHUB_COPILOT -> "https://api.githubcopilot.com"
        ModelProvider.GITHUB_MODELS  -> "https://models.inference.ai.azure.com"
        ModelProvider.OPEN_ROUTER   -> "https://openrouter.ai/api/v1"
        ModelProvider.LOCAL_EDGE    -> "http://localhost"
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
                ?: ModelRegistry.getModelById(request.modelId)

            if (model.provider == ModelProvider.LOCAL_EDGE) {
                return@withContext callLocalEdge(request)
            }

            val apiKey = request.apiKey
                ?: throw IOException(
                    "No API key configured for ${model.provider.displayName}. " +
                        "Add one via the Providers screen (Settings → API Keys)."
                )

            if (model.provider == ModelProvider.ANTHROPIC) {
                withRetry { callAnthropic(request, model.provider, apiKey) }
            } else {
                withRetry { callOpenAiCompatible(request, model.provider, apiKey) }
            }
        }

    // ── Local Edge (on-device GGUF) ───────────────────────────────────────────

    private suspend fun callLocalEdge(request: CompletionRequest): CompletionResponse {
        val engine = LocalEngineHolder.engine
        if (!engine.isLoaded) {
            throw IOException(
                "No local model loaded. Please load a GGUF model in Settings → Local Edge Model."
            )
        }
        val prompt = buildLocalPrompt(request)
        val sb = StringBuilder()
        engine.generateResponse(prompt).collect { token -> sb.append(token) }
        return CompletionResponse(content = sb.toString())
    }

    private suspend fun streamLocalEdge(
        request: CompletionRequest,
        onChunk: suspend (String) -> Unit
    ): CompletionResponse {
        val engine = LocalEngineHolder.engine
        if (!engine.isLoaded) {
            throw IOException(
                "No local model loaded. Please load a GGUF model in Settings → Local Edge Model."
            )
        }
        val prompt = buildLocalPrompt(request)
        val sb = StringBuilder()
        engine.generateResponse(prompt).collect { token ->
            onChunk(token)
            sb.append(token)
        }
        return CompletionResponse(content = sb.toString())
    }

    /**
     * Builds a plain-text prompt from the request's system prompt and message history,
     * suitable for local llama.cpp inference (no JSON/tool-call formatting).
     */
    private fun buildLocalPrompt(request: CompletionRequest): String {
        val sb = StringBuilder()
        if (!request.systemPrompt.isNullOrBlank()) {
            sb.append("System: ").append(request.systemPrompt).append("\n\n")
        }
        for (msg in request.messages) {
            val roleLabel = when (msg.role) {
                MessageRole.USER      -> "User"
                MessageRole.ASSISTANT -> "Assistant"
                MessageRole.SYSTEM    -> "System"
                MessageRole.TOOL      -> "Tool"
            }
            if (msg.content.isNotBlank()) {
                sb.append(roleLabel).append(": ").append(msg.content).append("\n")
            }
        }
        sb.append("Assistant:")
        return sb.toString()
    }

    // ── Anthropic Messages API ────────────────────────────────────────────────

    private suspend fun callAnthropic(
        request: CompletionRequest,
        provider: ModelProvider,
        apiKey: String
    ): CompletionResponse {
        val url = URL("${baseUrlFor(provider)}/v1/messages")

        val messages = run {
            val ephemeral = buildJsonObject { put("type", "ephemeral") }
            val raw = request.messages
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
            // Prompt caching: mark the last message as ephemeral so the cache
            // boundary falls at the end of the current conversation turn.
            if (raw.isNotEmpty()) {
                raw.dropLast(1) + raw.last().copy(cacheControl = ephemeral)
            } else raw
        }

        val anthropicTools = request.tools?.map { it.toAnthropicToolDef() }

        val body = json.encodeToString(
            AnthropicRequest.serializer(),
            AnthropicRequest(
                model = request.modelId.substringAfter("::"),
                maxTokens = request.maxTokens,
                messages = messages,
                system = request.systemPrompt,
                tools = anthropicTools
            )
        )

        val responseJson = postJson(
            url = url,
            body = body,
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
                "anthropic-beta" to "prompt-caching-2024-07-31"
            )
        )

        val parsed = json.decodeFromString(AnthropicResponse.serializer(), responseJson)
        val textContent = parsed.content
            .filter { it.type == "text" }
            .joinToString("") { it.text }

        val toolCalls = parsed.content
            .filter { it.type == "tool_use" }
            .mapIndexed { idx, block ->
                ToolCall(
                    id = block.id ?: "tool_$idx",
                    name = block.name ?: "",
                    arguments = parseJsonElementToStringMap(block.input)
                )
            }

        return CompletionResponse(
            content = textContent,
            toolCalls = toolCalls,
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

    private suspend fun callOpenAiCompatible(
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

        val openAiTools = request.tools?.map { it.toOpenAiToolDef() }

        // GitHub Models uses a "github/" prefix in registry IDs for uniqueness;
        // strip it before sending to the Azure inference endpoint.
        // GitHub Copilot uses a "copilot/" prefix similarly; strip before sending to api.githubcopilot.com.
        val rawModelId = request.modelId.substringAfter("::")
        val apiModelId = when (provider) {
            ModelProvider.GITHUB_MODELS  -> rawModelId.removePrefix("github/")
            ModelProvider.GITHUB_COPILOT -> rawModelId.removePrefix("copilot/")
            else                         -> rawModelId
        }

        val body = json.encodeToString(
            OpenAiRequest.serializer(),
            OpenAiRequest(
                model = apiModelId,
                messages = messages,
                maxTokens = request.maxTokens,
                temperature = request.temperature,
                tools = openAiTools,
                toolChoice = if (openAiTools != null) "auto" else null
            )
        )

        val extraHeaders: Map<String, String> = when (provider) {
            ModelProvider.GITHUB_COPILOT -> mapOf(
                "Editor-Version" to "vscode/1.0.0",
                "Editor-Plugin-Version" to "copilot-chat/0.1.0",
                "Copilot-Integration-Id" to "vscode-chat",
                "openai-organization" to "github-copilot"
            )
            ModelProvider.GITHUB_MODELS -> mapOf(
                "X-GitHub-Api-Version" to "2022-11-28"
            )
            else -> emptyMap()
        }

        val responseJson = postJson(
            url = url,
            body = body,
            headers = mapOf("Authorization" to "Bearer ${resolveApiKey(provider, apiKey)}") + extraHeaders
        )

        val parsed = json.decodeFromString(OpenAiResponse.serializer(), responseJson)
        val choice = parsed.choices.firstOrNull()

        val toolCalls = choice?.message?.toolCalls?.map { tc ->
            ToolCall(
                id = tc.id,
                name = tc.function.name,
                arguments = parseJsonStringToStringMap(tc.function.arguments)
            )
        } ?: emptyList()

        return CompletionResponse(
            content = choice?.message?.content ?: "",
            toolCalls = toolCalls,
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

    // ── Streaming completion (SSE) ────────────────────────────────────────────

    /**
     * Streams a completion request using Server-Sent Events (SSE).
     *
     * Calls the provider with `stream: true`, reads each `data:` line as it arrives,
     * and invokes [onChunk] with each text delta. Returns the accumulated
     * [CompletionResponse] once the stream is complete.
     *
     * This gives users a real-time typewriter-style response experience instead of
     * waiting for the entire response to arrive.
     *
     * @param request The completion request.
     * @param onChunk Called for each streamed text delta.
     * @throws IOException on network failure or non-2xx responses.
     */
    suspend fun stream(
        request: CompletionRequest,
        onChunk: suspend (String) -> Unit
    ): CompletionResponse = withContext(Dispatchers.IO) {
        val model = ModelRegistry.findModelById(request.modelId)
                ?: ModelRegistry.getModelById(request.modelId)

        if (model.provider == ModelProvider.LOCAL_EDGE) {
            return@withContext streamLocalEdge(request, onChunk)
        }

        val apiKey = request.apiKey
            ?: throw IOException(
                "No API key configured for ${model.provider.displayName}. " +
                    "Add one via the Providers screen (Settings → API Keys)."
            )

        if (model.provider == ModelProvider.ANTHROPIC) {
            withRetry { streamAnthropic(request, model.provider, apiKey, onChunk) }
        } else {
            withRetry { streamOpenAiCompatible(request, model.provider, apiKey, onChunk) }
        }
    }

    private suspend fun streamOpenAiCompatible(
        request: CompletionRequest,
        provider: ModelProvider,
        apiKey: String,
        onChunk: suspend (String) -> Unit
    ): CompletionResponse {
        val url = URL("${baseUrlFor(provider)}/chat/completions")

        val messages = buildList {
            request.systemPrompt?.let { add(OpenAiMessage("system", JsonPrimitive(it))) }
            addAll(request.messages.map { msg ->
                val role = when (msg.role) {
                    MessageRole.SYSTEM    -> "system"
                    MessageRole.ASSISTANT -> "assistant"
                    else                  -> "user"
                }
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
                                    put("url", "data:${img.mimeType};base64,${img.base64Data!!}")
                                })
                            })
                        }
                        add(buildJsonObject { put("type", "text"); put("text", msg.content) })
                    }
                }
                OpenAiMessage(role = role, content = content)
            })
        }

        // GitHub Models uses a "github/" prefix in registry IDs for uniqueness;
        // strip it before sending to the Azure inference endpoint.
        // GitHub Copilot uses a "copilot/" prefix similarly; strip before sending to api.githubcopilot.com.
        val rawModelId = request.modelId.substringAfter("::")
        val apiModelIdStream = when (provider) {
            ModelProvider.GITHUB_MODELS  -> rawModelId.removePrefix("github/")
            ModelProvider.GITHUB_COPILOT -> rawModelId.removePrefix("copilot/")
            else                         -> rawModelId
        }

        val openAiToolsStream = request.tools?.map { it.toOpenAiToolDef() }

        val body = json.encodeToString(
            OpenAiRequest.serializer(),
            OpenAiRequest(
                model = apiModelIdStream,
                messages = messages,
                maxTokens = request.maxTokens,
                temperature = request.temperature,
                stream = true,
                tools = openAiToolsStream,
                toolChoice = if (openAiToolsStream != null) "auto" else null
            )
        )

        val extraHeaders: Map<String, String> = when (provider) {
            ModelProvider.GITHUB_COPILOT -> mapOf(
                "Editor-Version" to "vscode/1.0.0",
                "Editor-Plugin-Version" to "copilot-chat/0.1.0",
                "Copilot-Integration-Id" to "vscode-chat",
                "openai-organization" to "github-copilot"
            )
            ModelProvider.GITHUB_MODELS -> mapOf(
                "X-GitHub-Api-Version" to "2022-11-28"
            )
            else -> emptyMap()
        }

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout    = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", "Bearer ${resolveApiKey(provider, apiKey)}")
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }

        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            val errorBody = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            conn.disconnect()
            val friendlyMessage = when (responseCode) {
                401 -> "API key is invalid or expired. Update it in Settings → API Keys."
                402 -> "Insufficient quota or billing issue. Check your account on the provider's dashboard."
                403 -> "Access forbidden. Your API key may not have permission to use this model."
                404 -> "Model not found (${url.host}). The selected model may not be available on your API tier."
                422 -> "Invalid request format. The provider rejected the payload (unprocessable entity)."
                429 -> "Rate limit exceeded. The agent will retry automatically after a short delay."
                500, 502, 503 -> "The provider's server encountered an error ($responseCode). Retrying…"
                else -> "API error $responseCode from ${url.host}: $errorBody"
            }
            throw IOException(friendlyMessage)
        }

        val fullContent = StringBuilder()
        var totalTokens: OpenAiUsage? = null
        // Tool call accumulation: indexed by tool call index
        val tcIds   = mutableMapOf<Int, String>()
        val tcNames = mutableMapOf<Int, StringBuilder>()
        val tcArgs  = mutableMapOf<Int, StringBuilder>()

        conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!
                if (!l.startsWith("data: ")) continue
                val data = l.removePrefix("data: ").trim()
                if (data == "[DONE]") break
                try {
                    val chunk = json.decodeFromString(OpenAiStreamChunk.serializer(), data)
                    if (chunk.usage != null) {
                        totalTokens = chunk.usage
                    }
                    val choice = chunk.choices.firstOrNull() ?: continue
                    // Accumulate text content
                    val textDelta = choice.delta.content
                    if (!textDelta.isNullOrEmpty()) {
                        fullContent.append(textDelta)
                        onChunk(textDelta)
                    }
                    // Accumulate tool call deltas
                    choice.delta.toolCalls?.forEach { tcDelta ->
                        val idx = tcDelta.index
                        tcDelta.id?.let { if (it.isNotEmpty()) tcIds[idx] = it }
                        tcDelta.function?.name?.let { name ->
                            if (name.isNotEmpty()) tcNames.getOrPut(idx) { StringBuilder() }.append(name)
                        }
                        tcDelta.function?.arguments?.let { args ->
                            tcArgs.getOrPut(idx) { StringBuilder() }.append(args)
                        }
                    }
                } catch (_: kotlinx.serialization.SerializationException) { /* skip malformed SSE events */ }
            }
        }
        conn.disconnect()

        val toolCalls = tcIds.keys.sorted().map { idx ->
            ToolCall(
                id        = tcIds[idx]   ?: "tool_$idx",
                name      = tcNames[idx]?.toString() ?: "",
                arguments = parseJsonStringToStringMap(tcArgs[idx]?.toString() ?: "{}")
            )
        }

        return CompletionResponse(
            content      = fullContent.toString(),
            toolCalls    = toolCalls,
            finishReason = "stop",
            tokensUsed   = totalTokens?.let { TokenUsage(it.promptTokens, it.completionTokens, it.totalTokens) }
        )
    }

    private suspend fun streamAnthropic(
        request: CompletionRequest,
        provider: ModelProvider,
        apiKey: String,
        onChunk: suspend (String) -> Unit
    ): CompletionResponse {
        val url = URL("${baseUrlFor(provider)}/v1/messages")

        val messages = run {
            val ephemeral = buildJsonObject { put("type", "ephemeral") }
            val raw = request.messages
                .filter { it.role != MessageRole.SYSTEM }
                .map { msg ->
                    val role = when (msg.role) {
                        MessageRole.USER, MessageRole.TOOL -> "user"
                        else -> "assistant"
                    }
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
                                        put("data", img.base64Data!!)
                                    })
                                })
                            }
                            add(buildJsonObject { put("type", "text"); put("text", msg.content) })
                        }
                    }
                    AnthropicMessage(role = role, content = content)
                }
            // Prompt caching: mark the last message as ephemeral.
            if (raw.isNotEmpty()) {
                raw.dropLast(1) + raw.last().copy(cacheControl = ephemeral)
            } else {
                raw
            }
        }

        val anthropicTools = request.tools?.map { it.toAnthropicToolDef() }

        val body = json.encodeToString(
            AnthropicRequest.serializer(),
            AnthropicRequest(
                model = request.modelId.substringAfter("::"),
                maxTokens = request.maxTokens,
                messages = messages,
                system = request.systemPrompt,
                stream = true,
                tools = anthropicTools
            )
        )

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout    = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
            setRequestProperty("anthropic-beta", "prompt-caching-2024-07-31")
        }

        conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }

        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            val errorBody = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            conn.disconnect()
            val friendlyMessage = when (responseCode) {
                401 -> "API key is invalid or expired. Update it in Settings → API Keys."
                402 -> "Insufficient quota or billing issue. Check your account on the provider's dashboard."
                403 -> "Access forbidden. Your API key may not have permission to use this model."
                404 -> "Model not found. The selected model may not be available on your API tier."
                429 -> "Rate limit exceeded. The agent will retry automatically after a short delay."
                500, 502, 503 -> "Anthropic server error ($responseCode). Retrying…"
                else -> "API error $responseCode from ${url.host}: $errorBody"
            }
            throw IOException(friendlyMessage)
        }

        val fullContent = StringBuilder()
        // Tool-use accumulation: indexed by content block index
        val toolUseIds   = mutableMapOf<Int, String>()
        val toolUseNames = mutableMapOf<Int, String>()
        val toolUseArgs  = mutableMapOf<Int, StringBuilder>()
        var inputTokens = 0
        var outputTokens = 0

        conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!
                if (!l.startsWith("data: ")) continue
                val data = l.removePrefix("data: ").trim()
                try {
                    val event = json.decodeFromString(AnthropicStreamEvent.serializer(), data)
                    if (event.type == "message_start" && event.message?.usage != null) {
                        inputTokens += event.message.usage.inputTokens
                        outputTokens += event.message.usage.outputTokens
                    } else if (event.type == "message_delta" && event.usage != null) {
                        inputTokens += event.usage.inputTokens
                        outputTokens += event.usage.outputTokens
                    }
                    when (event.type) {
                        "content_block_start" -> {
                            val block = event.contentBlock
                            if (block?.type == "tool_use") {
                                val idx = event.index ?: 0
                                toolUseIds[idx]   = block.id   ?: "tool_$idx"
                                toolUseNames[idx] = block.name ?: ""
                            }
                        }
                        "content_block_delta" -> {
                            val idx = event.index ?: 0
                            val delta = event.delta
                            when (delta?.type) {
                                "text_delta" -> {
                                    val text = delta.text
                                    if (!text.isNullOrEmpty()) {
                                        fullContent.append(text)
                                        onChunk(text)
                                    }
                                }
                                "input_json_delta" -> {
                                    val partial = delta.partialJson
                                    if (!partial.isNullOrEmpty()) {
                                        toolUseArgs.getOrPut(idx) { StringBuilder() }.append(partial)
                                    }
                                }
                            }
                        }
                    }
                } catch (_: kotlinx.serialization.SerializationException) { /* skip malformed SSE events */ }
            }
        }
        conn.disconnect()

        val toolCalls = toolUseIds.keys.sorted().map { idx ->
            ToolCall(
                id        = toolUseIds[idx]   ?: "tool_$idx",
                name      = toolUseNames[idx] ?: "",
                arguments = parseJsonStringToStringMap(toolUseArgs[idx]?.toString() ?: "{}")
            )
        }

        return CompletionResponse(
            content      = fullContent.toString(),
            toolCalls    = toolCalls,
            finishReason = "end_turn",
            tokensUsed   = if (inputTokens > 0 || outputTokens > 0) TokenUsage(inputTokens, outputTokens, inputTokens + outputTokens) else null
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

    // ── Tool definition conversion helpers ───────────────────────────────────

    /**
     * Converts a generic [ToolDefinition] to Anthropic's `input_schema` format.
     */
    private fun ToolDefinition.toAnthropicToolDef(): AnthropicToolDef {
        val required = parameters.filter { it.required }.map { it.name }
        val inputSchema: JsonElement = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                parameters.forEach { param ->
                    putJsonObject(param.name) {
                        put("type", param.type.lowercase())
                        put("description", param.description)
                    }
                }
            }
            if (required.isNotEmpty()) {
                putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
            }
        }
        return AnthropicToolDef(name = name, description = description, inputSchema = inputSchema)
    }

    /**
     * Converts a generic [ToolDefinition] to OpenAI's function tool format.
     * Always includes a `required` array (even when empty) to satisfy strict validators
     * such as the Gemini OpenAI-compatibility endpoint.
     */
    private fun ToolDefinition.toOpenAiToolDef(): OpenAiToolDef {
        val required = parameters.filter { it.required }.map { it.name }
        val parameters: JsonElement = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                this@toOpenAiToolDef.parameters.forEach { param ->
                    putJsonObject(param.name) {
                        put("type", param.type.lowercase())
                        put("description", param.description)
                    }
                }
            }
            // Always include 'required' — even as an empty array — to satisfy
            // strict OpenAPI validators (required by Gemini's OpenAI-compatible endpoint).
            putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        }
        return OpenAiToolDef(function = OpenAiFunction(
            name = name,
            description = description,
            parameters = parameters
        ))
    }

    /**
     * Parses a [JsonElement] tool `input` (from Anthropic `tool_use` block) into
     * a flat [Map<String, String>] suitable for [ToolCall.arguments].
     */
    private fun parseJsonElementToStringMap(element: JsonElement?): Map<String, String> {
        if (element == null || element !is JsonObject) return emptyMap()
        return element.entries.associate { (k, v) ->
            k to when (v) {
                is JsonPrimitive -> v.content
                else -> v.toString()
            }
        }
    }

    /**
     * Parses an OpenAI tool-call `arguments` JSON string (e.g. `{"path":"foo.kt"}`)
     * into a flat [Map<String, String>] suitable for [ToolCall.arguments].
     * Returns an empty map if the string is blank or cannot be parsed.
     */
    private fun parseJsonStringToStringMap(argumentsJson: String): Map<String, String> {
        if (argumentsJson.isBlank()) return emptyMap()
        return try {
            val parsed = json.parseToJsonElement(argumentsJson)
            parseJsonElementToStringMap(parsed)
        } catch (_: Exception) {
            emptyMap()
        }
    }
}


object CompletionPayloadParser {

    fun buildOpenAiStreamingPayload(prompt: String, model: String): String {
        return org.json.JSONObject().apply {
            put("model", model)
            put("messages", listOf(mapOf("role" to "user", "content" to prompt)))
            put("stream", true)
            put("stream_options", org.json.JSONObject().apply {
                put("include_usage", true)
            })
        }.toString()
    }

    fun extractTokenUsage(responseJson: org.json.JSONObject, isGemini: Boolean): Pair<Int, Int> {
        return if (isGemini) {
            val usage = responseJson.optJSONObject("usageMetadata")
            val prompt = usage?.optInt("promptTokenCount", 0) ?: 0
            val completion = usage?.optInt("candidatesTokenCount", 0) ?: 0
            Pair(prompt, completion)
        } else {
            val usage = responseJson.optJSONObject("usage")
            val prompt = usage?.optInt("prompt_tokens", 0) ?: 0
            val completion = usage?.optInt("completion_tokens", 0) ?: 0
            Pair(prompt, completion)
        }
    }
}
