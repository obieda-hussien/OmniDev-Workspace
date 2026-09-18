package com.omnidev.workspace

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.omnidev.workspace.core.policy.TierPolicyBootstrap
import com.omnidev.workspace.core.policy.TierPolicyHolder
import com.omnidev.workspace.core.privileged.PrivilegedExecutionFacadeBootstrap
import com.omnidev.workspace.core.privileged.PrivilegedExecutionFacadeHolder
import com.omnidev.workspace.data.auth.CopilotModelRefresher
import com.omnidev.workspace.data.brain.CausalChainPlanner
import com.omnidev.workspace.data.brain.EpisodicMemoryStore
import com.omnidev.workspace.data.brain.ProgressiveTrustEngine
import com.omnidev.workspace.data.brain.ReflexionEngine
import com.omnidev.workspace.data.brain.SmartLearningBridge
import com.omnidev.workspace.data.brain.ToolAwarenessEngine
import com.omnidev.workspace.data.brain.ToolExecutionJournal
import com.omnidev.workspace.data.brain.UserFeedbackLearningStore
import com.omnidev.workspace.data.builddoctor.BuildDoctorPro
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.debug.CrashHandler
import com.omnidev.workspace.data.debug.DebugLogManager
import com.omnidev.workspace.data.ipc.ExtensionConnectionManager
import com.omnidev.workspace.data.ipc.LauncherConnectionManager
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import com.omnidev.workspace.data.mcp.McpConfigManager
import com.omnidev.workspace.data.mcp.McpRegistry
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.repo.RepoContextEngine
import com.omnidev.workspace.data.repo.RepoIndexer
import com.omnidev.workspace.data.rollback.RollbackManager
import com.omnidev.workspace.data.tools.CausalChainPlannerTool
import com.omnidev.workspace.data.tools.EnvironmentSetupManager
import com.omnidev.workspace.data.tools.HeadlessBrowserManager
import com.omnidev.workspace.data.tools.ToolDownloaderEngine
import com.omnidev.workspace.data.tools.ml.ToolMachineLearningEngine
import com.omnidev.workspace.data.tools.monitoring.ToolMonitoringSystem
import com.omnidev.workspace.data.tools.orchestration.ToolIntelligenceEngine
import com.omnidev.workspace.domain.engine.AdaptiveModeRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * OmniDev Workspace Application class.
 * Initializes application-wide dependencies and services.
 *
 * Agent Brain is deliberately assembled in dependency order: persistent memories first,
 * then the bridge that consumes them. This prevents a silent half-wired brain where the
 * Reflexion/Episodic stores exist but never participate in execution.
 */
class OmniDevApp : Application(), Configuration.Provider {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Enables WorkManager on-demand initialization. This is critical when the process is first
     * created by a direct-boot-aware receiver before AndroidX Startup can initialize WorkManager.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    lateinit var toolExecutionJournal: ToolExecutionJournal
        private set

    lateinit var toolAwarenessEngine: ToolAwarenessEngine
        private set

    lateinit var mcpRegistry: McpRegistry
        private set

    lateinit var smartLearningBridge: SmartLearningBridge
        private set

    lateinit var reflexionEngine: ReflexionEngine
        private set

    lateinit var episodicMemoryStore: EpisodicMemoryStore
        private set

    lateinit var userFeedbackLearningStore: UserFeedbackLearningStore
        private set

    lateinit var rollbackManager: RollbackManager
        private set

    lateinit var repoIndexer: RepoIndexer
        private set

    lateinit var repoContextEngine: RepoContextEngine
        private set

    lateinit var buildDoctorPro: BuildDoctorPro
        private set

    lateinit var causalChainPlannerTool: CausalChainPlannerTool
        private set

    lateinit var progressiveTrustEngine: ProgressiveTrustEngine
        private set

    lateinit var headlessBrowserManager: HeadlessBrowserManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        TierPolicyBootstrap.install()
        Log.i(
            "OmniDevApp",
            "TierPolicy installed: tier=${TierPolicyHolder.current.tier} " +
                "root=${TierPolicyHolder.current.allowRoot} " +
                "shizuku=${TierPolicyHolder.current.allowShizuku} " +
                "a11y=${TierPolicyHolder.current.allowAccessibility} " +
                "autoApprove=${TierPolicyHolder.current.autoApproveConfirmations}"
        )

        DebugLogManager.init(applicationContext)
        CrashHandler.install()
        PrivilegedExecutionManager.init(applicationContext)
        PrivilegedExecutionFacadeBootstrap.install()
        Log.i(
            "OmniDevApp",
            "PrivilegedExecutionFacade installed (available=${PrivilegedExecutionFacadeHolder.current.isAvailable()})"
        )
        EnvironmentSetupManager.init(applicationContext)
        ToolDownloaderEngine.init(applicationContext)

        headlessBrowserManager = HeadlessBrowserManager(applicationContext)
        LauncherConnectionManager.initialize(applicationContext)
        ExtensionConnectionManager.initialize(applicationContext)

        initializeAgentBrainSystem()

        com.omnidev.workspace.data.tools.TaskSchedulerTool.initialize(
            OmniDevDatabase.getInstance(applicationContext).scheduledTaskDao()
        )

        restoreCopilotModelsAsync()
    }

    /** Assemble the complete Agent Brain in strict dependency order. */
    private fun initializeAgentBrainSystem() {
        try {
            val db = OmniDevDatabase.getInstance(applicationContext)

            toolExecutionJournal = ToolExecutionJournal(
                dao = db.toolExecutionDao(),
                scope = appScope
            )

            toolAwarenessEngine = ToolAwarenessEngine(
                context = applicationContext,
                systemKnowledgeDao = db.systemKnowledgeDao(),
                scope = appScope
            )

            val intelligenceEngine = ToolIntelligenceEngine(applicationContext, appScope)
            val mlEngine = ToolMachineLearningEngine(applicationContext)
            val monitoringSystem = ToolMonitoringSystem

            mcpRegistry = McpRegistry(McpConfigManager(applicationContext))

            reflexionEngine = ReflexionEngine(
                dao = db.reflexionDao(),
                maxLessons = 2000,
                topKForInjection = 3,
                scope = appScope
            )

            episodicMemoryStore = EpisodicMemoryStore(
                dao = db.episodicMemoryDao(),
                maxEpisodes = 2000,
                scope = appScope
            )

            userFeedbackLearningStore = UserFeedbackLearningStore(applicationContext)
            AdaptiveModeRouter.installPreferenceSource(userFeedbackLearningStore)

            val trustEngine = ProgressiveTrustEngine(applicationContext)
            progressiveTrustEngine = trustEngine

            causalChainPlannerTool = CausalChainPlannerTool(
                CausalChainPlanner(maxNodes = 50)
            )

            smartLearningBridge = SmartLearningBridge(
                context = applicationContext,
                journal = toolExecutionJournal,
                awarenessEngine = toolAwarenessEngine,
                intelligenceEngine = intelligenceEngine,
                mlEngine = mlEngine,
                monitoringSystem = monitoringSystem,
                reflexionEngine = reflexionEngine,
                episodicMemoryStore = episodicMemoryStore,
                progressiveTrustEngine = trustEngine,
                causalChainPlannerTool = causalChainPlannerTool,
                userFeedbackLearningStore = userFeedbackLearningStore,
                scope = appScope
            )

            rollbackManager = RollbackManager(
                dao = db.rollbackDao(),
                maxSnapshotsPerGroup = 200,
                maxBytesEvictable = 50L * 1024 * 1024
            )

            repoIndexer = RepoIndexer(
                dao = db.repoIndexDao(),
                maxFileSizeBytes = 500L * 1024,
                maxSymbolsPerScope = 5000,
                chunkSize = 50
            )
            repoContextEngine = RepoContextEngine(
                dao = db.repoIndexDao(),
                indexer = repoIndexer
            )

            buildDoctorPro = BuildDoctorPro(
                dao = db.buildDiagnosticDao(),
                maxEntries = 500
            )

            appScope.launch {
                try {
                    toolAwarenessEngine.initialize()
                    smartLearningBridge.onSessionStart()
                    val lessons = db.reflexionDao().count()
                    val episodes = db.episodicMemoryDao().count()
                    val diagnostics = db.buildDiagnosticDao().count()
                    Log.i(
                        "OmniDevApp",
                        "Agent Brain ready • lessons=$lessons episodes=$episodes buildSolutions=$diagnostics"
                    )
                } catch (e: Exception) {
                    Log.e("OmniDevApp", "Agent Brain background init failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("OmniDevApp", "Agent Brain initialization failed: ${e.message}")
            initializeFallbackBrain()
        }
    }

    /** Fallback remains fully wired; reduced engines are acceptable, disconnected memory is not. */
    private fun initializeFallbackBrain() {
        val db = OmniDevDatabase.getInstance(applicationContext)

        toolExecutionJournal = ToolExecutionJournal(db.toolExecutionDao())
        toolAwarenessEngine = ToolAwarenessEngine(
            applicationContext,
            db.systemKnowledgeDao()
        )
        mcpRegistry = McpRegistry(McpConfigManager(applicationContext))

        reflexionEngine = ReflexionEngine(
            dao = db.reflexionDao(),
            scope = appScope
        )
        episodicMemoryStore = EpisodicMemoryStore(
            dao = db.episodicMemoryDao(),
            scope = appScope
        )
        userFeedbackLearningStore = UserFeedbackLearningStore(applicationContext)
        AdaptiveModeRouter.installPreferenceSource(userFeedbackLearningStore)

        val fallbackTrustEngine = ProgressiveTrustEngine(applicationContext)
        progressiveTrustEngine = fallbackTrustEngine

        val fallbackCausalTool = CausalChainPlannerTool(CausalChainPlanner())
        causalChainPlannerTool = fallbackCausalTool

        smartLearningBridge = SmartLearningBridge(
            context = applicationContext,
            journal = toolExecutionJournal,
            awarenessEngine = toolAwarenessEngine,
            intelligenceEngine = null,
            mlEngine = null,
            monitoringSystem = null,
            reflexionEngine = reflexionEngine,
            episodicMemoryStore = episodicMemoryStore,
            progressiveTrustEngine = fallbackTrustEngine,
            causalChainPlannerTool = fallbackCausalTool,
            userFeedbackLearningStore = userFeedbackLearningStore,
            scope = appScope
        )

        rollbackManager = RollbackManager(dao = db.rollbackDao())
        repoIndexer = RepoIndexer(dao = db.repoIndexDao())
        repoContextEngine = RepoContextEngine(
            dao = db.repoIndexDao(),
            indexer = repoIndexer
        )
        buildDoctorPro = BuildDoctorPro(dao = db.buildDiagnosticDao())

        appScope.launch {
            runCatching { smartLearningBridge.onSessionStart() }
                .onFailure { Log.w("OmniDevApp", "Fallback brain session init failed: ${it.message}") }
        }
    }

    private fun restoreCopilotModelsAsync() {
        appScope.launch {
            try {
                val apiKeyRepo = com.omnidev.workspace.data.repository.ApiKeyRepository(
                    applicationContext
                )
                val oauthToken = apiKeyRepo.getApiKey(ModelProvider.GITHUB_COPILOT)
                if (!oauthToken.isNullOrBlank()) {
                    CopilotModelRefresher.refreshModels(oauthToken)
                }
            } catch (e: Exception) {
                Log.w("OmniDevApp", "Copilot model restore failed on startup: ${e.message}")
            }
        }
    }

    companion object {
        lateinit var instance: OmniDevApp
            private set
    }
}
