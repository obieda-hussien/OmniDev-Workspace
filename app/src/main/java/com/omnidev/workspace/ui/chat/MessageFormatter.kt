package com.omnidev.workspace.ui.chat

/**
 * A single parsed tool execution block, extracted from `<tool_code>` and
 * `<observation>` tags in an LLM response.
 */
data class ToolBlock(
    val toolCode: String,
    val observation: String? = null
)

/**
 * Structured representation of a raw LLM response after parsing out internal
 * XML-like tags (`<thinking>`, `<tool_code>`, `<observation>`).
 *
 * @property cleanText The user-facing conversational text, with all internal tags removed.
 * @property thoughtBlocks Content extracted from `<thinking>` tags (collapsed by default).
 * @property toolBlocks Paired `<tool_code>` + `<observation>` blocks for the tool-execution UI.
 */
data class ParsedMessage(
    val cleanText: String,
    val thoughtBlocks: List<String> = emptyList(),
    val toolBlocks: List<ToolBlock> = emptyList()
)

/**
 * Parses raw LLM responses to extract and separate internal tags from the
 * user-visible conversational text.
 *
 * Extracted tag types:
 * - `<thinking>…</thinking>` → [ParsedMessage.thoughtBlocks] (🧠 Thought Process panel)
 * - `<tool_code>…</tool_code>` paired with `<observation>…</observation>` →
 *   [ParsedMessage.toolBlocks] (🛠️ Tool Execution panel)
 *
 * All matched blocks are stripped from [ParsedMessage.cleanText].
 */
object MessageFormatter {

    /**
     * Matches both `<thinking>…</thinking>` (Anthropic / extended-thinking) and
     * `<think>…</think>` (DeepSeek-R1 / o1) in a single pass.
     * Using alternation avoids scanning large responses twice.
     */
    private val THINKING_RE = Regex(
        """<(?:thinking|think)>(.*?)</(?:thinking|think)>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val TOOL_CODE_RE = Regex(
        """<tool_code>(.*?)</tool_code>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val OBSERVATION_RE = Regex(
        """<observation>(.*?)</observation>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    /**
     * Parses [raw] into a [ParsedMessage].
     *
     * If the input contains no recognised tags, the result has an empty thought/tool list
     * and [ParsedMessage.cleanText] equals the trimmed input — zero overhead for plain replies.
     *
     * Both `<thinking>…</thinking>` (Anthropic / extended-thinking style) and
     * `<think>…</think>` (DeepSeek-R1 / o1 style) are treated as reasoning blocks and
     * collapsed into the same "🧠 Thought Process" expandable card so the raw tags are
     * never shown in the chat bubble.
     */
    fun parse(raw: String): ParsedMessage {
        val thoughtBlocks = THINKING_RE.findAll(raw)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()

        val toolCodes = TOOL_CODE_RE.findAll(raw)
            .map { it.groupValues[1].trim() }
            .toList()

        val observations = OBSERVATION_RE.findAll(raw)
            .map { it.groupValues[1].trim() }
            .toList()

        val toolBlocks = toolCodes.mapIndexed { i, code ->
            ToolBlock(
                toolCode = code,
                observation = observations.getOrNull(i)
            )
        }

        val cleanText = raw
            .replace(THINKING_RE, "")
            .replace(TOOL_CODE_RE, "")
            .replace(OBSERVATION_RE, "")
            .trim()
            .replace(Regex("\n{3,}"), "\n\n") // collapse 3+ blank lines to 2

        return ParsedMessage(
            cleanText = cleanText,
            thoughtBlocks = thoughtBlocks,
            toolBlocks = toolBlocks
        )
    }
}
