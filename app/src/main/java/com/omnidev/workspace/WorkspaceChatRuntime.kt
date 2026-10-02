package com.omnidev.workspace

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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
import com.omnidev.workspace.ui.chat.ChatViewModel

/** One process-owned runtime shared by the workspace and the system assistant. */
object WorkspaceChatRuntime {
    private val store = ViewModelStore()
    private var model: ChatViewModel? = null

    @Synchronized
    fun get(context: Context): ChatViewModel {
        model?.let { return it }
        val applicationContext = context.applicationContext
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

        val attachmentProcessor = AttachmentProcessor(applicationContext.contentResolver)
        return ViewModelProvider(store, viewModelFactory {
            initializer {
                ChatViewModel(
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
            }
        })[ChatViewModel::class.java].also { model = it }
    }
}
