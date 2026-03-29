package com.omnidev.workspace.data.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.omnidev.workspace.data.accessibility.SemanticUITool
import com.omnidev.workspace.data.admin.OmniDeviceAdminReceiver
import com.omnidev.workspace.data.communication.SmsCaptureBuffer
import com.omnidev.workspace.data.input.OmniInputMethodService
import com.omnidev.workspace.data.ipc.ExtensionConnectionManager
import com.omnidev.workspace.data.ipc.OmniCoreAgentTool
import com.omnidev.workspace.data.media.OmniMediaSessionService
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.data.sync.OmniSyncService
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.net.URI
import java.util.Date
import java.util.Locale
import android.content.pm.PackageManager
import android.os.Build

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
 * 6. Visual inspector, Telegram publisher, Telegram bot, GitHub manager
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
    val vectorMemoryManager: VectorMemoryManager? = null,
    val headlessBrowserManager: HeadlessBrowserManager? = null,
    private val apiKeyRepository: com.omnidev.workspace.data.repository.ApiKeyRepository? = null
) : ToolManager {
    companion object {
        /**
         * Extracts the -d URL argument from `am start` command, supporting:
         * -d "https://..."
         * -d 'https://...'
         * -d https://...
         * Note: escaped quotes inside quoted URLs are not supported by this lightweight parser.
         */
        private val AM_START_DATA_URL_REGEX =
            Regex("""\s-d\s+(?:"([^"]+)"|'([^']+)'|(\S+))""", RegexOption.IGNORE_CASE)
        private val AM_START_VIEW_ACTION_REGEX =
            Regex("""\s-a\s+android\.intent\.action\.VIEW\b""", RegexOption.IGNORE_CASE)
        private val AM_START_SET_ALARM_ACTION_REGEX =
            Regex("""\s-a\s+android\.intent\.action\.SET_ALARM\b""", RegexOption.IGNORE_CASE)
        private val AM_START_SET_ALARM_HOUR_REGEX =
            Regex("""--ei\s+android\.intent\.extra\.alarm\.HOUR\s+(\d{1,2})""", RegexOption.IGNORE_CASE)
        private val AM_START_SET_ALARM_MINUTES_REGEX =
            Regex("""--ei\s+android\.intent\.extra\.alarm\.MINUTES\s+(\d{1,2})""", RegexOption.IGNORE_CASE)
        private val AM_START_SET_ALARM_MESSAGE_REGEX =
            Regex("""--es\s+android\.intent\.extra\.alarm\.MESSAGE\s+(?:"([^"]+)"|'([^']+)'|(\S+))""", RegexOption.IGNORE_CASE)
        private val AM_START_SET_ALARM_SKIP_UI_REGEX =
            Regex("""--ez\s+android\.intent\.extra\.alarm\.SKIP_UI\s+(true|false)""", RegexOption.IGNORE_CASE)
        private const val ALARM_HOUR_MIN = 0
        private const val ALARM_HOUR_MAX = 23
        private const val ALARM_MINUTE_MIN = 0
        private const val ALARM_MINUTE_MAX = 59
    }

    // ─── Execution Diagnostics ───────────────────────────────────────────────────
    private val executionDiagnostics = OmniExecutionDiagnostics

    /**
     * Lazily constructed agentic-auth tool. Available only when both
     * [settingsRepository] and [apiKeyRepository] are supplied.
     */
    private val requestGitHubAuthTool: RequestGitHubAuthenticationTool? =
        if (settingsRepository != null && apiKeyRepository != null)
            RequestGitHubAuthenticationTool(settingsRepository, apiKeyRepository)
        else null

    private val clipboardTool: ClipboardTool? = if (context != null) ClipboardTool(context) else null

    override fun getToolDefinitions(): List<ToolDefinition> = buildList {
        addAll(fileToolManager.getToolDefinitions().filterNot { it.name == "web_search" })
        addAll(WebSearchTool.getToolDefinitions())
        addAll(NetworkRequestTool.getToolDefinitions())
        addAll(QualitySecurityTool.getToolDefinitions())
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
        addAll(TaskManagerTool.getToolDefinitions())
        addAll(N8nAutomationTool.getToolDefinitions())
        addAll(VisualInspectorTool.getToolDefinitions())
        addAll(TelegramPublisherTool.getToolDefinitions())
        addAll(TelegramBotTool.getToolDefinitions())
        addAll(DiscordBotTool.getToolDefinitions())
        addAll(SlackTool.getToolDefinitions())
        addAll(SendGridEmailTool.getToolDefinitions())
        clipboardTool?.let { addAll(it.getToolDefinitions()) }
        addAll(WhatsAppTool.getToolDefinitions())
        addAll(WhatsAppBridgeTool.getToolDefinitions())
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
            // Enhanced manifest analyzer companion tool (signatures, native libs, cache)
            add(ToolDefinition(
                name = "enhanced_manifest_analyzer",
                description = "Enhanced manifest analysis: signatures (SHA1/SHA256), native libraries, apk metadata, and exported component counts. Returns JSON.",
                parameters = listOf(
                    ToolParameter(name = "target_package", type = "string", description = "Package name of the app to analyze", required = true)
                )
            ))
            add(ToolDefinition(
                name = "enhanced_intent_resolver",
                description = "Resolve intents across activities, services, and receivers. Use action, uri, or mime_type.",
                parameters = listOf(
                    ToolParameter(name = "action", type = "string", description = "Intent action (default ACTION_VIEW)", required = false),
                    ToolParameter(name = "uri", type = "string", description = "URI to resolve (e.g., 'https://example.com')", required = false),
                    ToolParameter(name = "mime_type", type = "string", description = "MIME type to resolve (e.g., 'image/png')", required = false)
                )
            ))
            add(ToolDefinition(
                name = "enhanced_cached_analysis",
                description = "Return cached enhanced analysis JSON for a package if available and fresh (1h).",
                parameters = listOf(
                    ToolParameter(name = "target_package", type = "string", description = "Package name", required = true)
                )
            ))
            add(ToolDefinition(
                name = "enhanced_manifest_to_html",
                description = "Export enhanced manifest analysis to a single-file HTML report stored in cacheDir. Returns absolute file path on success.",
                parameters = listOf(
                    ToolParameter(name = "target_package", type = "string", description = "Package name", required = true)
                )
            ))
            // Enhanced network security and attack-surface tools
            add(ToolDefinition(
                name = "enhanced_network_security",
                description = "Extract networkSecurityConfig XML snippets packaged in the APK and run quick heuristics (cleartext/trust-anchors). Returns JSON.",
                parameters = listOf(
                    ToolParameter(name = "target_package", type = "string", description = "Package name", required = true)
                )
            ))
            add(ToolDefinition(
                name = "enhanced_attack_surface",
                description = "Build an attack-surface JSON listing exported components, bare exports, and useful am/content commands.",
                parameters = listOf(
                    ToolParameter(name = "target_package", type = "string", description = "Package name", required = true)
                )
            ))
            addAll(WebScraperTool.getToolDefinitions())
            addAll(AdvancedFileTools.getToolDefinitions())
            addAll(OmniCoreAgentTool.getToolDefinitions())
            addAll(AgentRuntimeTool.getToolDefinitions())
            addAll(TermuxEnvironmentBridge.getToolDefinitions())
            addAll(PythonRuntimeManager.getToolDefinitions())
            addAll(LauncherControlTool.getToolDefinitions())
            addAll(WidgetGeneratorTool.getToolDefinitions())
            // ── Dynamic Self-Sandbox Tool ────────────────────────────────────
            // Always present when context is available — it requires the
            // PrivilegedExecutionManager (Shizuku/rish/root) which is initialised
            // from the application context.
            addAll(AgentSandboxTool.getToolDefinitions())
        }
        addAll(SocialMediaTool.getToolDefinitions())
        if (context != null) {
            addAll(NetworkMonitorTool.getToolDefinitions())
            if (settingsRepository != null) {
                addAll(AutofillAssistTool.getToolDefinitions())
            }
            addAll(OmniLinkTool.getToolDefinitions())
        }
        if (headlessBrowserManager != null) {
            addAll(headlessBrowserManager.getToolDefinitions())
        }
        if (vectorMemoryManager != null) {
            addAll(vectorMemoryManager.getToolDefinitions())
        }
        if (requestGitHubAuthTool != null) {
            addAll(RequestGitHubAuthenticationTool.getToolDefinitions())
        }

        // ── Execution Diagnostics Tool ──
        addAll(executionDiagnostics.getToolDefinitions())

        // ── Media control tool ──
        if (context != null) {
            add(ToolDefinition(
                name = "media_control",
                description = "Control media playback across all apps (play, pause, stop, next, previous, toggle). " +
                        "Also supports 'info' action to get currently playing track details. " +
                        "Requires Notification Access permission.",
                parameters = listOf(
                    ToolParameter(
                        name = "action",
                        type = "string",
                        description = "One of: play, pause, stop, next, previous, toggle, info",
                        required = true
                    )
                )
            ))
        }

        // ── Device admin tool ──
        if (context != null) {
            add(ToolDefinition(
                name = "device_admin",
                description = "Device administration: lock screen, set password policies, check admin status. " +
                        "Requires Device Admin permission. Actions: lock_screen, set_password_min_length, " +
                        "set_lock_timeout, status, request_activation.",
                parameters = listOf(
                    ToolParameter(
                        name = "action",
                        type = "string",
                        description = "One of: lock_screen, set_password_min_length, set_lock_timeout, status, request_activation",
                        required = true
                    ),
                    ToolParameter(
                        name = "value",
                        type = "string",
                        description = "Value for the action (e.g., min password length, timeout in ms)",
                        required = false
                    )
                )
            ))
        }

        // ── Incoming SMS capture tool ──
        add(ToolDefinition(
            name = "read_incoming_sms",
            description = "Read real-time incoming SMS messages captured by the background SMS receiver. " +
                    "Returns recently received messages with sender, body, and timestamp. " +
                    "Different from sms_reader_tool which reads the SMS database — this reads only " +
                    "messages that arrived while the app was running.",
            parameters = listOf(
                ToolParameter(
                    name = "sender_filter",
                    type = "string",
                    description = "Filter by sender phone number (substring match)",
                    required = false
                ),
                ToolParameter(
                    name = "limit",
                    type = "string",
                    description = "Max number of messages to return (default: 20)",
                    required = false
                )
            )
        ))

        // ── Input method (IME) tool ──
        add(ToolDefinition(
            name = "ime_tool",
            description = "Interact with OmniDev's Input Method Service to type text into any app's " +
                    "input field, read text near the cursor, or delete text. The OmniDev IME must " +
                    "be enabled and active. Actions: commit_text, delete, get_selected, get_before_cursor, status.",
            parameters = listOf(
                ToolParameter(
                    name = "action",
                    type = "string",
                    description = "One of: commit_text, delete, get_selected, get_before_cursor, status",
                    required = true
                ),
                ToolParameter(
                    name = "text",
                    type = "string",
                    description = "Text to commit (for commit_text action)",
                    required = false
                ),
                ToolParameter(
                    name = "length",
                    type = "string",
                    description = "Number of chars for delete/get_before_cursor (default: 1 for delete, 100 for get)",
                    required = false
                )
            )
        ))

        // ── Sync service control tool ──
        if (context != null) {
            add(ToolDefinition(
                name = "sync_service",
                description = "Control the background sync service. Actions: start, stop, status. " +
                        "The sync service periodically checks for scheduled tasks and manages " +
                        "background data synchronization.",
                parameters = listOf(
                    ToolParameter(
                        name = "action",
                        type = "string",
                        description = "One of: start, stop, status",
                        required = true
                    )
                )
            ))
        }
    }

    override suspend fun executeTool(
        name: String,
        arguments: Map<String, String>,
        scopePath: String
    ): ToolExecutionResult {
        return when (name) {
            // ── Execution Diagnostics tool ──
            "execution_diagnostics" -> {
                val action = arguments["action"] ?: return missingArg("action")
                executionDiagnostics.execute(action, arguments)
            }

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

            // ── Task manager tool (Taskly-style todo list) ──
            "task_manager" ->
                TaskManagerTool.executeTool(name, arguments)

            // ── n8n automation tool ──
            "n8n_automation" -> {
                val n8nBaseUrl = settingsRepository?.observeN8nBaseUrl()?.first()
                val n8nApiKey = settingsRepository?.observeN8nApiKey()?.first()
                N8nAutomationTool.execute(
                    args = arguments,
                    settingsBaseUrl = n8nBaseUrl,
                    settingsApiKey = n8nApiKey
                )
            }

            // ── Robust web search tool ──
            "web_search" -> {
                val serpApiKey = settingsRepository?.observeSerpApiKey()?.first()
                val googleApiKey = settingsRepository?.observeGoogleCseApiKey()?.first()
                val googleCx = settingsRepository?.observeGoogleCseCx()?.first()
                WebSearchTool.execute(
                    query = arguments["query"] ?: return missingArg("query"),
                    serpApiKey = serpApiKey,
                    googleApiKey = googleApiKey,
                    googleCseCx = googleCx
                )
            }

            "web_search_deep" -> {
                val serpApiKey = settingsRepository?.observeSerpApiKey()?.first()
                val googleApiKey = settingsRepository?.observeGoogleCseApiKey()?.first()
                val googleCx = settingsRepository?.observeGoogleCseCx()?.first()
                val maxSites = arguments["max_sites"]?.toIntOrNull() ?: 5
                WebSearchTool.executeDeep(
                    query = arguments["query"] ?: return missingArg("query"),
                    maxSites = maxSites,
                    serpApiKey = serpApiKey,
                    googleApiKey = googleApiKey,
                    googleCseCx = googleCx
                )
            }

            // ── Direct network request tool ──
            "network_request" -> NetworkRequestTool.execute(arguments)

            // ── Quality/security tooling (code review, vulnerability scans, tests) ──
            "quality_security_tool" -> QualitySecurityTool.execute(context = context, args = arguments)

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

            // ── Telegram bot tool (bidirectional — send, receive, get_updates, etc.) ──
            "telegram_bot" -> {
                val botToken = settingsRepository?.observeTelegramBotToken()?.first()
                TelegramBotTool.execute(botToken = botToken, args = arguments)
            }

            // ── Discord bot tool (full bidirectional Discord Bot API) ──
            "discord_bot" -> {
                val botToken = settingsRepository?.observeDiscordBotToken()?.first()
                DiscordBotTool.execute(botToken = botToken, args = arguments)
            }

            // ── WhatsApp Business Cloud API tool ──
            "whatsapp" -> {
                val phoneNumberId = settingsRepository?.observeWhatsAppPhoneNumberId()?.first()
                val accessToken   = settingsRepository?.observeWhatsAppAccessToken()?.first()
                WhatsAppTool.execute(phoneNumberId = phoneNumberId, accessToken = accessToken, args = arguments)
            }

            // ── WhatsApp Baileys Bridge tool ──
            "whatsapp_bridge" -> {
                val bridgeUrl = settingsRepository?.observeWhatsAppBridgeUrl()?.first()
                WhatsAppBridgeTool.execute(bridgeUrl = bridgeUrl, args = arguments)
            }

            // ── Slack tool (full bidirectional Slack Web API) ──
            "slack" -> {
                val slackToken = settingsRepository?.observeSlackBotToken()?.first()
                SlackTool.execute(token = slackToken, args = arguments)
            }

            // ── SendGrid email tool ──
            "sendgrid_email", "send_email" -> {
                val key = settingsRepository?.observeSendGridApiKey()?.first()
                SendGridEmailTool.execute(apiKey = key, args = arguments)
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
                val command = arguments["command"] ?: return missingArg("command")
                val contextForIntent = context
                val viewUrl = extractUrlFromAmStartViewCommand(command)
                if (!viewUrl.isNullOrBlank()) {
                    if (contextForIntent != null) {
                        return fireViewIntentFallback(contextForIntent, viewUrl)
                    }
                }
                if (contextForIntent != null && isAmStartSetAlarmCommand(command)) {
                    parseSetAlarmCommand(command)?.let { alarmArgs ->
                        return fireSetAlarmIntentFallback(
                            ctx = contextForIntent,
                            hour = alarmArgs.hour,
                            minute = alarmArgs.minute,
                            message = alarmArgs.message,
                            skipUi = alarmArgs.skipUi
                        )
                    }
                }

                // Uses AdvancedRootShellTool which uses the fixed PrivilegedExecutionManager
                val rootResult = AdvancedRootShellTool.execute(command = command)
                rootResult
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
            "enhanced_manifest_analyzer" -> {
                val ctx = context
                    ?: return ToolExecutionResult("Enhanced manifest analyzer requires Android context.", isError = true)
                val pkg = arguments["target_package"] ?: return missingArg("target_package")
                val pm = ctx.packageManager
                val flags = PackageManager.GET_ACTIVITIES or
                    PackageManager.GET_SERVICES or
                    PackageManager.GET_RECEIVERS or
                    PackageManager.GET_PROVIDERS or
                    PackageManager.GET_PERMISSIONS or
                    PackageManager.GET_META_DATA
                val packageInfo = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(pkg, flags)
                    }
                } catch (e: Exception) { null }

                if (packageInfo == null) return ToolExecutionResult("Package '$pkg' not found.", isError = true)
                val resultJson = EnhancedAppManifestAnalyzerTool.buildEnhancedJson(ctx, packageInfo)
                EnhancedAppManifestAnalyzerTool.putCachedAnalysis(ctx, pkg, resultJson)
                ToolExecutionResult(resultJson.toString(2))
            }
            "intent_resolver" -> {
                val ctx = context
                    ?: return ToolExecutionResult("Intent resolver tool requires Android context.", isError = true)
                AppManifestAnalyzerTool.executeIntentResolver(
                    context = ctx,
                    action = arguments["action"],
                    uri = arguments["uri"],
                    mimeType = arguments["mime_type"]
                )
            }
            "enhanced_intent_resolver" -> {
                val ctx = context
                    ?: return ToolExecutionResult("Enhanced intent resolver requires Android context.", isError = true)
                val res = EnhancedAppManifestAnalyzerTool.resolveIntentAll(
                    context = ctx,
                    action = arguments["action"],
                    uri = arguments["uri"],
                    mimeType = arguments["mime_type"]
                )
                ToolExecutionResult(res.toString(2))
            }
            "batch_manifest_analyzer" -> {
                val ctx = context
                    ?: return ToolExecutionResult("Batch manifest analyzer requires Android context.", isError = true)
                AppManifestAnalyzerTool.executeBatch(
                    context = ctx,
                    packages = arguments["packages"] ?: return missingArg("packages")
                )
            }
            "enhanced_cached_analysis" -> {
                val ctx = context
                    ?: return ToolExecutionResult("Enhanced cached analysis requires Android context.", isError = true)
                val pkg = arguments["target_package"] ?: return missingArg("target_package")
                val cached = EnhancedAppManifestAnalyzerTool.getCachedAnalysis(ctx, pkg)
                if (cached == null) {
                    ToolExecutionResult("No fresh cached analysis for '$pkg'.", isError = false)
                } else {
                    ToolExecutionResult(cached.toString(2))
                }
            }

            // ── Privileged execution tool (Shizuku / rish / root via PrivilegedExecutionManager) ──
            "privileged_tool" -> {
                val action = arguments["action"] ?: return missingArg("action")
                OmniCoreAgentTool.execute(action = action, args = arguments)
            }

            // ── Agent runtime / tool-installer tool ──
            "agent_runtime" -> {
                val action = arguments["action"] ?: return missingArg("action")
                AgentRuntimeTool.execute(context = context ?: return missingContext(), action = action, args = arguments)
            }

            // ── Termux bridge tool (direct Termux env executor) ──
            "termux_bridge" -> {
                TermuxEnvironmentBridge.executeTool(arguments)
            }

            // ── Autonomous Python runtime manager ──
            "python_runtime" -> {
                val action = arguments["action"] ?: return missingArg("action")
                PythonRuntimeManager.execute(action = action, args = arguments)
            }

            // ── Universal launcher control tool ──
            "system_launcher_tool" -> {
                val action = arguments["action"] ?: return missingArg("action")
                LauncherControlTool.execute(action = action, args = arguments)
            }

            // ── Omni-Widgets UI generation tool ──
            "widget_generator_tool" -> {
                WidgetGeneratorTool.execute(args = arguments)
            }

            // ── Dynamic Self-Sandbox Tool ─────────────────────────────────────
            // Runs shell scripts in an ephemeral, UUID-keyed /data/local/tmp/
            // sandbox that is always wiped after execution.
            "sandbox_execution_tool" -> {
                AgentSandboxTool.executeTool(arguments)
            }

            // ── Social media / video tool ──
            "social_media_video" -> {
                val action = arguments["action"] ?: return missingArg("action")
                SocialMediaTool.execute(context = context, action = action, args = arguments)
            }

            // ── Network traffic monitor (VPN-based) ──
            "network_monitor" -> {
                val action = arguments["action"] ?: return missingArg("action")
                NetworkMonitorTool.execute(context = context ?: return missingContext(), action = action, args = arguments)
            }

            // ── Autofill assistant tool ──
            "autofill_assist" -> {
                val ctx = context ?: return missingContext()
                val settings = settingsRepository
                    ?: return ToolExecutionResult("Settings repository not available.", isError = true)
                val action = arguments["action"] ?: return missingArg("action")
                AutofillAssistTool.execute(
                    context = ctx,
                    settingsRepository = settings,
                    action = action,
                    args = arguments
                )
            }

            // ── Omni-Link universal extension tool ──
            "omni_link" -> {
                val action = arguments["action"] ?: return missingArg("action")
                val ctx = context ?: return missingContext()
                ExtensionConnectionManager.initialize(ctx)
                OmniLinkTool.execute(action = action, args = arguments)
            }

            // ── Vector memory tools ──
            "vector_store", "vector_search", "vector_similar" -> {
                val vmm = vectorMemoryManager
                    ?: return ToolExecutionResult("Vector memory manager not configured.", isError = true)
                vmm.executeTool(name, arguments)
            }

            // ── Web scraper tool (Deep Research) ──
            "web_scraper" -> {
                WebScraperTool.execute(
                    url = arguments["url"] ?: return missingArg("url"),
                    selector = arguments["selector"]
                )
            }

            "scrape_multiple" -> {
                val rawUrls = arguments["urls"] ?: return missingArg("urls")
                val urls = rawUrls.split(",").map { it.trim() }.filter { it.isNotBlank() }
                WebScraperTool.executeMultiple(urls = urls, selector = arguments["selector"])
            }

            // ── Headless browser tools (Ghost Browser) ──
            "browser_navigate" -> {
                val browser = headlessBrowserManager
                    ?: return ToolExecutionResult("Headless browser not configured.", isError = true)
                browser.navigate(url = arguments["url"] ?: return missingArg("url"))
            }
            "browser_execute_js" -> {
                val browser = headlessBrowserManager
                    ?: return ToolExecutionResult("Headless browser not configured.", isError = true)
                browser.executeJs(jsCode = arguments["js_code"] ?: return missingArg("js_code"))
            }
            "browser_get_dom" -> {
                val browser = headlessBrowserManager
                    ?: return ToolExecutionResult("Headless browser not configured.", isError = true)
                browser.getDom()
            }

            // ── Advanced root file tools ──
            "grep_search", "find_files", "file_permissions", "disk_usage", "archive_tool" -> {
                AdvancedFileTools.executeTool(name, arguments)
            }

            // ── Agentic GitHub authentication tool ──
            "request_github_auth" -> {
                val authTool = requestGitHubAuthTool
                    ?: return ToolExecutionResult("GitHub auth tool requires settingsRepository and apiKeyRepository.", isError = true)
                authTool.execute(requestedScopes = arguments["requested_scopes"])
            }

            // ── Clipboard tool ──
            "clipboard" -> clipboardTool?.execute(arguments)
                ?: ToolExecutionResult("Clipboard tool unavailable (no context).", isError = true)

            // ── Media control tool ──
            "media_control" -> {
                val ctx = context
                    ?: return ToolExecutionResult("Media control requires Android context.", isError = true)
                val action = arguments["action"] ?: return missingArg("action")
                if (action.lowercase() == "info") {
                    ToolExecutionResult(OmniMediaSessionService.getActiveMediaInfo(ctx))
                } else {
                    ToolExecutionResult(OmniMediaSessionService.controlMedia(ctx, action))
                }
            }

            // ── Device admin tool ──
            "device_admin" -> {
                val ctx = context
                    ?: return ToolExecutionResult("Device admin requires Android context.", isError = true)
                val action = arguments["action"] ?: return missingArg("action")
                when (action.lowercase()) {
                    "lock_screen" -> {
                        val success = OmniDeviceAdminReceiver.lockScreen(ctx)
                        if (success) ToolExecutionResult("🔒 Screen locked successfully.")
                        else ToolExecutionResult("Device Admin not active. Use action 'request_activation' first.", isError = true)
                    }
                    "set_password_min_length" -> {
                        val len = arguments["value"]?.toIntOrNull()
                            ?: return ToolExecutionResult("'value' must be an integer for min password length.", isError = true)
                        val success = OmniDeviceAdminReceiver.setMinPasswordLength(ctx, len)
                        if (success) ToolExecutionResult("✅ Minimum password length set to $len.")
                        else ToolExecutionResult("Device Admin not active.", isError = true)
                    }
                    "set_lock_timeout" -> {
                        val ms = arguments["value"]?.toLongOrNull()
                            ?: return ToolExecutionResult("'value' must be timeout in milliseconds.", isError = true)
                        val success = OmniDeviceAdminReceiver.setMaxScreenLockTimeout(ctx, ms)
                        if (success) ToolExecutionResult("✅ Screen lock timeout set to ${ms}ms.")
                        else ToolExecutionResult("Device Admin not active.", isError = true)
                    }
                    "status" -> {
                        val active = OmniDeviceAdminReceiver.isAdminActive(ctx)
                        ToolExecutionResult("Device Admin status: ${if (active) "✅ Active" else "❌ Inactive (use request_activation)"}")
                    }
                    "request_activation" -> {
                        OmniDeviceAdminReceiver.requestAdminActivation(ctx)
                        ToolExecutionResult("📱 Device Admin activation dialog launched. User must approve.")
                    }
                    else -> ToolExecutionResult("Unknown device_admin action '$action'.", isError = true)
                }
            }

            // ── Incoming SMS capture tool ──
            "read_incoming_sms" -> {
                val senderFilter = arguments["sender_filter"]
                val limit = arguments["limit"]?.toIntOrNull() ?: 20
                val messages = if (senderFilter != null) {
                    SmsCaptureBuffer.getFromSender(senderFilter, limit)
                } else {
                    SmsCaptureBuffer.getRecent(limit)
                }
                if (messages.isEmpty()) {
                    ToolExecutionResult("No incoming SMS captured yet. Messages appear here as they arrive in real-time.")
                } else {
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    val sb = StringBuilder("Captured SMS (${messages.size}):\n")
                    for (msg in messages) {
                        sb.appendLine("  From: ${msg.sender}")
                        sb.appendLine("  Time: ${dateFormat.format(Date(msg.timestamp))}")
                        sb.appendLine("  Body: ${msg.body}")
                        sb.appendLine("  ---")
                    }
                    ToolExecutionResult(sb.toString())
                }
            }

            // ── Input Method (IME) tool ──
            "ime_tool" -> {
                val action = arguments["action"] ?: return missingArg("action")
                when (action.lowercase()) {
                    "commit_text" -> {
                        val text = arguments["text"]
                            ?: return ToolExecutionResult("Missing 'text' argument for commit_text.", isError = true)
                        val success = OmniInputMethodService.commitText(text)
                        if (success) ToolExecutionResult("✅ Committed ${text.length} chars to input field.")
                        else ToolExecutionResult("OmniDev IME is not active. User must enable and switch to it in Settings → Languages & Input.", isError = true)
                    }
                    "delete" -> {
                        val len = arguments["length"]?.toIntOrNull() ?: 1
                        val success = OmniInputMethodService.deleteSurrounding(len)
                        if (success) ToolExecutionResult("✅ Deleted $len char(s).")
                        else ToolExecutionResult("OmniDev IME is not active.", isError = true)
                    }
                    "get_selected" -> {
                        val text = OmniInputMethodService.getSelectedText()
                        ToolExecutionResult(text ?: "(no text selected or IME not active)")
                    }
                    "get_before_cursor" -> {
                        val len = arguments["length"]?.toIntOrNull() ?: 100
                        val text = OmniInputMethodService.getTextBeforeCursor(len)
                        ToolExecutionResult(text ?: "(no text or IME not active)")
                    }
                    "status" -> {
                        val active = OmniInputMethodService.isActive.value
                        ToolExecutionResult("OmniDev IME status: ${if (active) "✅ Active" else "❌ Inactive"}")
                    }
                    else -> ToolExecutionResult("Unknown ime_tool action '$action'.", isError = true)
                }
            }

            // ── Sync service control tool ──
            "sync_service" -> {
                val ctx = context
                    ?: return ToolExecutionResult("Sync service requires Android context.", isError = true)
                val action = arguments["action"] ?: return missingArg("action")
                when (action.lowercase()) {
                    "start" -> {
                        OmniSyncService.start(ctx)
                        ToolExecutionResult("✅ Background sync service started.")
                    }
                    "stop" -> {
                        OmniSyncService.stop(ctx)
                        ToolExecutionResult("⏹️ Background sync service stopped.")
                    }
                    "status" -> {
                        val state = OmniSyncService.syncState.value
                        val lastSync = OmniSyncService.lastSyncTimeMs.value
                        val lastStr = if (lastSync > 0) {
                            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                                .format(Date(lastSync))
                        } else "never"
                        ToolExecutionResult("Sync status: $state | Last sync: $lastStr")
                    }
                    else -> ToolExecutionResult("Unknown sync_service action '$action'.", isError = true)
                }
            }

            // ── File tools (default fallback) ──
            else ->
                fileToolManager.executeTool(name, arguments, scopePath)
        }
    }

    private fun missingArg(name: String) =
        ToolExecutionResult("Missing required argument: $name", isError = true)

    private fun missingContext() =
        ToolExecutionResult("Context not available for this operation.", isError = true)

    private fun extractUrlFromAmStartViewCommand(command: String): String? {
        if (!command.contains("am start", ignoreCase = true)) return null
        if (!AM_START_VIEW_ACTION_REGEX.containsMatchIn(command)) return null
        val match = AM_START_DATA_URL_REGEX.find(command) ?: return null
        val doubleQuoted = match.groupValues.getOrNull(1).orEmpty()
        val singleQuoted = match.groupValues.getOrNull(2).orEmpty()
        val unquoted = match.groupValues.getOrNull(3).orEmpty()
        val extractedUrl = when {
            doubleQuoted.isNotBlank() -> doubleQuoted
            singleQuoted.isNotBlank() -> singleQuoted
            unquoted.isNotBlank() -> unquoted
            else -> return null
        }
        return extractedUrl.trim().takeIf { isValidHttpUrl(it) }
    }

    private fun isAmStartSetAlarmCommand(command: String): Boolean {
        if (!command.contains("am start", ignoreCase = true)) return false
        return AM_START_SET_ALARM_ACTION_REGEX.containsMatchIn(command)
    }

    private data class SetAlarmArgs(
        val hour: Int,
        val minute: Int,
        val message: String?,
        val skipUi: Boolean
    )

    private fun parseSetAlarmCommand(command: String): SetAlarmArgs? {
        val hour = AM_START_SET_ALARM_HOUR_REGEX.find(command)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null
        val minute = AM_START_SET_ALARM_MINUTES_REGEX.find(command)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null
        if (hour !in ALARM_HOUR_MIN..ALARM_HOUR_MAX || minute !in ALARM_MINUTE_MIN..ALARM_MINUTE_MAX) return null

        val messageMatch = AM_START_SET_ALARM_MESSAGE_REGEX.find(command)
        val message = messageMatch?.let {
            val doubleQuoted = it.groupValues.getOrNull(1).orEmpty()
            val singleQuoted = it.groupValues.getOrNull(2).orEmpty()
            val unquoted = it.groupValues.getOrNull(3).orEmpty()
            when {
                doubleQuoted.isNotBlank() -> doubleQuoted
                singleQuoted.isNotBlank() -> singleQuoted
                unquoted.isNotBlank() -> unquoted
                else -> null
            }
        }

        val skipUi = AM_START_SET_ALARM_SKIP_UI_REGEX.find(command)
            ?.groupValues?.getOrNull(1)?.equals("true", ignoreCase = true) ?: false

        return SetAlarmArgs(hour = hour, minute = minute, message = message, skipUi = skipUi)
    }

    private fun isValidHttpUrl(candidate: String): Boolean {
        return runCatching {
            val uri = URI(candidate.trim())
            (uri.scheme.equals("http", ignoreCase = true) ||
                uri.scheme.equals("https", ignoreCase = true)) &&
                !uri.host.isNullOrBlank()
        }.getOrDefault(false)
    }

    private fun fireViewIntentFallback(ctx: Context, viewUrl: String): ToolExecutionResult {
        return AndroidIntentTool.fire(
            context = ctx,
            action = Intent.ACTION_VIEW,
            extraUri = viewUrl
        ).toDisplayString().let { ToolExecutionResult(it) }
    }

    private fun fireSetAlarmIntentFallback(
        ctx: Context,
        hour: Int,
        minute: Int,
        message: String?,
        skipUi: Boolean
    ): ToolExecutionResult {
        fun buildSetAlarmIntent(skipUiValue: Boolean) = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (!message.isNullOrBlank()) {
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
            }
            putExtra(AlarmClock.EXTRA_SKIP_UI, skipUiValue)
        }
        return runCatching {
            ctx.startActivity(buildSetAlarmIntent(skipUi))
            ToolExecutionResult("✅ Alarm intent fired for %02d:%02d.".format(hour, minute))
        }.getOrElse { skipUiError ->
            runCatching {
                ctx.startActivity(buildSetAlarmIntent(skipUiValue = false))
                ToolExecutionResult("✅ Alarm intent fired (UI fallback) for %02d:%02d.".format(hour, minute))
            }.getOrElse { fallbackError ->
                ToolExecutionResult(
                    "Failed to launch alarm intent (primary: ${skipUiError.message}, fallback: ${fallbackError.message})",
                    isError = true
                )
            }
        }
    }
}