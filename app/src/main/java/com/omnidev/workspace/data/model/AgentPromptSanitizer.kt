package com.omnidev.workspace.data.model

/**
 * Central request-boundary hygiene for agent prompts.
 *
 * Older Omni prompts explicitly asked models to print chain-of-thought and the
 * pipeline replayed thinkingContent on every ReAct turn. Besides exposing private
 * reasoning, that caused enormous repeated prompt-token growth. This sanitizer
 * enforces a compact observable-agent contract at the model boundary regardless
 * of which caller/provider created the CompletionRequest.
 */
object AgentPromptSanitizer {

    private val chainOfThoughtSection = Regex(
        pattern = "(?is)##\\s+THINKING PROCESS\\s*\\(Chain of Thought\\).*?(?=##\\s+CONTINUITY|##\\s+[A-Z][A-Z &/-]{3,}|\\z)"
    )

    private val deepThinkingDisclosure = Regex(
        pattern = "(?is)DEEP THINKING MODE ACTIVE:.*?(?=##\\s+[A-Z][A-Z &/-]{3,}|\\z)"
    )

    private val explicitDisclosureLines = listOf(
        Regex("(?im)^.*output your internal reasoning.*$"),
        Regex("(?im)^.*emit your internal reasoning.*$"),
        Regex("(?im)^.*<thinking>.*</thinking>.*$"),
        Regex("(?im)^.*Before each action, emit.*thinking.*$"),
        Regex("(?im)^.*Observation:.*Reasoning:.*Plan:.*Action:.*$")
    )

    fun sanitizeSystemPrompt(prompt: String?): String? {
        if (prompt.isNullOrBlank()) return prompt
        var clean = prompt
            .replace(chainOfThoughtSection, "")
            .replace(deepThinkingDisclosure, "")
        explicitDisclosureLines.forEach { pattern -> clean = clean.replace(pattern, "") }
        clean = clean.trim()
        return buildString(clean.length + 300) {
            append(clean)
            appendLine()
            appendLine()
            appendLine("## OBSERVABLE REASONING POLICY")
            appendLine("Keep private chain-of-thought internal. Do not print hidden reasoning or <thinking> blocks.")
            appendLine("Expose only concise operational telemetry: objective, selected capability/domain, tool action, verification result, and blockers.")
            append("Prefer deterministic tool evidence over narrated reasoning.")
        }
    }

    /** Never replay provider/model thinking blocks into later ReAct requests. */
    fun sanitizeMessages(messages: List<ChatMessage>): List<ChatMessage> =
        messages.map { message ->
            if (message.thinkingContent == null) message
            else message.copy(thinkingContent = null)
        }
}
