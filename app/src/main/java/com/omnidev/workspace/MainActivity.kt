package com.omnidev.workspace

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.omnidev.workspace.data.auth.OAuthManager
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.network.CompletionService
import com.omnidev.workspace.data.repository.AnalyticsRepository
import com.omnidev.workspace.data.repository.ApiKeyRepository
import com.omnidev.workspace.data.repository.ChatRepository
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.data.model.ModelRole
import com.omnidev.workspace.data.tools.AgentBrainTools
import com.omnidev.workspace.data.tools.BuildDoctorTools
import com.omnidev.workspace.data.tools.CausalChainPlannerTool
import com.omnidev.workspace.data.tools.ProgressiveTrustTool
import com.omnidev.workspace.data.tools.ScriptRunnerTool
import com.omnidev.workspace.data.tools.CompositeToolManager
import com.omnidev.workspace.data.tools.EnvironmentSetupManager
import com.omnidev.workspace.data.tools.FileToolManager
import com.omnidev.workspace.data.tools.GodEyeProfilerTool
import com.omnidev.workspace.data.tools.HeadlessBrowserManager
import com.omnidev.workspace.data.tools.MemoryManager
import com.omnidev.workspace.data.tools.RepoContextTools
import com.omnidev.workspace.data.tools.RollbackTools
import com.omnidev.workspace.data.tools.ShizukuCommandTool
import com.omnidev.workspace.data.tools.TaskSchedulerTool
import com.omnidev.workspace.data.tools.VectorMemoryManager
import com.omnidev.workspace.domain.attachment.AttachmentProcessor
import com.omnidev.workspace.domain.engine.AgentConfig
import com.omnidev.workspace.domain.engine.AgentEvent
import com.omnidev.workspace.domain.engine.AgentPipeline
import com.omnidev.workspace.domain.engine.SwarmOrchestrator
import com.omnidev.workspace.ui.chat.ChatViewModel
import com.omnidev.workspace.ui.navigation.AppNavigation
import com.omnidev.workspace.ui.providers.ProvidersViewModel
import com.omnidev.workspace.ui.settings.AISettingsViewModel
import com.omnidev.workspace.ui.theme.OmniDevTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicInteger

/**
 * Main entry point for OmniDev Workspace.
 *
 * Sets up the dependency graph manually (without DI framework) and launches
 * the Compose navigation host.
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** Emits the OAuth authorization code received via deep link callback. */
        val pendingOAuthCode: MutableStateFlow<String?> = MutableStateFlow(null)
        val pendingChatSession = MutableStateFlow<Long?>(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {


super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // ── Manual Dependency Injection ──
        val settingsRepository = SettingsRepository(applicationContext)
        val apiKeyRepository = ApiKeyRepository(applicationContext)
        val analyticsRepository = AnalyticsRepository(applicationContext)

        // Room database — single instance per process
        val database = OmniDevDatabase.getInstance(applicationContext)
        val chatRepository = ChatRepository(database.chatSessionDao(), database.chatMessageDao())
        val memoryManager = MemoryManager(database.knowledgeDao())

        // Composite tool manager: file tools + memory + system assistant + build environment
        val fileToolManager = FileToolManager()
        val environmentSetupManager = EnvironmentSetupManager
        val godEyeProfilerTool = GodEyeProfilerTool(applicationContext, ShizukuCommandTool)
        val notionPublisherTool = com.omnidev.workspace.data.tools.NotionPublisherTool(settingsRepository)

        // ── Agent Brain 2.0 + Action Insurance + Repo Context + Build Doctor Pro ──
        // المحركات تُهيَّأ في OmniDevApp.onCreate() — هنا فقط نلتقط مراجعها ونغلّفها
        // كأدوات يستدعيها الـ Agent عبر الـ ReAct loop.
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
            headlessBrowserManager = OmniDevApp.instance.headlessBrowserManager,
            chatRepository = chatRepository,
            agentBrainTools = agentBrainTools,
            rollbackTools = rollbackTools,
            repoContextTools = repoContextTools,
            buildDoctorTools = buildDoctorTools,
            causalChainPlannerTool = app.causalChainPlannerTool,
            progressiveTrustTool = ProgressiveTrustTool(app.progressiveTrustEngine),
            scriptRunnerTool = ScriptRunnerTool()
        )

        // Real HTTP completion provider
        val completionService = CompletionService(settingsRepository)
        val completionProvider: suspend (com.omnidev.workspace.data.model.CompletionRequest) -> com.omnidev.workspace.data.model.CompletionResponse =
            completionService::invoke

        val agentPipeline = AgentPipeline(
            toolManager = toolManager,
            mcpRegistry = com.omnidev.workspace.OmniDevApp.instance.mcpRegistry,
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

        // Swarm orchestrator for Team Agents mode
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

        // Auto-Heal Build Loop
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

        // Handle OAuth deep link delivered with the launch intent
        handleOAuthCallback(intent)
        intent.getLongExtra("deep_link_session_id", -1L).takeIf { it > 0 }?.let { pendingChatSession.value = it }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) OmniDevApp.instance.headlessBrowserManager.onAppClosed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthCallback(intent)
        intent.getLongExtra("deep_link_session_id", -1L).takeIf { it > 0 }?.let { pendingChatSession.value = it }
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
