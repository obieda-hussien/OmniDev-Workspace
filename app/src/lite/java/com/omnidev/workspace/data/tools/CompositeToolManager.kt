package com.omnidev.workspace.data.tools

import android.content.Context
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.data.tools.research.PageFetchTool
import com.omnidev.workspace.data.tools.research.MessageSearchTool
import kotlinx.coroutines.flow.first

/** Lite catalog: delegated files, browser, memory and explicitly connected services only. */
class CompositeToolManager(
    private val fileToolManager: FileToolManager,
    val memoryManager: MemoryManager,
    private val context: Context? = null,
    var confirmationGate: com.omnidev.workspace.core.policy.ConfirmationGate? = null,
    private val environmentSetupManager: EnvironmentSetupManager? = null,
    private val settingsRepository: SettingsRepository? = null,
    private val godEyeProfilerTool: GodEyeProfilerTool? = null,
    private val notionPublisherTool: NotionPublisherTool? = null,
    val vectorMemoryManager: VectorMemoryManager? = null,
    val headlessBrowserManager: HeadlessBrowserManager? = null,
    private val apiKeyRepository: com.omnidev.workspace.data.repository.ApiKeyRepository? = null,
    private val chatRepository: com.omnidev.workspace.data.repository.ChatRepository? = null,
    // ── Agent Brain 2.0 + Rollback + Repo Context + Build Doctor (mobile-friendly) ──
    val agentBrainTools: AgentBrainTools? = null,
    val rollbackTools: RollbackTools? = null,
    val repoContextTools: RepoContextTools? = null,
    val buildDoctorTools: BuildDoctorTools? = null,
    // ── Causal Chain Planner — pre-execution conflict detection & simulation ──
    val causalChainPlannerTool: CausalChainPlannerTool? = null,
    // ── Progressive Trust — trust scoring & capability gating ──
    val progressiveTrustTool: ProgressiveTrustTool? = null,
    // ── Script Runner — sandboxed scripting for Norm+ tier ──
    val scriptRunnerTool: ScriptRunnerTool? = null
) : ToolManager {
    var currentSessionId: Long? = null
    var assistantActionGuard: (suspend (String, Map<String, String>) -> ToolExecutionResult?)? = null
    var learnedRoutineConfirmationGate: com.omnidev.workspace.core.policy.ConfirmationGate? = null
    val learnedRoutineTool: com.omnidev.workspace.data.routines.LearnedRoutineTool? = null

    override fun getToolDefinitions(): List<ToolDefinition> = TierToolGate.filter(buildList {
        addAll(fileToolManager.getToolDefinitions())
        addAll(WebSearchTool.getToolDefinitions())
        addAll(PageFetchTool.getToolDefinitions())
        addAll(WebScraperTool.getToolDefinitions())
        addAll(memoryManager.getToolDefinitions())
        vectorMemoryManager?.let { addAll(it.getToolDefinitions()) }
        headlessBrowserManager?.let { addAll(it.getToolDefinitions()) }
        agentBrainTools?.let { addAll(it.getDefinitions()) }
        causalChainPlannerTool?.let { addAll(it.getDefinitions()) }
        progressiveTrustTool?.let { addAll(it.getDefinitions()) }
        addAll(OmniLinkTool.getToolDefinitions())
        addAll(GitHubManagerTool.getToolDefinitions())
        addAll(PlannerTool.getToolDefinitions())
        add(ContextCompressionTool.definition())
        addAll(ScriptRunnerTool().getDefinitions())
        if (context != null) add(MediaGenerationTool.definition())
        addAll(MessageSearchTool.getToolDefinitions())
    })

    override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
        TierToolGate.denyReason(name)?.let { return ToolExecutionResult(it, true) }
        com.omnidev.workspace.domain.engine.ToolCallPreflight(getToolDefinitions()).check(
            com.omnidev.workspace.data.model.ToolCall("lite", name, arguments))?.let { return it }
        assistantActionGuard?.invoke(name, arguments)?.let { return it }
        if (name == "headless_browser" && arguments["action"]?.lowercase() in BrowserToolPolicy.mutations) {
            if (confirmationGate?.request(com.omnidev.workspace.core.policy.ConfirmationKind.ASSISTANT_ACTION,
                com.omnidev.workspace.data.assistant.AssistantActionPolicy.preview(name, arguments), null) != true)
                return ToolExecutionResult("Browser interaction denied. No action was executed.", true, classification = "USER_DENIED")
        }
        agentBrainTools?.execute(name, arguments)?.let { return it }
        causalChainPlannerTool?.execute(name, arguments)?.let { return it }
        progressiveTrustTool?.execute(name, arguments)?.let { return it }
        if (name in memoryManager.getToolDefinitions().map { it.name }) return memoryManager.executeTool(name, arguments)
        if (name.startsWith("vector_")) return vectorMemoryManager?.executeTool(name, arguments) ?: ToolExecutionResult("Memory unavailable", true)
        return when (name) {
            "compress_context" -> ContextCompressionTool.result()
            "eval_expression" -> requireNotNull(ScriptRunnerTool().execute(name, arguments))
            "web_search", "web_search_deep" -> if (name == "web_search") WebSearchTool.execute(arguments["query"].orEmpty(), null, null, null) else WebSearchTool.executeDeep(arguments["query"].orEmpty(), arguments["max_sites"]?.toIntOrNull() ?: 5, null, null, null)
            "fetch_page" -> PageFetchTool.execute(arguments["url"].orEmpty())
            "web_scraper", "scrape_multiple" -> WebScraperTool.executeTool(name, arguments)
            "headless_browser" -> headlessBrowserManager?.execute(arguments["action"].orEmpty(), arguments) ?: ToolExecutionResult("Browser unavailable", true)
            "media_generation" -> context?.let { MediaGenerationTool.execute(it, arguments, currentSessionId) } ?: ToolExecutionResult("Context unavailable", true)
            "omni_link" -> OmniLinkTool.execute(arguments["action"].orEmpty(), arguments, confirmationGate)
            "github_manager" -> GitHubManagerTool.execute(null, arguments["action"].orEmpty(), arguments["repo"].orEmpty(), arguments["title"].orEmpty(), arguments["body"].orEmpty(), arguments["head"], arguments["base"], arguments)
            "planner_tool" -> context?.let { PlannerTool.execute(it, arguments["action"].orEmpty(), arguments["title"].orEmpty(), arguments["timeMillis"]?.toLongOrNull() ?: 0L) } ?: ToolExecutionResult("Context unavailable", true)
            "list_chat_sessions" -> MessageSearchTool.listSessions(arguments["query"], arguments["offset"]?.toIntOrNull(), arguments["limit"]?.toIntOrNull(), chatRepository, currentSessionId)
            "search_messages" -> MessageSearchTool.execute(arguments["query"].orEmpty(), arguments["limit"]?.toIntOrNull(), arguments["sessionId"], chatRepository, currentSessionId)
            "read_chat_session" -> MessageSearchTool.readSession(arguments["sessionId"], arguments["beforeId"], arguments["limit"]?.toIntOrNull(), chatRepository, currentSessionId)
            "read_chat_message" -> MessageSearchTool.readMessage(arguments["sessionId"], arguments["messageId"], arguments["offset"]?.toIntOrNull(), chatRepository, currentSessionId)
            else -> fileToolManager.executeTool(name, arguments, scopePath)
        }
    }
}
