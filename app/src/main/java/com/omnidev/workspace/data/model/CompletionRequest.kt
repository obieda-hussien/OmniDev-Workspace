package com.omnidev.workspace.data.model

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
    val timestamp: Long = System.currentTimeMillis()
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
 */
@Serializable
data class AttachmentMeta(
    val uri: String,
    val mimeType: String,
    val fileName: String,
    val sizeBytes: Long,
    val mediaType: AttachmentMediaType
)

@Serializable
enum class AttachmentMediaType {
    IMAGE, PDF, TEXT, VIDEO, UNKNOWN
}

/**
 * Payload sent to an AI completion endpoint.
 * Structured to support Anthropic, OpenAI, and Gemini API formats.
 */
@Serializable
data class CompletionRequest(
    val modelId: String,
    val messages: List<ChatMessage>,
    val systemPrompt: String? = null,
    val maxTokens: Int = 4096,
    val temperature: Double = 0.7,
    val enableThinking: Boolean = false,
    val targetContext: String? = null
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
