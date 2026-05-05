package com.omnidev.workspace

import android.content.Intent
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
        val discordPublisherTool = com.omnidev.workspace.data.tools.DiscordPublisherTool(settingsRepository)
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
            discordPublisherTool = discordPublisherTool,
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
        val completionService = CompletionService()
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
        val providersViewModel = ProvidersViewModel(apiKeyRepository)

        // ── Task Execution Bridge ──────────────────────────────────────────────
        // Connects TaskSchedulerTool to AgentPipeline so scheduled tasks are
        // ACTUALLY EXECUTED by OmniSyncService (not just marked as "running").
        //
        // Without this wiring, OmniSyncService.performSync() would detect ready
        // tasks but find `executionCallback == null` and mark them as failed
        // with "Execution bridge not configured". Setting it here closes the
        // loop: ready task → AgentPipeline.execute() → result written back.
        TaskSchedulerTool.executionCallback = { task ->
            val startMs = System.currentTimeMillis()
            val toolsUsedCount = AtomicInteger(0)
            val toolNamesList = mutableListOf<String>()
            var finalResult = ""
            var finalIterations = 0
            var executionError: String? = null

            try {
                // Optional: enrich prompt with dependency context when present.
                val dependencyContext = task.dependsOn.mapNotNull { _ ->
                    // Reserved for future: look up completed dependency results
                    // and inject them here. For now we only signal the presence
                    // of dependencies to the model via the surrounding task metadata.
                    null
                }.joinToString("\n")

                val fullPrompt = if (dependencyContext.isNotBlank()) {
                    "# Task: ${task.name}\n\n${task.prompt}\n\n## Context from dependencies:\n$dependencyContext"
                } else {
                    task.prompt
                }

                // Resolve model + scope from user settings at execution time so
                // scheduled tasks always honor the latest preferences.
                val modelId = settingsRepository
                    .observeModelIdForRole(ModelRole.AGENT)
                    .first()
                val scopePath = settingsRepository
                    .observeTargetContext()
                    .first()
                    .orEmpty()

                agentPipeline.execute(
                    userMessage = fullPrompt,
                    modelId = modelId,
                    scopePath = scopePath,
                    enableDeepThinking = false
                ).collect { event ->
                    when (event) {
                        is AgentEvent.ToolExecution -> {
                            toolsUsedCount.incrementAndGet()
                            toolNamesList.add(event.toolName)
                        }
                        is AgentEvent.FinalAnswer -> {
                            finalResult = event.content
                            finalIterations = event.totalIterations
                        }
                        is AgentEvent.Error -> {
                            executionError = event.message
                        }
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                executionError = e.message ?: "Unknown error"
            }

            val endMs = System.currentTimeMillis()
            val errorSnapshot = executionError
            val isSuccess = errorSnapshot == null && finalResult.isNotBlank()

            TaskSchedulerTool.ExecutionSummary(
                taskId = task.id,
                taskName = task.name,
                startTimeMs = startMs,
                endTimeMs = endMs,
                toolsUsed = toolsUsedCount.get(),
                toolNames = toolNamesList.toList(),
                result = finalResult.ifBlank { errorSnapshot ?: "(no output)" },
                isSuccess = isSuccess,
                iterationsUsed = finalIterations,
                errorMessage = errorSnapshot
            )
        }
        android.util.Log.i(
            "MainActivity",
            "✅ TaskSchedulerTool.executionCallback wired — scheduled tasks will now execute via AgentPipeline."
        )

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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthCallback(intent)
    }

    private fun handleOAuthCallback(intent: Intent) {
        val data: Uri = intent.data ?: return
        val code = OAuthManager.extractCodeFromCallback(data) ?: return
        pendingOAuthCode.value = code
    }
}
