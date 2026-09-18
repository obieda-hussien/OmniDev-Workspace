package com.omnidev.workspace.data.model

import com.omnidev.workspace.data.tools.ToolDefinition
import kotlinx.serialization.Serializable

/**
 * Represents a single message in the AI conversation, including tool calls and results.
 */
@Serializable
data class ChatMessage(
    val role: MessageRole,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolResults: List<ToolCallResult> = emptyList(),
    val thinkingContent: String? = null,
    val attachments: List<AttachmentMeta> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val messageId: String = java.util.UUID.randomUUID().toString(),
    val replyToMessageId: String? = null,
    val executionRequest: ExecutionModeRequest? = null
)

@Serializable
enum class MessageRole {
    USER, ASSISTANT, SYSTEM, TOOL
}

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, String>,
    /** Opaque provider metadata (including Gemini thought signatures); replay unchanged. */
    val extraContent: kotlinx.serialization.json.JsonObject? = null
)

@Serializable
data class ToolCallResult(
    val toolCallId: String,
    val toolName: String,
    val output: String,
    val isError: Boolean = false
)

@Serializable
data class AttachmentMeta(
    val uri: String,
    val mimeType: String,
    val fileName: String,
    val sizeBytes: Long,
    val mediaType: AttachmentMediaType,
    val base64Data: String? = null
)

@Serializable
enum class AttachmentMediaType {
    IMAGE, PDF, TEXT, VIDEO, UNKNOWN
}

/**
 * Payload sent to an AI completion endpoint.
 *
 * Request-boundary hygiene is centralized here so every provider/caller receives:
 * - no replayed/private chain-of-thought,
 * - the observable execution policy,
 * - bounded/deduplicated native tool schemas.
 */
@Serializable
data class CompletionRequest(
    val modelId: String,
    var messages: List<ChatMessage>,
    var systemPrompt: String? = null,
    val maxTokens: Int = 4096,
    val temperature: Double = 0.7,
    val enableThinking: Boolean = false,
    val targetContext: String? = null,
    /** The resolved API key for the target provider. */
    val apiKey: String? = null,
    /** Native function-calling definitions, compacted in [init]. */
    var tools: List<ToolDefinition>? = null,
    val customBaseUrl: String? = null,
    val customModelId: String? = null,
    /**
     * Legacy/provider reasoning callback. Cleared in [init] so private reasoning
     * is not streamed into the user-visible console. Operational progress still
     * comes from deterministic AgentEvent phase/tool/result telemetry.
     */
    @kotlinx.serialization.Transient
    var onReasoning: (suspend (String) -> Unit)? = null
) {
    init {
        messages = AgentPromptSanitizer.sanitizeMessages(messages)
        systemPrompt = AgentPromptSanitizer.sanitizeSystemPrompt(systemPrompt)
        tools = ToolSchemaCompactor.compact(tools, messages)
        onReasoning = null
    }
}

/**
 * Response from an AI completion endpoint.
 *
 * Providers may still use hidden reasoning internally, but raw chain-of-thought
 * is not stored, replayed into future turns, or rendered in the agent console.
 */
@Serializable
data class CompletionResponse(
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    var thinkingContent: String? = null,
    val finishReason: String? = null,
    val tokensUsed: TokenUsage? = null
) {
    init {
        thinkingContent = null
    }
}

@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
)

/**
 * A user-visible request to move the same execution session to another mode.
 *
 * Extra fields have defaults so old persisted messages remain deserializable.
 * The request never constitutes permission by itself; UI/policy approval is required.
 */
@Serializable
data class ExecutionModeRequest(
    val mode: String,
    val reason: String,
    val originMessageId: String,
    val status: String = "pending",
    val sourceMode: String? = null,
    val confidence: Float? = null,
    val trigger: String? = null
)
