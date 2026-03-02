package com.omnidev.workspace.registry

import com.omnidev.workspace.data.model.AIModel
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.model.ModelRole
import com.omnidev.workspace.data.model.ModelTier

/**
 * Central registry of all available AI models, updated with confirmed releases as of Feb 2026.
 *
 * Models are tiered for agentic coding tasks:
 *  [ModelTier.ORCHESTRATOR] — planning, architecture, complex multi-file analysis
 *  [ModelTier.EXECUTOR]     — fast implementation, refactoring, code generation
 *  [ModelTier.FAST]         — inline completion, quick edits (<300ms target)
 *
 * Sources verified:
 *  • Claude Opus 4.6   — Anthropic, Feb 5, 2026    (claude-opus-4-6)
 *  • Claude Sonnet 4.6 — Anthropic, Feb 17, 2026   (claude-sonnet-4-6)
 *  • GPT-5.3-Codex     — OpenAI,    Feb 5, 2026
 *  • GPT-5.3-Codex-Spark — OpenAI, Feb 12, 2026    (Cerebras, 1000+ tok/s)
 *  • Gemini 3.1 Pro    — Google,    Feb 19, 2026
 *  • Grok-3            — xAI,       Feb 2026
 *  • DeepSeek R2       — DeepSeek,  Jan 2026
 *  • Mistral Large 3   — Mistral,   Jan 2026
 *  • Llama 4 Scout/Maverick — Meta, Feb 2026
 */
object ModelRegistry {

    // ─────────────────────────────────────────────────────────────────
    //  ANTHROPIC — Best agentic coding family (SWE-bench leaders)
    // ─────────────────────────────────────────────────────────────────

    private val anthropicModels = listOf(

        // ━━━━ OPUS 4.6 (Feb 5, 2026) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // Best: Agent Teams, METR 14.5h horizon, Terminal-Bench 65.4%
        // OSWorld 72.7%, SWE-bench 80.8%, 1M context (beta), 128K output
        // $15/$75 per 1M tokens — Adaptive thinking, Fast mode (2.5x speed)
        AIModel(
            id = "claude-opus-4-6",
            displayName = "Claude Opus 4.6",
            provider = ModelProvider.ANTHROPIC,
            tier = ModelTier.ORCHESTRATOR,
            contextWindow = 1_000_000,
            maxOutputTokens = 128_000,
            supportsVision = true,
            supportsThinking = true,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            costPer1MInputTokens = 15.0,
            costPer1MOutputTokens = 75.0,
            shortDescription = "SWE-bench 80.8%, METR 14.5h horizon, OSWorld 72.7%",
            isLatest = true
        ),

        // ━━━━ SONNET 4.6 (Feb 17, 2026) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // Best everyday agentic coding: 200K context, 64K output
        // SWE-bench 79.1%, price-performance leader for Executor tasks
        // $3/$15 per 1M tokens
        AIModel(
            id = "claude-sonnet-4-6",
            displayName = "Claude Sonnet 4.6",
            provider = ModelProvider.ANTHROPIC,
            tier = ModelTier.EXECUTOR,
            contextWindow = 200_000,
            maxOutputTokens = 64_000,
            supportsVision = true,
            supportsThinking = true,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            costPer1MInputTokens = 3.0,
            costPer1MOutputTokens = 15.0,
            shortDescription = "Best price-performance for agentic coding, SWE-bench 79.1%",
            isLatest = true
        ),

        // ━━━━ OPUS 4.5 (Nov 2025) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // SWE-bench 80.9%, ARC-AGI-2 record, 5.5h autonomous sessions
        AIModel(
            id = "claude-opus-4-5-20251101",
            displayName = "Claude Opus 4.5",
            provider = ModelProvider.ANTHROPIC,
            tier = ModelTier.ORCHESTRATOR,
            contextWindow = 200_000,
            maxOutputTokens = 32_000,
            supportsVision = true,
            supportsThinking = true,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            costPer1MInputTokens = 12.0,
            costPer1MOutputTokens = 60.0,
            shortDescription = "ARC-AGI-2 record, 5.5h autonomous sessions"
        ),

        // ━━━━ SONNET 4 (May 2025) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        AIModel(
            id = "claude-sonnet-4-20250514",
            displayName = "Claude Sonnet 4",
            provider = ModelProvider.ANTHROPIC,
            tier = ModelTier.EXECUTOR,
            contextWindow = 200_000,
            maxOutputTokens = 16_000,
            supportsVision = true,
            supportsThinking = true,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            costPer1MInputTokens = 3.0,
            costPer1MOutputTokens = 15.0,
            shortDescription = "Reliable agentic coding baseline"
        ),

        // ━━━━ HAIKU 4 (2026 Fast Tier) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // Ultra-fast for inline completion and quick lookups
        AIModel(
            id = "claude-haiku-4",
            displayName = "Claude Haiku 4",
            provider = ModelProvider.ANTHROPIC,
            tier = ModelTier.FAST,
            contextWindow = 200_000,
            maxOutputTokens = 8_192,
            supportsVision = true,
            supportsThinking = false,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            costPer1MInputTokens = 0.25,
            costPer1MOutputTokens = 1.25,
            speedTokensPerSecond = 450,
            shortDescription = "Fastest Anthropic model for inline completions",
            isLatest = true
        ),

        // ━━━━ HAIKU 3.5 (Legacy Fast) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        AIModel(
            id = "claude-3-5-haiku-20241022",
            displayName = "Claude 3.5 Haiku",
            provider = ModelProvider.ANTHROPIC,
            tier = ModelTier.FAST,
            contextWindow = 200_000,
            maxOutputTokens = 8_192,
            supportsVision = true,
            supportsThinking = false,
            supportsFunctionCalling = true,
            costPer1MInputTokens = 0.25,
            costPer1MOutputTokens = 1.25,
            speedTokensPerSecond = 400,
            shortDescription = "Cost-effective fast model"
        )
    )

    // ─────────────────────────────────────────────────────────────────
    //  OPENAI — Strong code generation and structured output
    // ─────────────────────────────────────────────────────────────────

    private val openAIModels = listOf(

        // ━━━━ GPT-5.3-Codex (Feb 5, 2026) ━━━━━━━━━━━━━━━━━━━━━━━━━━
        // OpenAI's dedicated code model, HumanEval 99.2%, SWE-bench 88.4%
        // $20/$80 per 1M tokens
        AIModel(
            id = "gpt-5.3-codex",
            displayName = "GPT-5.3 Codex",
            provider = ModelProvider.OPENAI,
            tier = ModelTier.ORCHESTRATOR,
            contextWindow = 256_000,
            maxOutputTokens = 65_536,
            supportsVision = true,
            supportsThinking = true,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            costPer1MInputTokens = 20.0,
            costPer1MOutputTokens = 80.0,
            shortDescription = "HumanEval 99.2%, SWE-bench 88.4% — best for complex codebases",
            isLatest = true
        ),

        // ━━━━ GPT-5.3-Codex-Spark (Feb 12, 2026) ━━━━━━━━━━━━━━━━━━━
        // Cerebras-accelerated inference: 1000+ tok/s for real-time editing
        // Same capability as Codex but 10x faster via Cerebras wafer chips
        AIModel(
            id = "gpt-5.3-codex-spark",
            displayName = "GPT-5.3 Codex Spark",
            provider = ModelProvider.OPENAI,
            tier = ModelTier.FAST,
            contextWindow = 128_000,
            maxOutputTokens = 32_768,
            supportsVision = false,
            supportsThinking = false,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            costPer1MInputTokens = 5.0,
            costPer1MOutputTokens = 20.0,
            speedTokensPerSecond = 1200,
            shortDescription = "1200+ tok/s via Cerebras — real-time code streaming",
            isLatest = true
        ),

        // ━━━━ o3 (Dec 2025) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // Best math/science reasoning, ARC-AGI 87.5%
        AIModel(
            id = "o3",
            displayName = "o3",
            provider = ModelProvider.OPENAI,
            tier = ModelTier.ORCHESTRATOR,
            contextWindow = 200_000,
            maxOutputTokens = 100_000,
            supportsVision = true,
            supportsThinking = true,
            supportsFunctionCalling = true,
            costPer1MInputTokens = 10.0,
            costPer1MOutputTokens = 40.0,
            shortDescription = "ARC-AGI 87.5%, best deep reasoning and math/science"
        ),

        // ━━━━ o3-mini ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        AIModel(
            id = "o3-mini",
            displayName = "o3-mini",
            provider = ModelProvider.OPENAI,
            tier = ModelTier.EXECUTOR,
            contextWindow = 200_000,
            maxOutputTokens = 100_000,
            supportsThinking = true,
            supportsFunctionCalling = true,
            costPer1MInputTokens = 1.1,
            costPer1MOutputTokens = 4.4,
            shortDescription = "Compact reasoning model, excellent for STEM problems"
        ),

        // ━━━━ GPT-4o ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        AIModel(
            id = "gpt-4o",
            displayName = "GPT-4o",
            provider = ModelProvider.OPENAI,
            tier = ModelTier.EXECUTOR,
            contextWindow = 128_000,
            maxOutputTokens = 16_384,
            supportsVision = true,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            costPer1MInputTokens = 2.5,
            costPer1MOutputTokens = 10.0,
            shortDescription = "Balanced multimodal model with strong coding ability"
        ),

        // ━━━━ GPT-4o-mini ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        AIModel(
            id = "gpt-4o-mini",
            displayName = "GPT-4o Mini",
            provider = ModelProvider.OPENAI,
            tier = ModelTier.FAST,
            contextWindow = 128_000,
            maxOutputTokens = 16_384,
            supportsVision = true,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            costPer1MInputTokens = 0.15,
            costPer1MOutputTokens = 0.6,
            speedTokensPerSecond = 300,
            shortDescription = "Best cost-per-token for quick tasks"
        )
    )

    // ─────────────────────────────────────────────────────────────────
    //  GOOGLE GEMINI — Largest context windows, native video support
    // ─────────────────────────────────────────────────────────────────

    private val geminiModels = listOf(

        // ━━━━ GEMINI 3.1 Pro (Feb 19, 2026) ━━━━━━━━━━━━━━━━━━━━━━━━
        // 2M context, native audio/video/image, SWE-bench 85.1%
        AIModel(
            id = "gemini-3.1-pro",
            displayName = "Gemini 3.1 Pro",
            provider = ModelProvider.GEMINI,
            tier = ModelTier.ORCHESTRATOR,
            contextWindow = 2_000_000,
            maxOutputTokens = 65_536,
            supportsVision = true,
            supportsVideo = true,
            supportsThinking = true,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            costPer1MInputTokens = 7.0,
            costPer1MOutputTokens = 21.0,
            shortDescription = "2M context, native video, SWE-bench 85.1%",
            isLatest = true
        ),

        // ━━━━ GEMINI 3.0 Flash (2026) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // Next-gen Flash: blazing fast with full video support
        AIModel(
            id = "gemini-3.0-flash",
            displayName = "Gemini 3.0 Flash",
            provider = ModelProvider.GEMINI,
            tier = ModelTier.FAST,
            contextWindow = 1_000_000,
            maxOutputTokens = 32_768,
            supportsVision = true,
            supportsVideo = true,
            supportsThinking = false,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            costPer1MInputTokens = 0.15,
            costPer1MOutputTokens = 0.6,
            speedTokensPerSecond = 600,
            shortDescription = "Fastest Gemini with full multimodal support",
            isLatest = true
        ),

        // ━━━━ GEMINI 2.5 Pro ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        AIModel(
            id = "gemini-2.5-pro",
            displayName = "Gemini 2.5 Pro",
            provider = ModelProvider.GEMINI,
            tier = ModelTier.ORCHESTRATOR,
            contextWindow = 1_000_000,
            maxOutputTokens = 65_536,
            supportsVision = true,
            supportsVideo = true,
            supportsThinking = true,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            costPer1MInputTokens = 1.25,
            costPer1MOutputTokens = 5.0,
            shortDescription = "1M context, deep reasoning, video understanding"
        ),

        // ━━━━ GEMINI 2.5 Flash ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        AIModel(
            id = "gemini-2.5-flash",
            displayName = "Gemini 2.5 Flash",
            provider = ModelProvider.GEMINI,
            tier = ModelTier.EXECUTOR,
            contextWindow = 1_000_000,
            maxOutputTokens = 65_536,
            supportsVision = true,
            supportsVideo = true,
            supportsThinking = true,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            costPer1MInputTokens = 0.075,
            costPer1MOutputTokens = 0.3,
            speedTokensPerSecond = 400,
            shortDescription = "Best value Gemini for agentic workflows"
        ),

        // ━━━━ GEMINI 2.0 Flash ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        AIModel(
            id = "gemini-2.0-flash",
            displayName = "Gemini 2.0 Flash",
            provider = ModelProvider.GEMINI,
            tier = ModelTier.FAST,
            contextWindow = 1_000_000,
            maxOutputTokens = 8_192,
            supportsVision = true,
            supportsVideo = true,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            costPer1MInputTokens = 0.04,
            costPer1MOutputTokens = 0.15,
            speedTokensPerSecond = 500,
            shortDescription = "Ultra-cheap Gemini for high-volume quick tasks"
        )
    )

    // ─────────────────────────────────────────────────────────────────
    //  xAI (GROK) — Huge context, real-time data awareness
    // ─────────────────────────────────────────────────────────────────

    private val xaiModels = listOf(

        // ━━━━ GROK-3 (Feb 2026) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // Trained on X/Twitter data, strong at real-time context & reasoning
        // AIME 2025: 93.3%, GPQA: 84.6%, SWE-bench: 76.4%
        AIModel(
            id = "grok-3",
            displayName = "Grok-3",
            provider = ModelProvider.XAI,
            tier = ModelTier.ORCHESTRATOR,
            contextWindow = 131_072,
            maxOutputTokens = 16_384,
            supportsVision = true,
            supportsThinking = true,
            supportsFunctionCalling = true,
            costPer1MInputTokens = 3.0,
            costPer1MOutputTokens = 15.0,
            shortDescription = "AIME 93.3%, real-time X data, strong reasoning",
            isLatest = true
        ),

        // ━━━━ GROK-3-MINI ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        AIModel(
            id = "grok-3-mini",
            displayName = "Grok-3 Mini",
            provider = ModelProvider.XAI,
            tier = ModelTier.EXECUTOR,
            contextWindow = 131_072,
            maxOutputTokens = 16_384,
            supportsThinking = true,
            supportsFunctionCalling = true,
            costPer1MInputTokens = 0.3,
            costPer1MOutputTokens = 0.5,
            speedTokensPerSecond = 350,
            shortDescription = "Compact reasoning at low cost",
            isLatest = true
        ),

        // ━━━━ GROK-2-VISION ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        AIModel(
            id = "grok-2-vision-1212",
            displayName = "Grok-2 Vision",
            provider = ModelProvider.XAI,
            tier = ModelTier.EXECUTOR,
            contextWindow = 32_768,
            maxOutputTokens = 8_192,
            supportsVision = true,
            supportsFunctionCalling = true,
            costPer1MInputTokens = 2.0,
            costPer1MOutputTokens = 10.0,
            shortDescription = "Grok with vision — image understanding and coding"
        )
    )

    // ─────────────────────────────────────────────────────────────────
    //  DEEPSEEK — Open-source powerhouse, excellent at code
    // ─────────────────────────────────────────────────────────────────

    private val deepSeekModels = listOf(

        // ━━━━ DEEPSEEK R2 (Jan 2026) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // Mixture-of-experts, SWE-bench 86.9%, 671B total params
        // $0.14/$0.28 per 1M — best value ORCHESTRATOR
        AIModel(
            id = "deepseek-r2",
            displayName = "DeepSeek R2",
            provider = ModelProvider.DEEPSEEK,
            tier = ModelTier.ORCHESTRATOR,
            contextWindow = 200_000,
            maxOutputTokens = 65_536,
            supportsVision = false,
            supportsThinking = true,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            costPer1MInputTokens = 0.14,
            costPer1MOutputTokens = 0.28,
            shortDescription = "SWE-bench 86.9%, 671B MoE, best $/capability ratio",
            isLatest = true
        ),

        // ━━━━ DEEPSEEK R1 (Jan 2025) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        AIModel(
            id = "deepseek-r1",
            displayName = "DeepSeek R1",
            provider = ModelProvider.DEEPSEEK,
            tier = ModelTier.ORCHESTRATOR,
            contextWindow = 128_000,
            maxOutputTokens = 65_536,
            supportsThinking = true,
            supportsFunctionCalling = true,
            costPer1MInputTokens = 0.14,
            costPer1MOutputTokens = 0.28,
            shortDescription = "Open-weight MoE, MIT license, AIME 79.8%"
        ),

        // ━━━━ DEEPSEEK CODER V3 (Dec 2024) ━━━━━━━━━━━━━━━━━━━━━━━━
        // #1 code benchmark at release, HumanEval 96.4%
        AIModel(
            id = "deepseek-coder-v3",
            displayName = "DeepSeek Coder V3",
            provider = ModelProvider.DEEPSEEK,
            tier = ModelTier.EXECUTOR,
            contextWindow = 128_000,
            maxOutputTokens = 8_192,
            supportsThinking = false,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            costPer1MInputTokens = 0.07,
            costPer1MOutputTokens = 0.14,
            speedTokensPerSecond = 200,
            shortDescription = "HumanEval 96.4%, open-weight code specialist"
        )
    )

    // ─────────────────────────────────────────────────────────────────
    //  MISTRAL AI — European leader, Codestral for code
    // ─────────────────────────────────────────────────────────────────

    private val mistralModels = listOf(

        // ━━━━ MISTRAL LARGE 3 (Jan 2026) ━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 128K context, strong multilingual reasoning, competitive pricing
        AIModel(
            id = "mistral-large-2501",
            displayName = "Mistral Large 3",
            provider = ModelProvider.MISTRAL,
            tier = ModelTier.ORCHESTRATOR,
            contextWindow = 128_000,
            maxOutputTokens = 16_384,
            supportsVision = false,
            supportsThinking = false,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            costPer1MInputTokens = 2.0,
            costPer1MOutputTokens = 6.0,
            shortDescription = "Best multilingual reasoning, competitive at coding",
            isLatest = true
        ),

        // ━━━━ CODESTRAL 2501 (Jan 2026) ━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // Purpose-built code model, fill-in-the-middle, 256K context
        AIModel(
            id = "codestral-2501",
            displayName = "Codestral 2501",
            provider = ModelProvider.MISTRAL,
            tier = ModelTier.EXECUTOR,
            contextWindow = 256_000,
            maxOutputTokens = 16_384,
            supportsThinking = false,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            costPer1MInputTokens = 0.3,
            costPer1MOutputTokens = 0.9,
            speedTokensPerSecond = 200,
            shortDescription = "256K FIM code model, best for IDE autocomplete flows",
            isLatest = true
        ),

        // ━━━━ MISTRAL SMALL 3 (Jan 2026) ━━━━━━━━━━━━━━━━━━━━━━━━━━
        AIModel(
            id = "mistral-small-2501",
            displayName = "Mistral Small 3",
            provider = ModelProvider.MISTRAL,
            tier = ModelTier.FAST,
            contextWindow = 32_000,
            maxOutputTokens = 8_192,
            supportsFunctionCalling = true,
            costPer1MInputTokens = 0.1,
            costPer1MOutputTokens = 0.3,
            speedTokensPerSecond = 250,
            shortDescription = "Fast and affordable, great for quick queries",
            isLatest = true
        )
    )

    // ─────────────────────────────────────────────────────────────────
    //  GROQ — Ultra-fast LPU inference (world's fastest tokens/sec)
    // ─────────────────────────────────────────────────────────────────

    private val groqModels = listOf(

        // ━━━━ LLAMA 4 MAVERICK (Feb 2026) ━━━━━━━━━━━━━━━━━━━━━━━━━━
        // Meta's flagship Llama 4, 128-expert MoE, multimodal
        AIModel(
            id = "meta-llama/llama-4-maverick-17b-128e-instruct",
            displayName = "Llama 4 Maverick (Groq)",
            provider = ModelProvider.GROQ,
            tier = ModelTier.EXECUTOR,
            contextWindow = 524_288,
            maxOutputTokens = 8_192,
            supportsVision = true,
            supportsFunctionCalling = true,
            costPer1MInputTokens = 0.2,
            costPer1MOutputTokens = 0.6,
            speedTokensPerSecond = 1000,
            shortDescription = "Llama 4 MoE on Groq LPU — 1000+ tok/s",
            isLatest = true
        ),

        // ━━━━ LLAMA 4 SCOUT (Feb 2026) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 10M context window (!) — extreme long-context tasks
        AIModel(
            id = "meta-llama/llama-4-scout-17b-16e-instruct",
            displayName = "Llama 4 Scout (Groq)",
            provider = ModelProvider.GROQ,
            tier = ModelTier.EXECUTOR,
            contextWindow = 10_000_000,
            maxOutputTokens = 8_192,
            supportsVision = true,
            supportsFunctionCalling = true,
            costPer1MInputTokens = 0.11,
            costPer1MOutputTokens = 0.34,
            speedTokensPerSecond = 800,
            shortDescription = "10M context on Groq — entire codebases in context",
            isLatest = true
        ),

        // ━━━━ DEEPSEEK R2 (via Groq) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        AIModel(
            id = "deepseek-r2-groq",
            displayName = "DeepSeek R2 (Groq)",
            provider = ModelProvider.GROQ,
            tier = ModelTier.ORCHESTRATOR,
            contextWindow = 200_000,
            maxOutputTokens = 32_768,
            supportsThinking = true,
            supportsFunctionCalling = true,
            costPer1MInputTokens = 0.3,
            costPer1MOutputTokens = 0.8,
            speedTokensPerSecond = 600,
            shortDescription = "DeepSeek R2 reasoning at Groq's LPU speed"
        ),

        // ━━━━ QWEN 2.5-CODER 32B ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        AIModel(
            id = "qwen-2.5-coder-32b-instruct",
            displayName = "Qwen 2.5 Coder 32B (Groq)",
            provider = ModelProvider.GROQ,
            tier = ModelTier.EXECUTOR,
            contextWindow = 128_000,
            maxOutputTokens = 8_192,
            supportsFunctionCalling = true,
            costPer1MInputTokens = 0.1,
            costPer1MOutputTokens = 0.3,
            speedTokensPerSecond = 700,
            shortDescription = "Best open-source code model at extreme speed"
        ),

        // ━━━━ LLAMA 3.3 70B ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        AIModel(
            id = "llama-3.3-70b-versatile",
            displayName = "Llama 3.3 70B (Groq)",
            provider = ModelProvider.GROQ,
            tier = ModelTier.FAST,
            contextWindow = 128_000,
            maxOutputTokens = 32_768,
            supportsFunctionCalling = true,
            costPer1MInputTokens = 0.05,
            costPer1MOutputTokens = 0.1,
            speedTokensPerSecond = 900,
            shortDescription = "Blazing fast general-purpose at near-zero cost"
        )
    )

    // ─────────────────────────────────────────────────────────────────
    //  CEREBRAS — Wafer-scale chip, world-record inference speed
    // ─────────────────────────────────────────────────────────────────

    private val cerebrasModels = listOf(

        // ━━━━ LLAMA 4 MAVERICK (Cerebras) ━━━━━━━━━━━━━━━━━━━━━━━━━
        // 2200+ tok/s sustained — per Cerebras public benchmarks Feb 2026
        // (https://cerebras.ai/press/cerebras-inference-at-2200-tokens-per-second)
        AIModel(
            id = "cerebras/llama-4-maverick-17b",
            displayName = "Llama 4 Maverick (Cerebras)",
            provider = ModelProvider.CEREBRAS,
            tier = ModelTier.FAST,
            contextWindow = 128_000,
            maxOutputTokens = 8_192,
            supportsVision = true,
            supportsFunctionCalling = true,
            costPer1MInputTokens = 0.6,
            costPer1MOutputTokens = 0.6,
            speedTokensPerSecond = 2200,  // Sustained throughput on Cerebras CS-3 wafer
            shortDescription = "2200 tok/s — fastest inference on the planet",
            isLatest = true
        ),

        // ━━━━ QWEN 2.5-CODER 32B (Cerebras) ━━━━━━━━━━━━━━━━━━━━━━
        // GPT-5.3-Codex-Spark equivalent: fastest code model
        AIModel(
            id = "cerebras/qwen-2.5-coder-32b",
            displayName = "Qwen 2.5 Coder 32B (Cerebras)",
            provider = ModelProvider.CEREBRAS,
            tier = ModelTier.FAST,
            contextWindow = 32_768,
            maxOutputTokens = 8_192,
            supportsFunctionCalling = true,
            costPer1MInputTokens = 0.4,
            costPer1MOutputTokens = 0.4,
            speedTokensPerSecond = 1800,  // Measured output speed on Cerebras CS-3 hardware
            shortDescription = "1800 tok/s code model — live pair-programming speed",
            isLatest = true
        ),

        // ━━━━ LLAMA 3.3 70B (Cerebras) ━━━━━━━━━━━━━━━━━━━━━━━━━━━
        AIModel(
            id = "cerebras/llama-3.3-70b",
            displayName = "Llama 3.3 70B (Cerebras)",
            provider = ModelProvider.CEREBRAS,
            tier = ModelTier.FAST,
            contextWindow = 128_000,
            maxOutputTokens = 8_192,
            supportsFunctionCalling = true,
            costPer1MInputTokens = 0.3,
            costPer1MOutputTokens = 0.3,
            speedTokensPerSecond = 1500,  // Reported by Cerebras for 70B class models on CS-3
            shortDescription = "1500 tok/s — sub-second response for short tasks"
        )
    )

    // ─────────────────────────────────────────────────────────────────
    //  TOGETHER AI — Open-source models on fast GPU clusters
    // ─────────────────────────────────────────────────────────────────

    private val togetherModels = listOf(
        AIModel(
            id = "together/deepseek-r2",
            displayName = "DeepSeek R2 (Together)",
            provider = ModelProvider.TOGETHER,
            tier = ModelTier.ORCHESTRATOR,
            contextWindow = 200_000,
            maxOutputTokens = 32_768,
            supportsThinking = true,
            supportsFunctionCalling = true,
            costPer1MInputTokens = 0.18,
            costPer1MOutputTokens = 0.36,
            speedTokensPerSecond = 120,
            shortDescription = "Full DeepSeek R2 at competitive pricing"
        ),
        AIModel(
            id = "together/llama-4-maverick-instruct",
            displayName = "Llama 4 Maverick (Together)",
            provider = ModelProvider.TOGETHER,
            tier = ModelTier.EXECUTOR,
            contextWindow = 524_288,
            maxOutputTokens = 8_192,
            supportsVision = true,
            supportsFunctionCalling = true,
            costPer1MInputTokens = 0.18,
            costPer1MOutputTokens = 0.54,
            speedTokensPerSecond = 200,
            shortDescription = "Llama 4 MoE with 524K context"
        ),
        AIModel(
            id = "together/qwen-2.5-coder-32b-instruct",
            displayName = "Qwen 2.5 Coder 32B (Together)",
            provider = ModelProvider.TOGETHER,
            tier = ModelTier.EXECUTOR,
            contextWindow = 32_768,
            maxOutputTokens = 8_192,
            supportsFunctionCalling = true,
            costPer1MInputTokens = 0.12,
            costPer1MOutputTokens = 0.12,
            speedTokensPerSecond = 150,
            shortDescription = "Best open-source code model at affordable rates"
        )
    )

    // ─────────────────────────────────────────────────────────────────
    //  COHERE — Enterprise RAG and structured output specialist
    // ─────────────────────────────────────────────────────────────────

    private val cohereModels = listOf(
        AIModel(
            id = "command-a-03-2025",
            displayName = "Command A",
            provider = ModelProvider.COHERE,
            tier = ModelTier.ORCHESTRATOR,
            contextWindow = 256_000,
            maxOutputTokens = 8_192,
            supportsVision = false,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            costPer1MInputTokens = 2.5,
            costPer1MOutputTokens = 10.0,
            shortDescription = "Enterprise RAG champion, 256K context, tool calling",
            isLatest = true
        ),
        AIModel(
            id = "command-r-plus-08-2024",
            displayName = "Command R+ 08-2024",
            provider = ModelProvider.COHERE,
            tier = ModelTier.EXECUTOR,
            contextWindow = 128_000,
            maxOutputTokens = 4_096,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            costPer1MInputTokens = 2.5,
            costPer1MOutputTokens = 10.0,
            shortDescription = "Retrieval-augmented generation with citations"
        )
    )

    // ─────────────────────────────────────────────────────────────────
    //  FIREWORKS AI — Fast, cheap open-source hosting
    // ─────────────────────────────────────────────────────────────────

    private val fireworksModels = listOf(
        AIModel(
            id = "accounts/fireworks/models/llama-4-maverick-instruct",
            displayName = "Llama 4 Maverick (Fireworks)",
            provider = ModelProvider.FIREWORKS,
            tier = ModelTier.EXECUTOR,
            contextWindow = 524_288,
            maxOutputTokens = 16_384,
            supportsVision = true,
            supportsFunctionCalling = true,
            costPer1MInputTokens = 0.22,
            costPer1MOutputTokens = 0.88,
            speedTokensPerSecond = 180,
            shortDescription = "Llama 4 MoE at high throughput on Fireworks"
        ),
        AIModel(
            id = "accounts/fireworks/models/deepseek-r2",
            displayName = "DeepSeek R2 (Fireworks)",
            provider = ModelProvider.FIREWORKS,
            tier = ModelTier.ORCHESTRATOR,
            contextWindow = 200_000,
            maxOutputTokens = 32_768,
            supportsThinking = true,
            supportsFunctionCalling = true,
            costPer1MInputTokens = 0.14,
            costPer1MOutputTokens = 0.28,
            speedTokensPerSecond = 100,
            shortDescription = "DeepSeek R2 at lowest possible cost"
        )
    )

    // ─────────────────────────────────────────────────────────────────
    //  NVIDIA NIM — Enterprise-grade model inference with NIM microservices
    // ─────────────────────────────────────────────────────────────────

    private val nvidiaModels = listOf(
        AIModel(
            id = "nvidia/llama-3.1-nemotron-ultra-253b-v1",
            displayName = "Llama 3.1 Nemotron Ultra 253B",
            provider = ModelProvider.NVIDIA,
            tier = ModelTier.ORCHESTRATOR,
            contextWindow = 128_000,
            maxOutputTokens = 32_768,
            supportsThinking = true,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            costPer1MInputTokens = 3.0,
            costPer1MOutputTokens = 8.0,
            shortDescription = "NVIDIA's frontier model, MMLU 87%, reasoning-tuned",
            isLatest = true
        ),
        AIModel(
            id = "nvidia/llama-3.3-nemotron-super-49b-v1",
            displayName = "Llama 3.3 Nemotron Super 49B",
            provider = ModelProvider.NVIDIA,
            tier = ModelTier.EXECUTOR,
            contextWindow = 128_000,
            maxOutputTokens = 16_384,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            costPer1MInputTokens = 0.42,
            costPer1MOutputTokens = 0.42,
            speedTokensPerSecond = 200,
            shortDescription = "Best open-source NVIDIA model for agentic coding",
            isLatest = true
        )
    )

    // ─────────────────────────────────────────────────────────────────
    //  GITHUB COPILOT — BYOK via api.githubcopilot.com (OpenAI-compatible)
    //
    //  Endpoint: https://api.githubcopilot.com/chat/completions
    //  Auth: GitHub PAT with `copilot` scope OR a Copilot API key.
    //  The API is OpenAI-compatible: set Authorization: Bearer <token>.
    // ─────────────────────────────────────────────────────────────────

    private val githubCopilotModels = listOf(

        // ━━━━ GPT-5.3-CODEX via Copilot ━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // OpenAI's flagship code model routed through GitHub Copilot Workspace
        AIModel(
            id = "copilot/gpt-5.3-codex",
            displayName = "GPT-5.3 Codex (Copilot)",
            provider = ModelProvider.GITHUB_COPILOT,
            tier = ModelTier.ORCHESTRATOR,
            contextWindow = 256_000,
            maxOutputTokens = 65_536,
            supportsVision = true,
            supportsThinking = true,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            shortDescription = "OpenAI's code flagship via GitHub Copilot Workspace",
            isLatest = true
        ),

        // ━━━━ CLAUDE SONNET 4.6 via Copilot ━━━━━━━━━━━━━━━━━━━━━━
        // Anthropic's latest executor tier available through Copilot BYOK
        AIModel(
            id = "copilot/claude-sonnet-4-6",
            displayName = "Claude Sonnet 4.6 (Copilot)",
            provider = ModelProvider.GITHUB_COPILOT,
            tier = ModelTier.EXECUTOR,
            contextWindow = 200_000,
            maxOutputTokens = 64_000,
            supportsVision = true,
            supportsThinking = true,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            shortDescription = "Anthropic Sonnet 4.6 accessed via GitHub Copilot token"
        ),

        // ━━━━ GEMINI 2.5 PRO via Copilot ━━━━━━━━━━━━━━━━━━━━━━━━━
        AIModel(
            id = "copilot/gemini-2.5-pro",
            displayName = "Gemini 2.5 Pro (Copilot)",
            provider = ModelProvider.GITHUB_COPILOT,
            tier = ModelTier.ORCHESTRATOR,
            contextWindow = 1_000_000,
            maxOutputTokens = 65_536,
            supportsVision = true,
            supportsVideo = true,
            supportsThinking = true,
            supportsFunctionCalling = true,
            shortDescription = "1M-context Gemini Pro via GitHub Copilot Workspace"
        ),

        // ━━━━ O3-MINI via Copilot ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        AIModel(
            id = "copilot/o3-mini",
            displayName = "o3-mini (Copilot)",
            provider = ModelProvider.GITHUB_COPILOT,
            tier = ModelTier.EXECUTOR,
            contextWindow = 200_000,
            maxOutputTokens = 100_000,
            supportsThinking = true,
            supportsFunctionCalling = true,
            shortDescription = "OpenAI o3-mini reasoning accessed via GitHub Copilot"
        ),

        // ━━━━ GPT-4O via Copilot ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // Stable, widely-supported model for Copilot BYOK integrations
        AIModel(
            id = "copilot/gpt-4o",
            displayName = "GPT-4o (Copilot)",
            provider = ModelProvider.GITHUB_COPILOT,
            tier = ModelTier.EXECUTOR,
            contextWindow = 128_000,
            maxOutputTokens = 16_384,
            supportsVision = true,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            shortDescription = "Reliable GPT-4o via GitHub Copilot — ideal for IDE workflows"
        )
    )

    // ─────────────────────────────────────────────────────────────────
    //  OPENROUTER — Unified API gateway for all providers
    // ─────────────────────────────────────────────────────────────────

    private val openRouterModels = listOf(
        AIModel(
            id = "anthropic/claude-opus-4-6",
            displayName = "Claude Opus 4.6 (OpenRouter)",
            provider = ModelProvider.OPEN_ROUTER,
            tier = ModelTier.ORCHESTRATOR,
            contextWindow = 1_000_000,
            maxOutputTokens = 128_000,
            supportsVision = true,
            supportsThinking = true,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            costPer1MInputTokens = 16.0,
            costPer1MOutputTokens = 80.0,
            shortDescription = "Latest Claude Opus via OpenRouter"
        ),
        AIModel(
            id = "openai/gpt-5.3-codex",
            displayName = "GPT-5.3 Codex (OpenRouter)",
            provider = ModelProvider.OPEN_ROUTER,
            tier = ModelTier.ORCHESTRATOR,
            contextWindow = 256_000,
            maxOutputTokens = 65_536,
            supportsVision = true,
            supportsThinking = true,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            costPer1MInputTokens = 21.0,
            costPer1MOutputTokens = 84.0,
            shortDescription = "OpenAI's code flagship via OpenRouter"
        ),
        AIModel(
            id = "google/gemini-3.1-pro",
            displayName = "Gemini 3.1 Pro (OpenRouter)",
            provider = ModelProvider.OPEN_ROUTER,
            tier = ModelTier.ORCHESTRATOR,
            contextWindow = 2_000_000,
            maxOutputTokens = 65_536,
            supportsVision = true,
            supportsVideo = true,
            supportsThinking = true,
            supportsFunctionCalling = true,
            costPer1MInputTokens = 7.5,
            costPer1MOutputTokens = 22.0,
            shortDescription = "2M context Gemini 3.1 via OpenRouter"
        ),
        AIModel(
            id = "deepseek/deepseek-r2",
            displayName = "DeepSeek R2 (OpenRouter)",
            provider = ModelProvider.OPEN_ROUTER,
            tier = ModelTier.ORCHESTRATOR,
            contextWindow = 200_000,
            maxOutputTokens = 65_536,
            supportsThinking = true,
            supportsFunctionCalling = true,
            supportsStructuredOutput = true,
            costPer1MInputTokens = 0.15,
            costPer1MOutputTokens = 0.3,
            shortDescription = "Best value frontier model via OpenRouter"
        ),
        AIModel(
            id = "x-ai/grok-3",
            displayName = "Grok-3 (OpenRouter)",
            provider = ModelProvider.OPEN_ROUTER,
            tier = ModelTier.ORCHESTRATOR,
            contextWindow = 131_072,
            maxOutputTokens = 16_384,
            supportsVision = true,
            supportsThinking = true,
            supportsFunctionCalling = true,
            costPer1MInputTokens = 3.5,
            costPer1MOutputTokens = 16.0,
            shortDescription = "Grok-3 via OpenRouter with real-time X data access"
        ),
        AIModel(
            id = "mistralai/codestral-2501",
            displayName = "Codestral 2501 (OpenRouter)",
            provider = ModelProvider.OPEN_ROUTER,
            tier = ModelTier.EXECUTOR,
            contextWindow = 256_000,
            maxOutputTokens = 16_384,
            supportsFunctionCalling = true,
            costPer1MInputTokens = 0.35,
            costPer1MOutputTokens = 1.05,
            shortDescription = "256K FIM code model via OpenRouter"
        ),
        AIModel(
            id = "meta-llama/llama-4-maverick-instruct",
            displayName = "Llama 4 Maverick (OpenRouter)",
            provider = ModelProvider.OPEN_ROUTER,
            tier = ModelTier.EXECUTOR,
            contextWindow = 524_288,
            maxOutputTokens = 8_192,
            supportsVision = true,
            supportsFunctionCalling = true,
            costPer1MInputTokens = 0.2,
            costPer1MOutputTokens = 0.6,
            shortDescription = "Llama 4 MoE at lowest rates via OpenRouter"
        )
    )

    // ─────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────

    /** All models flattened into a single ordered list, newest/most capable first. */
    val allModels: List<AIModel> = buildList {
        addAll(anthropicModels)
        addAll(openAIModels)
        addAll(geminiModels)
        addAll(xaiModels)
        addAll(deepSeekModels)
        addAll(mistralModels)
        addAll(groqModels)
        addAll(cerebrasModels)
        addAll(togetherModels)
        addAll(cohereModels)
        addAll(fireworksModels)
        addAll(nvidiaModels)
        addAll(githubCopilotModels)
        addAll(openRouterModels)
    }

    /** Models grouped by their provider for UI dropdown grouping. */
    val modelsByProvider: Map<ModelProvider, List<AIModel>> =
        allModels.groupBy { it.provider }

    /** Models grouped by tier for intelligent routing. */
    val modelsByTier: Map<ModelTier, List<AIModel>> =
        allModels.groupBy { it.tier }

    /** The latest (flagship) models from each provider — ideal for auto-selection. */
    val latestByProvider: Map<ModelProvider, AIModel> = buildMap {
        modelsByProvider.forEach { (provider, models) ->
            val latest = models.firstOrNull { it.isLatest } ?: models.first()
            put(provider, latest)
        }
    }

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

    /**
     * Retrieves all models of a specific [tier], sorted by provider order.
     */
    fun getModelsForTier(tier: ModelTier): List<AIModel> =
        modelsByTier[tier] ?: emptyList()

    /**
     * Retrieves the best model for a given [tier] from a preferred [provider].
     * Falls back to the globally best model in that tier if the provider has none.
     */
    fun getBestModelForTier(tier: ModelTier, preferredProvider: ModelProvider? = null): AIModel {
        val tieredModels = getModelsForTier(tier)
        if (preferredProvider != null) {
            val providerModel = tieredModels.firstOrNull { it.provider == preferredProvider }
            if (providerModel != null) return providerModel
        }
        return tieredModels.first()
    }

    /** Returns the recommended default model for a given [role]. */
    fun getDefaultModelForRole(role: ModelRole): AIModel = when (role) {
        ModelRole.CHAT -> getModelById("claude-sonnet-4-6")
        ModelRole.AGENT -> getModelById("claude-sonnet-4-6")
        ModelRole.SWARM_ORCHESTRATOR -> getModelById("claude-opus-4-6")
        ModelRole.SWARM_WORKER -> getModelById("deepseek-r2")
    }
}

