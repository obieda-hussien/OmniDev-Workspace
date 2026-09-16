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
        return buildString(clean.length + 1_100) {
            append(clean)
            appendLine()
            appendLine()
            appendLine("## OBSERVABLE AGENT POLICY")
            appendLine("Keep private chain-of-thought internal. Do not print hidden reasoning or <thinking> blocks.")
            appendLine("Expose only concise operational telemetry: objective, selected capability/domain, tool action, verification result, and blockers.")
            appendLine("Prefer deterministic tool evidence over narrated reasoning.")
            appendLine()
            appendLine("Execution domains are strict:")
            appendLine("- Developer/Linux work (packages, Python, Node, Git, normal shell/files) -> agent_runtime / Termux RunCommandService.")
            appendLine("- Android privileged work (settings, dumpsys, getprop/setprop, pm/am/cmd/wm/svc/input) -> privileged_tool / Shizuku UserService.")
            appendLine("- Explicit ADB-equivalent terminal shell -> privileged_tool rish_* actions only; never wrap rish inside agent_runtime.")
            appendLine("- Root is not a generic fallback. Use it only when a verified root backend exists and the requested capability actually requires root.")
            appendLine()
            appendLine("Failure discipline:")
            appendLine("- Treat ERROR observations and semantic classifications as authoritative even when a transport or later echo exited 0.")
            appendLine("- WRONG_EXECUTION_DOMAIN means reroute to the correct tool; do not retry the same command through Termux.")
            appendLine("- Persistent failures (RISH_NATIVE_LOADER_FAILURE, RISH_DEX_MISSING, RISH_LAYOUT_BROKEN, ROOT_UNAVAILABLE, ANDROID_PERMISSION_DENIED) are circuit-breaker events: do not repeat the same backend strategy.")
            appendLine("- SHIZUKU_CONNECTION_TIMEOUT may be retried once after a health probe; then pivot/report the backend as degraded.")
            appendLine("- A mutation is complete only when the tool reports verification/postcondition evidence when such evidence is available.")
            append("- Never append a fake success echo merely to force exit code 0.")
        }
    }

    /** Never replay provider/model thinking blocks into later ReAct requests. */
    fun sanitizeMessages(messages: List<ChatMessage>): List<ChatMessage> =
        messages.map { message ->
            if (message.thinkingContent == null) message
            else message.copy(thinkingContent = null)
        }
}
