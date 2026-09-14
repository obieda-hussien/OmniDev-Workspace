package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.network.CompletionService
import com.omnidev.workspace.data.repository.AnalyticsRepository
import com.omnidev.workspace.data.repository.ApiKeyRepository
import com.omnidev.workspace.data.repository.ChatRepository
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.data.tools.AgentBrainTools
import com.omnidev.workspace.data.tools.BuildDoctorTools
import com.omnidev.workspace.data.tools.ProgressiveTrustTool
import com.omnidev.workspace.data.tools.ScriptRunnerTool
import com.omnidev.workspace.data.tools.CompositeToolManager
import com.omnidev.workspace.data.tools.EnvironmentSetupManager
import com.omnidev.workspace.data.tools.FileToolManager
import com.omnidev.workspace.data.tools.GodEyeProfilerTool
import com.omnidev.workspace.data.tools.MemoryManager
import com.omnidev.workspace.data.tools.RepoContextTools
import com.omnidev.workspace.data.tools.RollbackTools
import com.omnidev.workspace.data.tools.ShizukuCommandTool
import com.omnidev.workspace.data.tools.VectorMemoryManager
import com.omnidev.workspace.domain.engine.AgentConfig
import com.omnidev.workspace.domain.engine.AgentPipeline
import com.omnidev.workspace.domain.engine.SwarmOrchestrator
import android.content.Context
import com.omnidev.workspace.OmniDevApp

/** Application-context dependencies for a run, independent of an Activity lifecycle. */
class AgentRuntime(context: Context) {
    private val applicationContext = context.applicationContext
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


}
