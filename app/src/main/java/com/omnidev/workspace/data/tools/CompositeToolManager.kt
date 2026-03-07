package com.omnidev.workspace.data.tools

import android.content.Context
import com.omnidev.workspace.data.accessibility.SemanticUITool
import com.omnidev.workspace.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Delegates tool execution to [FileToolManager], [MemoryManager], and the suite of
 * system-level assistant tools, presenting their combined tool set as a single
 * [ToolManager] to [AgentPipeline].
 *
 * Tool routing order:
 * 1. Memory tools (remember_fact, search_knowledge)
 * 2. System assistant tools (communicate, planner, hardware, location, device info, app manager)
 * 3. Logcat analyzer and Git manager
 * 4. Environment / advanced terminal tools
 * 5. Notification and task scheduler tools
 * 6. Visual inspector, Telegram publisher, GitHub manager
 * 7. File tools (read, search, patch, create, delete, terminal, web search) — default fallback
 */
class CompositeToolManager(
    private val fileToolManager: FileToolManager,
    val memoryManager: MemoryManager,
    private val context: Context? = null,
    private val environmentSetupManager: EnvironmentSetupManager? = null,
    private val settingsRepository: SettingsRepository? = null,
    private val godEyeProfilerTool: GodEyeProfilerTool? = null,
    private val discordPublisherTool: DiscordPublisherTool? = null,
    private val notionPublisherTool: NotionPublisherTool? = null,
    val vectorMemoryManager: VectorMemoryManager? = null
) : ToolManager {

    override fun getToolDefinitions(): List<ToolDefinition> = buildList {
        addAll(fileToolManager.getToolDefinitions())
        addAll(memoryManager.getToolDefinitions())
        addAll(CommunicationTool.getToolDefinitions())
        addAll(PlannerTool.getToolDefinitions())
        addAll(HardwareToggleTool.getToolDefinitions())
        addAll(LocationTool.getToolDefinitions())
        addAll(DeviceInfoTool.getToolDefinitions())
        addAll(AppManagerTool.getToolDefinitions())
        addAll(LogcatAnalyzerTool.getToolDefinitions())
        addAll(GitManagerTool.getToolDefinitions())
        if (environmentSetupManager != null) {
            addAll(environmentSetupManager.getToolDefinitions())
        }
        addAll(NotificationCaptureTool.getToolDefinitions())
        addAll(TaskSchedulerTool.getToolDefinitions())
        addAll(VisualInspectorTool.getToolDefinitions())
        addAll(TelegramPublisherTool.getToolDefinitions())
        addAll(GitHubManagerTool.getToolDefinitions())
        if (discordPublisherTool != null) {
            addAll(DiscordPublisherTool.getToolDefinitions())
        }
        if (notionPublisherTool != null) {
            addAll(NotionPublisherTool.getToolDefinitions())
        }
        if (godEyeProfilerTool != null) {
            addAll(godEyeProfilerTool.getToolDefs())
        }
        if (context != null) {
            addAll(SystemContactsTool.getToolDefinitions())
            addAll(UIAutomationTool.getToolDefinitions())
            addAll(SemanticUITool.getToolDefinitions())
            addAll(CallLogTool.getToolDefinitions())
            addAll(SmsReaderTool.getToolDefinitions())
            addAll(ScreenshotTool.getToolDefinitions())
            addAll(SystemSettingsTool.getToolDefinitions())
            addAll(PackageInstallerTool.getToolDefinitions())
            addAll(AdvancedRootShellTool.getToolDefinitions())
            addAll(AppManifestAnalyzerTool.getToolDefinitions())
        }
        if (vectorMemoryManager != null) {
            addAll(vectorMemoryManager.getToolDefinitions())
        }
    }

    override suspend fun executeTool(
        name: String,
        arguments: Map<String, String>,
        scopePath: String
    ): ToolExecutionResult {
        return when (name) {
            // ── Memory tools ──
            "remember_fact", "search_knowledge", "update_memory", "delete_memory" ->
                memoryManager.executeTool(name, arguments)

            // ── Communication tool ──
            "communicate_tool" -> {
                val ctx = context
                    ?: return ToolExecutionResult("Communication tool requires Android context.", isError = true)
                CommunicationTool.execute(
                    context = ctx,
                    method = arguments["method"] ?: return missingArg("method"),
                    target = arguments["target"] ?: return missingArg("target"),
                    message = arguments["message"]
                )
            }

            // ── Planner tool ──
            "planner_tool" -> {
                val ctx = context
                    ?: return ToolExecutionResult("Planner tool requires Android context.", isError = true)
                PlannerTool.execute(
                    context = ctx,
                    action = arguments["action"] ?: return missingArg("action"),
                    title = arguments["title"] ?: return missingArg("title"),
                    timeMillis = arguments["timeMillis"]?.toLongOrNull()
                        ?: return ToolExecutionResult("timeMillis must be a valid long.", isError = true)
                )
            }

            // ── Hardware toggle tool ──
            "hardware_toggle_tool" ->
                HardwareToggleTool.execute(
                    setting = arguments["setting"] ?: return missingArg("setting"),
                    state = arguments["state"] ?: return missingArg("state")
                )

            // ── Location tool ──
            "get_current_location" -> {
                val ctx = context
                    ?: return ToolExecutionResult("Location tool requires Android context.", isError = true)
                LocationTool.execute(ctx)
            }

            // ── Device info tool ──
            "get_device_info" -> {
                val ctx = context
                    ?: return ToolExecutionResult("Device info tool requires Android context.", isError = true)
                DeviceInfoTool.execute(ctx, arguments["infoType"] ?: "all")
            }

            // ── App manager tool ──
            "app_manager_tool" -> {
                val ctx = context
                    ?: return ToolExecutionResult("App manager tool requires Android context.", isError = true)
                AppManagerTool.execute(
                    context = ctx,
                    action = arguments["action"] ?: return missingArg("action"),
                    packageName = arguments["packageName"]
                )
            }

            // ── Logcat analyzer tool ──
            "analyze_logcat" ->
                LogcatAnalyzerTool.executeTool(name, arguments)

            // ── Git manager tool ──
            "git_manager" ->
                GitManagerTool.executeTool(name, arguments, scopePath)

            // ── Environment / advanced terminal tools ──
            "advanced_terminal", "setup_build_environment" -> {
                val env = environmentSetupManager
                    ?: return ToolExecutionResult("Build environment manager not configured.", isError = true)
                env.executeTool(name, arguments, scopePath)
            }

            // ── Notification capture tool ──
            "read_notifications" ->
                NotificationCaptureTool.executeTool(name, arguments)

            // ── Task scheduler tool ──
            "task_scheduler" ->
                TaskSchedulerTool.executeTool(name, arguments)

            // ── Visual inspector tool ──
            "visual_inspector" ->
                VisualInspectorTool.execute()

            // ── Telegram publisher tool ──
            "telegram_publish" -> {
                val botToken = settingsRepository?.observeTelegramBotToken()?.first()
                val chatId = settingsRepository?.observeTelegramChatId()?.first()
                TelegramPublisherTool.execute(
                    botToken = botToken,
                    chatId = chatId,
                    message = arguments["message"] ?: return missingArg("message"),
                    parseMode = arguments["parseMode"] ?: "Markdown"
                )
            }

            // ── GitHub manager tool ──
            "github_manager" -> {
                val pat = settingsRepository?.observeGitHubPat()?.first()
                GitHubManagerTool.execute(
                    pat = pat,
                    action = arguments["action"] ?: return missingArg("action"),
                    repo = arguments["repo"] ?: return missingArg("repo"),
                    title = arguments["title"] ?: return missingArg("title"),
                    body = arguments["body"] ?: return missingArg("body"),
                    head = arguments["head"],
                    base = arguments["base"]
                )
            }

            // ── GodEye profiler tools ──
            "read_network_log", "read_db_schema", "analyze_anr_trace", "memory_snapshot" -> {
                val profiler = godEyeProfilerTool
                    ?: return ToolExecutionResult("GodEye profiler tool not configured.", isError = true)
                ToolExecutionResult(profiler.execute(name, arguments))
            }

            // ── Discord publisher tool ──
            "publish_to_discord" -> {
                val discord = discordPublisherTool
                    ?: return ToolExecutionResult("Discord publisher tool not configured.", isError = true)
                discord.execute(arguments)
            }

            // ── Notion publisher tool ──
            "create_notion_page" -> {
                val notion = notionPublisherTool
                    ?: return ToolExecutionResult("Notion publisher tool not configured.", isError = true)
                notion.execute(arguments)
            }

            // ── System contacts tool ──
            "search_contacts" -> {
                val ctx = context
                    ?: return ToolExecutionResult("Contacts tool requires Android context.", isError = true)
                val searchName = arguments["searchName"] ?: return missingArg("searchName")
                SystemContactsTool.execute(ctx, searchName)
            }

            // ── UI automation tool (Ghost Finger) ──
            "ui_automation" -> {
                val action = arguments["action"] ?: return missingArg("action")
                UIAutomationTool.execute(action, arguments)
            }

            // ── Semantic UI tool (Accessibility Service) ──
            "semantic_ui" -> {
                val action = arguments["action"] ?: return missingArg("action")
                SemanticUITool.execute(action, arguments)
            }

            // ── Call log tool ──
            "call_log_tool" -> {
                val ctx = context
                    ?: return ToolExecutionResult("Call log tool requires Android context.", isError = true)
                CallLogTool.execute(
                    context = ctx,
                    action = arguments["action"] ?: return missingArg("action"),
                    query = arguments["query"],
                    limit = arguments["limit"]?.toIntOrNull() ?: 50
                )
            }

            // ── SMS reader tool ──
            "sms_reader_tool" -> {
                val ctx = context
                    ?: return ToolExecutionResult("SMS tool requires Android context.", isError = true)
                SmsReaderTool.execute(
                    context = ctx,
                    action = arguments["action"] ?: return missingArg("action"),
                    query = arguments["query"],
                    limit = arguments["limit"]?.toIntOrNull() ?: 30
                )
            }

            // ── Screenshot tool ──
            "screenshot_tool" -> {
                ScreenshotTool.execute(filename = arguments["filename"])
            }

            // ── System settings tool ──
            "system_settings_tool" -> {
                SystemSettingsTool.execute(
                    action = arguments["action"] ?: return missingArg("action"),
                    namespace = arguments["namespace"] ?: return missingArg("namespace"),
                    key = arguments["key"] ?: return missingArg("key"),
                    value = arguments["value"]
                )
            }

            // ── Package installer tool ──
            "package_installer_tool" -> {
                PackageInstallerTool.execute(
                    action = arguments["action"] ?: return missingArg("action"),
                    target = arguments["target"] ?: return missingArg("target"),
                    extraFlags = arguments["extraFlags"]
                )
            }

            // ── Advanced root shell tool ──
            "root_shell_tool" -> {
                AdvancedRootShellTool.execute(
                    command = arguments["command"] ?: return missingArg("command")
                )
            }

            // ── App manifest analyzer tool ──
            "app_manifest_analyzer" -> {
                val ctx = context
                    ?: return ToolExecutionResult("App analyzer tool requires Android context.", isError = true)
                AppManifestAnalyzerTool.execute(
                    context = ctx,
                    targetPackage = arguments["target_package"] ?: return missingArg("target_package"),
                    filter = arguments["filter"]
                )
            }

            // ── Vector memory tools ──
            "vector_store", "vector_search", "vector_similar" -> {
                val vmm = vectorMemoryManager
                    ?: return ToolExecutionResult("Vector memory manager not configured.", isError = true)
                vmm.executeTool(name, arguments)
            }

            // ── File tools (default fallback) ──
            else ->
                fileToolManager.executeTool(name, arguments, scopePath)
        }
    }

    private fun missingArg(name: String) =
        ToolExecutionResult("Missing required argument: $name", isError = true)
}
