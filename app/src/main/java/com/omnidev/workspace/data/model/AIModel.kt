package com.omnidev.workspace.data.model

import kotlinx.serialization.Serializable

/**
 * Represents a specific AI model available for use.
 *
 * @property id Unique identifier used in API requests (e.g., "claude-sonnet-4-20250514").
 * @property displayName Human-readable name shown in the UI.
 * @property provider The AI provider that hosts this model.
 * @property contextWindow Maximum token context window size.
 * @property supportsVision Whether the model can process image inputs.
 * @property supportsVideo Whether the model can process video inputs natively.
 * @property supportsThinking Whether the model supports extended thinking / chain-of-thought.
 * @property maxOutputTokens Maximum tokens the model can generate in a single response.
 */
@Serializable
data class AIModel(
    val id: String,
    val displayName: String,
    val provider: ModelProvider,
    val contextWindow: Int,
    val supportsVision: Boolean = false,
    val supportsVideo: Boolean = false,
    val supportsThinking: Boolean = false,
    val maxOutputTokens: Int = 4096
)

/**
 * Supported AI model providers.
 */
@Serializable
enum class ModelProvider(val displayName: String) {
    ANTHROPIC("Anthropic"),
    OPENAI("OpenAI"),
    GEMINI("Google Gemini"),
    GROQ("Groq"),
    OPEN_ROUTER("OpenRouter")
}

/**
 * Defines the four distinct functional roles a model can be assigned to.
 * Each role serves a specific purpose in the agent pipeline.
 */
@Serializable
enum class ModelRole(val displayName: String, val description: String) {
    /** General question-and-answer interactions with the user. */
    CHAT("Chat Model", "General Q&A and conversational responses"),

    /** Single-step ReAct loop executor for autonomous tool use. */
    AGENT("Agent Model", "Single ReAct loop tool executor"),

    /** The planner model in Swarm/Team mode that decomposes tasks. */
    SWARM_ORCHESTRATOR("Swarm Orchestrator", "Task decomposition planner in Team mode"),

    /** The coder model in Swarm/Team mode that executes sub-tasks. */
    SWARM_WORKER("Swarm Worker", "Code executor in Team mode")
}
