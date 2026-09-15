package com.omnidev.workspace

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.omnidev.workspace.data.auth.OAuthManager
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.network.CompletionService
import com.omnidev.workspace.data.repository.AnalyticsRepository
import com.omnidev.workspace.data.repository.ApiKeyRepository
import com.omnidev.workspace.data.repository.ChatRepository
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.data.tools.AgentBrainTools
import com.omnidev.workspace.data.tools.BuildDoctorTools
import com.omnidev.workspace.data.tools.CompositeToolManager
import com.omnidev.workspace.data.tools.EnvironmentSetupManager
import com.omnidev.workspace.data.tools.FileToolManager
import com.omnidev.workspace.data.tools.GodEyeProfilerTool
import com.omnidev.workspace.data.tools.MemoryManager
import com.omnidev.workspace.data.tools.NotificationCaptureTool
import com.omnidev.workspace.data.tools.PermissionRequestBridge
import com.omnidev.workspace.data.tools.ProgressiveTrustTool
import com.omnidev.workspace.data.tools.RepoContextTools
import com.omnidev.workspace.data.tools.RollbackTools
import com.omnidev.workspace.data.tools.ScriptRunnerTool
import com.omnidev.workspace.data.tools.ShizukuCommandTool
import com.omnidev.workspace.data.tools.VectorMemoryManager
import com.omnidev.workspace.domain.attachment.AttachmentProcessor
import com.omnidev.workspace.domain.engine.AgentConfig
import com.omnidev.workspace.domain.engine.AgentPipeline
import com.omnidev.workspace.domain.engine.SwarmOrchestrator
import com.omnidev.workspace.ui.browser.BrowserFileChooserBridge
import com.omnidev.workspace.ui.chat.ChatViewModel
import com.omnidev.workspace.ui.navigation.AppNavigation
import com.omnidev.workspace.ui.providers.ProvidersViewModel
import com.omnidev.workspace.ui.settings.AISettingsViewModel
import com.omnidev.workspace.ui.theme.OmniDevTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Main entry point for OmniDev Workspace. */
class MainActivity : ComponentActivity() {

    companion object {
        /** Emits the OAuth authorization code received via deep link callback. */
        val pendingOAuthCode: MutableStateFlow<String?> = MutableStateFlow(null)
        val pendingChatSession = MutableStateFlow<Long?>(null)

        /**
         * One-shot navigation signal used by the autonomous agent when it reaches
         * a password/OTP/CAPTCHA/passkey/payment/consent step that needs a human.
         * AppNavigation consumes it and opens Browser Viewer immediately.
         */
        val pendingBrowserHandoff = MutableStateFlow(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PermissionRequestBridge.attach(this)
        enableEdgeToEdge()

        NotificationCaptureTool.initialize(applicationContext)

        // ── Manual Dependency Injection ──
        val settingsRepository = SettingsRepository(applicationContext)
        val apiKeyRepository = ApiKeyRepository(applicationContext)
        val analyticsRepository = AnalyticsRepository(applicationContext)

        val database = OmniDevDatabase.getInstance(applicationContext)
        val chatRepository = ChatRepository(database.chatSessionDao(), database.chatMessageDao())
        val memoryManager = MemoryManager(database.knowledgeDao())

        val fileToolManager = FileToolManager()
        val environmentSetupManager = EnvironmentSetupManager
        val godEyeProfilerTool = GodEyeProfilerTool(applicationContext, ShizukuCommandTool)
        val notionPublisherTool = com.omnidev.workspace.data.tools.NotionPublisherTool(settingsRepository)

        val app = OmniDevApp.instance
        val agentBrainTools = AgentBrainTools(
            reflexion = app.reflexionEngine,
            episodic = app.episodicMemoryStore
        )
        val rollbackTools = RollbackTools(app.rollbackManager)
        val repoContextTools = RepoContextTools(app.repoIndexer, app.repoContextEngine)
        val buildDoctorTools = BuildDoctorTools(app.buildDoctorPro)

        val toolManager = CompositeToolManager(
            fileToolManager = fileToolManager,
            memoryManager = memoryManager,
            context = applicationContext,
            environmentSetupManager = environmentSetupManager,
            settingsRepository = settingsRepository,
            godEyeProfilerTool = godEyeProfilerTool,
            notionPublisherTool = notionPublisherTool,
            vectorMemoryManager = VectorMemoryManager(database.knowledgeDao()),
            apiKeyRepository = apiKeyRepository,
            headlessBrowserManager = app.headlessBrowserManager,
            chatRepository = chatRepository,
            agentBrainTools = agentBrainTools,
            rollbackTools = rollbackTools,
            repoContextTools = repoContextTools,
            buildDoctorTools = buildDoctorTools,
            causalChainPlannerTool = app.causalChainPlannerTool,
            progressiveTrustTool = ProgressiveTrustTool(app.progressiveTrustEngine),
            scriptRunnerTool = ScriptRunnerTool()
        )

        val completionService = CompletionService(settingsRepository)
        val completionProvider: suspend (com.omnidev.workspace.data.model.CompletionRequest) -> com.omnidev.workspace.data.model.CompletionResponse =
            completionService::invoke

        val agentPipeline = AgentPipeline(
            toolManager = toolManager,
            mcpRegistry = app.mcpRegistry,
            completionProvider = completionProvider,
            streamingCompletionProvider = { request, onChunk ->
                completionService.stream(request, onChunk)
            },
            config = AgentConfig.THOROUGH,
            apiKeyRepository = apiKeyRepository,
            memoryManager = memoryManager,
            smartLearningBridge = app.smartLearningBridge,
            analyticsRepository = analyticsRepository
        )

        val swarmOrchestrator = SwarmOrchestrator(
            toolManager = toolManager,
            completionProvider = completionProvider,
            apiKeyRepository = apiKeyRepository,
            memoryManager = memoryManager,
            smartLearningBridge = app.smartLearningBridge,
            streamingCompletionProvider = { request, onChunk ->
                completionService.stream(request, onChunk)
            },
            analyticsRepository = analyticsRepository
        )

        val autoHealBuildUseCase = com.omnidev.workspace.domain.engine.AutoHealBuildUseCase(
            agentPipeline = agentPipeline,
            settingsRepository = settingsRepository,
            apiKeyRepository = apiKeyRepository,
            toolManager = toolManager
        )

        val settingsViewModel = AISettingsViewModel(settingsRepository)
        val attachmentProcessor = AttachmentProcessor(contentResolver)
        val chatViewModel = ChatViewModel(
            settingsRepository = settingsRepository,
            agentPipeline = agentPipeline,
            chatRepository = chatRepository,
            attachmentProcessor = attachmentProcessor,
            completionProvider = completionProvider,
            streamingCompletionProvider = { request, onChunk ->
                completionService.stream(request, onChunk)
            },
            swarmOrchestrator = swarmOrchestrator,
            apiKeyRepository = apiKeyRepository,
            fileToolManager = fileToolManager,
            autoHealBuildUseCase = autoHealBuildUseCase,
            analyticsRepository = analyticsRepository,
            compositeToolManager = toolManager
        )
        val providersViewModel = ProvidersViewModel(apiKeyRepository, settingsRepository)

        setContent {
            OmniDevTheme {
                AppNavigation(
                    settingsViewModel = settingsViewModel,
                    chatViewModel = chatViewModel,
                    providersViewModel = providersViewModel,
                    settingsRepository = settingsRepository,
                    database = database
                )
            }
        }

        handleOAuthCallback(intent)
        handleNavigationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        PermissionRequestBridge.attach(this)
    }

    @Deprecated("WebView FileChooserParams still delivers results through Activity results")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (BrowserFileChooserBridge.handleActivityResult(requestCode, resultCode, data)) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) OmniDevApp.instance.headlessBrowserManager.onAppClosed()
    }

    override fun onDestroy() {
        PermissionRequestBridge.detach(this)
        if (isFinishing) BrowserFileChooserBridge.cancelPending()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthCallback(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(intent: Intent) {
        intent.getLongExtra("deep_link_session_id", -1L)
            .takeIf { it > 0 }
            ?.let { pendingChatSession.value = it }

        if (intent.hasExtra("browser_handoff_notification_id") ||
            intent.getBooleanExtra("open_browser_handoff", false)
        ) {
            pendingBrowserHandoff.value = true
        }
    }

    private fun handleOAuthCallback(intent: Intent) {
        val data: Uri = intent.data ?: return
        if (data.scheme == "omnidev" && data.host == "task") {
            data.getQueryParameter("id")?.let { taskId ->
                lifecycleScope.launch {
                    val session = OmniDevDatabase.getInstance(applicationContext).chatSessionDao()
                        .getByBackgroundKey("scheduled_task:$taskId")
                    session?.let { pendingChatSession.value = it.id }
                }
            }
            return
        }
        val code = OAuthManager.extractCodeFromCallback(data) ?: return
        pendingOAuthCode.value = code
    }
}
