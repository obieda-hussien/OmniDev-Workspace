package com.omnidev.workspace

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
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
    private val credentialStorageGate = CredentialStorageInitGate()
    private var unlockReceiverRegistered = false

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != Intent.ACTION_USER_UNLOCKED || !isUserUnlocked()) return
            try {
                initializeAfterUnlock()
            } catch (error: Exception) {
                // Keep the process alive so another unlocked entry point can retry startup.
                // The normal startup path still reports its failure to AndroidRuntime.
                Log.e("OmniDevApp", "Deferred post-unlock initialization failed", error)
                DebugLogManager.appendError("PostUnlockStartup", error)
            }
        }
    }

    private fun isUserUnlocked(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
            (getSystemService(UserManager::class.java)?.isUserUnlocked == true)

    /**
     * Enables WorkManager on-demand initialization. This is critical when the process is first
     * created by a direct-boot-aware receiver before AndroidX Startup can initialize WorkManager.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    // Android starts Application for the selected assistant even without a visible UI.
    // Keep that cold start small; construct each database/learning engine only on demand.
    private val database by lazy { OmniDevDatabase.getInstance(applicationContext) }
    val toolExecutionJournal by lazy { ToolExecutionJournal(database.toolExecutionDao(), scope = appScope) }
    val toolAwarenessEngine by lazy {
        ToolAwarenessEngine(applicationContext, database.systemKnowledgeDao(), scope = appScope).also { engine ->
            appScope.launch { runCatching { engine.initialize() }.onFailure { DebugLogManager.appendError("ToolAwareness", it) } }
        }
    }
    val mcpRegistry by lazy { McpRegistry(McpConfigManager(applicationContext)) }
    val reflexionEngine by lazy { ReflexionEngine(database.reflexionDao(), maxLessons = 2000, topKForInjection = 3, scope = appScope) }
    val episodicMemoryStore by lazy { EpisodicMemoryStore(database.episodicMemoryDao(), maxEpisodes = 2000, scope = appScope) }
    val userFeedbackLearningStore by lazy {
        UserFeedbackLearningStore(applicationContext).also { AdaptiveModeRouter.installPreferenceSource(it) }
    }
    val progressiveTrustEngine by lazy { ProgressiveTrustEngine(applicationContext) }
    val causalChainPlannerTool by lazy { CausalChainPlannerTool(CausalChainPlanner(maxNodes = 50)) }
    val smartLearningBridge by lazy {
        SmartLearningBridge(
            context = applicationContext, journal = toolExecutionJournal, awarenessEngine = toolAwarenessEngine,
            intelligenceEngine = if (BuildConfig.ALLOW_AGENT) ToolIntelligenceEngine(applicationContext, appScope) else null,
            mlEngine = if (BuildConfig.ALLOW_AGENT) ToolMachineLearningEngine(applicationContext) else null,
            monitoringSystem = if (BuildConfig.ALLOW_AGENT) ToolMonitoringSystem else null,
            reflexionEngine = reflexionEngine, episodicMemoryStore = episodicMemoryStore,
            progressiveTrustEngine = progressiveTrustEngine, causalChainPlannerTool = causalChainPlannerTool,
            userFeedbackLearningStore = userFeedbackLearningStore, scope = appScope
        ).also { bridge ->
            appScope.launch { runCatching { bridge.onSessionStart() }.onFailure { DebugLogManager.appendError("AgentBrain", it) } }
        }
    }
    val rollbackManager by lazy { RollbackManager(database.rollbackDao(), maxSnapshotsPerGroup = 200, maxBytesEvictable = 50L * 1024 * 1024) }
    val repoIndexer by lazy { RepoIndexer(database.repoIndexDao(), maxFileSizeBytes = 500L * 1024, maxSymbolsPerScope = 5000, chunkSize = 50) }
    val repoContextEngine by lazy { RepoContextEngine(database.repoIndexDao(), indexer = repoIndexer) }
    val buildDoctorPro by lazy { BuildDoctorPro(database.buildDiagnosticDao(), maxEntries = 500) }
    val headlessBrowserManager by lazy { HeadlessBrowserManager(applicationContext) }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Before providers/WorkManager/credential startup can fail, including direct boot.
        DebugLogManager.init(this)
        CrashHandler.install()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Direct-boot-aware services can create the Application before first unlock. Most of
        // OmniDev's preferences, Room, DataStore and WorkManager use credential-encrypted
        // storage and must not be touched in that state.
        if (!isUserUnlocked()) {
            Log.i("OmniDevApp", "User locked; deferring credential-protected initialization")
            registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
            unlockReceiverRegistered = true
            // Handle unlock racing with receiver registration.
            if (isUserUnlocked()) initializeAfterUnlock()
            return
        }

        initializeAfterUnlock()
    }

    /** Also safe to invoke from an existing post-unlock entry point. */
    fun initializeAfterUnlock() {
        if (!isUserUnlocked()) return
        if (!credentialStorageGate.tryBegin(userUnlocked = true)) return
        try {
            initializeCredentialProtectedComponents()
            if (unlockReceiverRegistered) {
                unregisterReceiver(unlockReceiver)
                unlockReceiverRegistered = false
            }
        } catch (error: Exception) {
            credentialStorageGate.resetAfterFailure()
            throw error
        }
    }

    private fun initializeCredentialProtectedComponents() {
        DebugLogManager.includeCredentialLogs(this)
        ensureWorkManagerInitialized(this)

        TierPolicyBootstrap.install()
        Log.i(
            "OmniDevApp",
            "TierPolicy installed: tier=${TierPolicyHolder.current.tier} " +
                "root=${TierPolicyHolder.current.allowRoot} " +
                "shizuku=${TierPolicyHolder.current.allowShizuku} " +
                "a11y=${TierPolicyHolder.current.allowAccessibility} " +
                "autoApprove=${TierPolicyHolder.current.autoApproveConfirmations}"
        )

        PrivilegedExecutionManager.init(applicationContext)
        PrivilegedExecutionFacadeBootstrap.install()
        Log.i(
            "OmniDevApp",
            "PrivilegedExecutionFacade installed (available=${PrivilegedExecutionFacadeHolder.current.isAvailable()})"
        )
        EnvironmentSetupManager.init(applicationContext)
        ToolDownloaderEngine.init(applicationContext)

        com.omnidev.workspace.data.debug.RuntimeDiagnostics.start(applicationContext)
        appScope.launch {
            runCatching {
                LauncherConnectionManager.initialize(applicationContext)
                ExtensionConnectionManager.initialize(applicationContext)
                com.omnidev.workspace.data.background.BackgroundServiceSupervisor.bootstrap(applicationContext)
            }.onFailure { DebugLogManager.appendError("StartupDiscovery", it) }
        }

        if (BuildConfig.ALLOW_AGENT) com.omnidev.workspace.data.tools.TaskSchedulerTool.initialize(
            database.scheduledTaskDao()
        )

        restoreCopilotModelsAsync()
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
        private val workManagerInitLock = Any()

        lateinit var instance: OmniDevApp
            private set

        fun ensureWorkManagerInitialized(context: android.content.Context) {
            val appContext = context.applicationContext
            synchronized(workManagerInitLock) {
                val alreadyReady = runCatching { WorkManager.getInstance(appContext) }.isSuccess
                if (alreadyReady) return

                val configuration = (appContext as? Configuration.Provider)
                    ?.workManagerConfiguration
                    ?: Configuration.Builder()
                        .setMinimumLoggingLevel(Log.INFO)
                        .build()

                runCatching { WorkManager.initialize(appContext, configuration) }
                    .onFailure { error ->
                        // Another initializer may have won the race. Verify before surfacing it.
                        if (runCatching { WorkManager.getInstance(appContext) }.isFailure) {
                            throw error
                        }
                    }
            }
        }
    }
}
