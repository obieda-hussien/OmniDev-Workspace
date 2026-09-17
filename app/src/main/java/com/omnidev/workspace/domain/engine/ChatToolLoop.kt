package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.data.tools.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Small native-tool loop: Chat never receives the full agent registry. */
class ChatToolLoop(private val tools: ToolManager?) {
    companion object {
        val WEB_TOOLS = setOf("web_search", "web_search_deep", "web_scraper", "fetch_page", "scrape_multiple")
        const val REQUEST_MODE = "request_execution_mode"
        val MODE_TOOL = ToolDefinition(REQUEST_MODE,
            "Ask the user to enable execution. Never needed for web research. Use AGENT for sequential execution; SWARM only for independent parallel tasks. This only proposes a mode; it does not execute anything.",
            listOf(ToolParameter("mode", "string", "AGENT or SWARM"),
                ToolParameter("reason", "string", "Explain the missing capability and ask permission, in the user's language.")))
    }

    data class Result(val content: String, val request: ExecutionModeRequest? = null)

    suspend fun run(
        base: CompletionRequest,
        disabled: Set<String>,
        originMessageId: String,
        complete: suspend (CompletionRequest) -> CompletionResponse,
        event: suspend (AgentEvent) -> Unit
    ): Result {
        val definitions = tools?.getToolDefinitions().orEmpty().filter { it.name in WEB_TOOLS && it.name !in disabled }
        val allowed = definitions.map { it.name }.toSet()
        val history = base.messages.toMutableList()
        var calls = 0
        var tokens = 0
        val seen = mutableSetOf<Pair<String, Map<String, String>>>()
        for (round in 1..7) {
            val canUseTools = round <= 6 && calls < 8 && tokens < 32_000
            event(AgentEvent.Thinking(round))
            val response = complete(base.copy(messages = history.toList(),
                tools = if (canUseTools) definitions + MODE_TOOL else null,
                systemPrompt = base.systemPrompt.orEmpty() + if (canUseTools) "" else "\nTool budget exhausted. Summarize verified results and any remaining limitations; do not claim unfinished work is complete."))
            tokens += response.tokensUsed?.totalTokens ?: 0
            event(AgentEvent.TokenUsageUpdate(response.tokensUsed?.totalTokens ?: 0, tokens, 32_000))
            if (response.toolCalls.isEmpty()) return Result(response.content)
            if (!canUseTools) return Result(response.content.ifBlank { "وصلت لحد أدوات الشات. تقدر تكمّل البحث برسالة جديدة." })
            history += ChatMessage(MessageRole.ASSISTANT, response.content, toolCalls = response.toolCalls,
                thinkingContent = response.thinkingContent)
            val results = mutableListOf<ToolCallResult>()
            for (call in response.toolCalls) {
                event(AgentEvent.ToolExecution(call.name, call.arguments, round))
                val mode = call.arguments["mode"]?.takeIf { it == "AGENT" || it == "SWARM" }
                val reason = call.arguments["reason"]?.trim()?.take(1200)
                if (call.name == REQUEST_MODE && mode != null && !reason.isNullOrBlank()) {
                    event(AgentEvent.ToolResult(call.name, "Awaiting user approval; no execution started.", false, round))
                    return Result(
                        reason,
                        ExecutionModeRequest(
                            mode = mode,
                            reason = reason,
                            originMessageId = originMessageId,
                            sourceMode = OmniMode.CHAT.name,
                            confidence = 0.9f,
                            trigger = AdaptiveModeRouter.Trigger.CHAT_CAPABILITY_GAP.name
                        )
                    )
                }
                val result = when {
                    calls >= 8 -> ToolExecutionResult("Chat tool budget exhausted.", true)
                    call.name !in allowed -> ToolExecutionResult("Tool unavailable in Chat. Use request_execution_mode only if execution is required.", true)
                    !seen.add(call.name to call.arguments) -> ToolExecutionResult("Identical request already executed. Use its previous result or change the query.", true)
                    else -> {
                        calls++
                        try {
                            withTimeoutOrNull(60_000) { tools!!.executeTool(call.name, call.arguments, null) }
                                ?: ToolExecutionResult("Tool timed out after 60 seconds.", true)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { ToolExecutionResult(error.message ?: "Tool failed.", true) }
                    }
                }
                val output = result.output.take(12_000)
                event(AgentEvent.ToolResult(call.name, output, result.isError, round))
                results += ToolCallResult(call.id, call.name, output.take(4000), result.isError)
            }
            history += ChatMessage(MessageRole.TOOL, results.joinToString("\n") { it.output }, toolResults = results)
        }
        return Result("وصلت لحد أدوات الشات. تقدر تكمّل البحث برسالة جديدة.")
    }
}
