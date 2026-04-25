package com.omnidev.workspace.data.model

import kotlinx.serialization.Serializable

/**
 * Functional tier of an AI model — determines its role in the agent pipeline.
 *
 * Tier is used for intelligent auto-routing: the [AgentPipeline] can fall back
 * to a cheaper EXECUTOR model for simple tasks and escalate to the ORCHESTRATOR tier
 * for complex planning or multi-file analysis.
 */
@Serializable
enum class ModelTier(val displayName: String, val badge: String) {
    /** Largest, most capable models. Best for complex reasoning, architecture, and long-horizon agents. */
    ORCHESTRATOR("Orchestrator", "🧠"),

    /** Balanced models for fast code generation, refactoring, and tool calls. */
    EXECUTOR("Executor", "⚡"),

    /** Smallest, fastest models targeting <300ms TTFT. Best for inline completions and quick edits. */
    FAST("Fast", "🚀")
}

/**
 * Represents a specific AI model available for use.
 *
 * @property id Unique identifier used in API requests (e.g., "claude-opus-4-6").
 * @property displayName Human-readable name shown in the UI.
 * @property provider The AI provider that hosts this model.
 * @property tier The functional tier for agent-pipeline routing.
 * @property contextWindow Maximum input token context window size.
 * @property maxOutputTokens Maximum tokens the model can generate in a single response.
 * @property supportsVision Whether the model can process image inputs.
 * @property supportsVideo Whether the model can process video inputs natively.
 * @property supportsThinking Whether the model supports extended thinking / chain-of-thought.
 * @property supportsFunctionCalling Whether the model supports structured tool/function calls.
 * @property supportsStructuredOutput Whether the model can emit guaranteed-valid JSON schemas.
 * @property costPer1MInputTokens USD cost per 1M input tokens (null = unknown/free).
 * @property costPer1MOutputTokens USD cost per 1M output tokens (null = unknown/free).
 * @property speedTokensPerSecond Approximate inference speed in tokens/second (null = unknown).
 * @property shortDescription A brief one-line summary of the model's key strengths.
 * @property isLatest Whether this is the current recommended version of the model.
 */
@Serializable
data class AIModel(
    val id: String,
    val displayName: String,
    val provider: ModelProvider,
    val tier: ModelTier = ModelTier.EXECUTOR,
    val contextWindow: Int,
    val maxOutputTokens: Int = 4096,
    val supportsVision: Boolean = false,
    val supportsVideo: Boolean = false,
    val supportsThinking: Boolean = false,
    val supportsFunctionCalling: Boolean = true,
    val supportsStructuredOutput: Boolean = false,
    val costPer1MInputTokens: Double? = null,
    val costPer1MOutputTokens: Double? = null,
    val speedTokensPerSecond: Int? = null,
    val shortDescription: String? = null,
    val isLatest: Boolean = false
)

/**
 * Supported AI model providers.
 * Ordered by general agentic coding reputation (descending).
 */
@Serializable
enum class ModelProvider(val displayName: String) {
    ANTHROPIC("Anthropic"),
    OPENAI("OpenAI"),
    GEMINI("Google Gemini"),
    XAI("xAI (Grok)"),
    DEEPSEEK("DeepSeek"),
    MISTRAL("Mistral AI"),
    GROQ("Groq"),
    CEREBRAS("Cerebras"),
    COHERE("Cohere"),
    TOGETHER("Together AI"),
    FIREWORKS("Fireworks AI"),
    PERPLEXITY("Perplexity AI"),
    NVIDIA("NVIDIA NIM"),
    MINIMAX("MiniMax"),
    VERCEL_AI_GATEWAY("Vercel AI Gateway"),
    HUGGING_FACE("Hugging Face"),
    GITHUB_COPILOT("GitHub Copilot"),
    GITHUB_MODELS("GitHub Models"),
    OPEN_ROUTER("OpenRouter"),
    LOCAL_EDGE("Local Edge Model")
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

