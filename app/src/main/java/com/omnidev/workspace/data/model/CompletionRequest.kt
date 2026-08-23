package com.omnidev.workspace.data.model

import com.omnidev.workspace.data.tools.ToolDefinition
import kotlinx.serialization.Serializable

/**
 * Represents a single message in the AI conversation, including tool calls and results.
 *
 * @property messageId Stable UUID string identifying this message. Generated on creation;
 *   restored from the database when loading past messages so references stay consistent.
 * @property replyToMessageId When non-null, this message is a reply to the message with the
 *   given [messageId]. Drives the WhatsApp-style quoted-reply UI and agent context injection.
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
    val replyToMessageId: String? = null
)

@Serializable
enum class MessageRole {
    USER, ASSISTANT, SYSTEM, TOOL
}

/**
 * Represents a tool invocation requested by the AI model.
 */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, String>
)

/**
 * Represents the result of executing a tool call.
 */
@Serializable
data class ToolCallResult(
    val toolCallId: String,
    val toolName: String,
    val output: String,
    val isError: Boolean = false
)

/**
 * Metadata for an attached file (image, PDF, text, video).
 *
 * @property base64Data Optional Base64-encoded content of the file, populated for
 *           image attachments when the target model supports vision input.
 *           Not persisted to the DB — only used in-memory for the active request.
 */
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
 * Structured to support Anthropic, OpenAI, Gemini, and OpenAI-compatible API formats.
 */
@Serializable
data class CompletionRequest(
    val modelId: String,
    val messages: List<ChatMessage>,
    val systemPrompt: String? = null,
    val maxTokens: Int = 4096,
    val temperature: Double = 0.7,
    val enableThinking: Boolean = false,
    val targetContext: String? = null,
    /** The resolved API key for the target provider. Populated by [AgentPipeline]. */
    val apiKey: String? = null,
    /**
     * Native function-calling tool definitions. When non-null, [CompletionService]
     * includes them in the API request so the model can invoke tools via the provider's
     * structured tool-call mechanism instead of raw text output.
     */
    val tools: List<ToolDefinition>? = null,
    val customBaseUrl: String? = null,
    val customModelId: String? = null
)

/**
 * Response from an AI completion endpoint.
 */
@Serializable
data class CompletionResponse(
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val thinkingContent: String? = null,
    val finishReason: String? = null,
    val tokensUsed: TokenUsage? = null
)

@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
)
