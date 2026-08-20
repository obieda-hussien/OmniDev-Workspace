package com.omnidev.workspace

import android.app.Application
import android.util.Log
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
import com.omnidev.workspace.data.builddoctor.BuildDoctorPro
import com.omnidev.workspace.data.mcp.McpConfigManager
import com.omnidev.workspace.data.mcp.McpRegistry
import com.omnidev.workspace.data.brain.ToolAwarenessEngine
import com.omnidev.workspace.data.brain.ToolExecutionJournal
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.repo.RepoContextEngine
import com.omnidev.workspace.data.repo.RepoIndexer
import com.omnidev.workspace.data.rollback.RollbackManager
import com.omnidev.workspace.data.debug.CrashHandler
import com.omnidev.workspace.data.debug.DebugLogManager
import com.omnidev.workspace.data.ipc.ExtensionConnectionManager
import com.omnidev.workspace.data.ipc.LauncherConnectionManager
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.tools.CausalChainPlannerTool
import com.omnidev.workspace.data.tools.HeadlessBrowserManager
import com.omnidev.workspace.data.tools.ProgressiveTrustTool
import com.omnidev.workspace.data.tools.ScriptRunnerTool
import com.omnidev.workspace.data.tools.EnvironmentSetupManager
import com.omnidev.workspace.data.tools.ToolDownloaderEngine
import com.omnidev.workspace.data.tools.ml.ToolMachineLearningEngine
import com.omnidev.workspace.data.tools.monitoring.ToolMonitoringSystem
import com.omnidev.workspace.data.tools.orchestration.ToolIntelligenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * OmniDev Workspace Application class.
 * Initializes application-wide dependencies and services.
 *
 * System and domain documentation note System and domain documentation note System and domain documentation note:
 * - SmartLearningBridge: System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note
 * - ToolExecutionJournal: System and domain documentation note System and domain documentation note System and domain documentation note
 * - ToolAwarenessEngine: System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note
 */
class OmniDevApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ─── System and domain documentation note System and domain documentation note System and domain documentation note (Agent Brain) ────────────────────────────

    /** System and domain documentation note System and domain documentation note System and domain documentation note — System and domain documentation note System and domain documentation note */
    lateinit var toolExecutionJournal: ToolExecutionJournal
        private set

    /** System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note */
    lateinit var toolAwarenessEngine: ToolAwarenessEngine
        private set


    /** System and domain documentation note System and domain documentation note MCP (System and domain documentation note System and domain documentation note) */
    lateinit var mcpRegistry: McpRegistry
        private set

    /** System and domain documentation note System and domain documentation note System and domain documentation note — System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note */

    lateinit var smartLearningBridge: SmartLearningBridge
        private set

    // ═══════════════════════════════════════════════════════════════════════════
    // 🧠 Agent Brain 2.0 + Action Insurance + Live Repo Context + Build Doctor Pro
    //    (mobile-first: hash embeddings, regex parsing, Deflate diffs — all on
    //     low-end Android with 2-4 GB RAM, no native libs, no extra LLM calls).
    // ═══════════════════════════════════════════════════════════════════════════

    /** System and domain documentation note Reflexion — System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note Agent. */
    lateinit var reflexionEngine: ReflexionEngine
        private set

    /** System and domain documentation note System and domain documentation note System and domain documentation note (Episodic Memory). */
    lateinit var episodicMemoryStore: EpisodicMemoryStore
        private set

    /** System and domain documentation note System and domain documentation note — Action Insurance. */
    lateinit var rollbackManager: RollbackManager
        private set

    /** System and domain documentation note System and domain documentation note System and domain documentation note (incremental). */
    lateinit var repoIndexer: RepoIndexer
        private set

    /** System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note retrieval. */
    lateinit var repoContextEngine: RepoContextEngine
        private set

    /** Build Doctor Pro — System and domain documentation note + System and domain documentation note System and domain documentation note System and domain documentation note. */
    lateinit var buildDoctorPro: BuildDoctorPro
        private set

    /** System and domain documentation note System and domain documentation note System and domain documentation note — System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note. */
    lateinit var causalChainPlannerTool: CausalChainPlannerTool
        private set

    /** System and domain documentation note System and domain documentation note System and domain documentation note — System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note. */
    lateinit var progressiveTrustEngine: ProgressiveTrustEngine
        private set

    /** System and domain documentation note System and domain documentation note System and domain documentation note — System and domain documentation note System and domain documentation note System and domain documentation note Agent System and domain documentation note System and domain documentation note System and domain documentation note. */
    lateinit var headlessBrowserManager: HeadlessBrowserManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // ═══════════════════════════════════════════════════════════════════════════
        // 🎛️ Install the tier policy FIRST — every privileged path after this
        //     consults TierPolicyHolder.current to know what is permitted in this
        //     build variant (lite / norm / pro / oem). Exactly one flavor source
        //     set provides the `TierPolicyBootstrap` object for the active variant.
        // ═══════════════════════════════════════════════════════════════════════════
        TierPolicyBootstrap.install()
        Log.i("OmniDevApp", "🎛️ TierPolicy installed: tier=${TierPolicyHolder.current.tier} " +
            "root=${TierPolicyHolder.current.allowRoot} shizuku=${TierPolicyHolder.current.allowShizuku} " +
            "a11y=${TierPolicyHolder.current.allowAccessibility} autoApprove=${TierPolicyHolder.current.autoApproveConfirmations}")

        // Initialise the debug log directory before installing the crash handler
        // so that the first crash can be written to disk immediately.
        DebugLogManager.init(applicationContext)
        CrashHandler.install()

        // Initialise PrivilegedExecutionManager with application context.
        PrivilegedExecutionManager.init(applicationContext)

        // Install the tier-specific PrivilegedExecutionFacade. Must come AFTER
        // PrivilegedExecutionManager.init() because the Pro bootstrap wraps it.
        // Lite/Norm install a denying facade; Pro wraps PrivilegedExecutionManager;
        // OEM installs a Runtime.exec() system-uid facade.
        PrivilegedExecutionFacadeBootstrap.install()
        Log.i("OmniDevApp", "🔒 PrivilegedExecutionFacade installed (available=${PrivilegedExecutionFacadeHolder.current.isAvailable()})")
        EnvironmentSetupManager.init(applicationContext)
        ToolDownloaderEngine.init(applicationContext)

        // Initialize the shared headless browser manager (used by agent + browser viewer UI)
        headlessBrowserManager = HeadlessBrowserManager(applicationContext)

        // Initialize universal launcher IPC binding manager
        LauncherConnectionManager.initialize(applicationContext)

        // Initialize Omni-Link extension discovery
        ExtensionConnectionManager.initialize(applicationContext)

        // ═══════════════════════════════════════════════════════════════
        // 🧠 System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note (Agent Brain System)
        // ═══════════════════════════════════════════════════════════════
        initializeAgentBrainSystem()

        // Restore dynamic Copilot models from the persisted cache
        restoreCopilotModelsAsync()
    }

    /**
     * System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note Agent System and domain documentation note
     * System and domain documentation note: System and domain documentation note System and domain documentation note + System and domain documentation note System and domain documentation note + System and domain documentation note System and domain documentation note
     */
    private fun initializeAgentBrainSystem() {
        try {
            val db = OmniDevDatabase.getInstance(applicationContext)

            // 1. System and domain documentation note System and domain documentation note System and domain documentation note
            toolExecutionJournal = ToolExecutionJournal(
                dao = db.toolExecutionDao(),
                scope = appScope
            )

            // 2. System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note
            toolAwarenessEngine = ToolAwarenessEngine(
                context = applicationContext,
                systemKnowledgeDao = db.systemKnowledgeDao(),
                scope = appScope
            )

            // 3. System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note
            val intelligenceEngine = ToolIntelligenceEngine(applicationContext, appScope)
            val mlEngine = ToolMachineLearningEngine(applicationContext)
            val monitoringSystem = ToolMonitoringSystem


            // 4. System and domain documentation note System and domain documentation note MCP
            val mcpConfigManager = McpConfigManager(applicationContext)
            mcpRegistry = McpRegistry(mcpConfigManager)

            // 5. System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note Progressive Trust Engine
            val trustEngine = ProgressiveTrustEngine(applicationContext)
            progressiveTrustEngine = trustEngine

            // 6f. Causal Chain Planner — System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note (in-memory, no DB)
            //     System and domain documentation note System and domain documentation note SmartLearningBridge System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note dependency
            //     50 node max per graph — mobile-safe (~50 KB peak)
            causalChainPlannerTool = CausalChainPlannerTool(CausalChainPlanner(maxNodes = 50))

            smartLearningBridge = SmartLearningBridge(

                context = applicationContext,
                journal = toolExecutionJournal,
                awarenessEngine = toolAwarenessEngine,
                intelligenceEngine = intelligenceEngine,
                mlEngine = mlEngine,
                monitoringSystem = monitoringSystem,
                progressiveTrustEngine = trustEngine,
                causalChainPlannerTool = causalChainPlannerTool,
                scope = appScope
            )

            // ═══════════════════════════════════════════════════════════════
            // 🧠 6. Agent Brain 2.0 + Action Insurance + Repo Context + Build Doctor
            // ═══════════════════════════════════════════════════════════════

            // 6a. Reflexion — System and domain documentation note System and domain documentation note System and domain documentation note (rule-based, on-device)
            reflexionEngine = ReflexionEngine(
                dao = db.reflexionDao(),
                maxLessons = 2000,           // ~2 MB System and domain documentation note DB
                topKForInjection = 3,        // 3 System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note prompt (System and domain documentation note System and domain documentation note tokens)
                scope = appScope
            )

            // 6b. Episodic Memory — System and domain documentation note System and domain documentation note System and domain documentation note
            episodicMemoryStore = EpisodicMemoryStore(
                dao = db.episodicMemoryDao(),
                maxEpisodes = 2000,
                scope = appScope
            )

            // 6c. Rollback Manager — Action Insurance
            //     50 MB max storageSystem and domain documentation note 200 snapshot maxSystem and domain documentation note diff-based System and domain documentation note System and domain documentation note
            rollbackManager = RollbackManager(
                dao = db.rollbackDao(),
                maxSnapshotsPerGroup = 200,
                maxBytesEvictable = 50L * 1024 * 1024
            )

            // 6d. Repo Indexer + Context Engine — Live Repository Context
            //     time budget 30s System and domain documentation note passSystem and domain documentation note System and domain documentation note incremental
            repoIndexer = RepoIndexer(
                dao = db.repoIndexDao(),
                maxFileSizeBytes = 500L * 1024,
                maxSymbolsPerScope = 5000,
                chunkSize = 50
            )
            repoContextEngine = RepoContextEngine(dao = db.repoIndexDao(), indexer = repoIndexer)

            // 6e. Build Doctor Pro — System and domain documentation note + System and domain documentation note System and domain documentation note
            buildDoctorPro = BuildDoctorPro(
                dao = db.buildDiagnosticDao(),
                maxEntries = 500
            )

            // 7. System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note (System and domain documentation note System and domain documentation note + System and domain documentation note System and domain documentation note)
            appScope.launch {
                try {
                    toolAwarenessEngine.initialize()
                    smartLearningBridge.onSessionStart()
                    val lessons = db.reflexionDao().count()
                    val episodes = db.episodicMemoryDao().count()
                    val diagnostics = db.buildDiagnosticDao().count()
                    Log.i("OmniDevApp",
                        "✅ Agent Brain 2.0 System component status  •  System component status=$lessons  System component status=$episodes  System component status-System component status=$diagnostics")
                } catch (e: Exception) {
                    Log.e("OmniDevApp", "⚠️ System component status System component status System component status Agent Brain: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.e("OmniDevApp", "❌ System component status System component status System component status Agent Brain System: ${e.message}")
            // System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note System and domain documentation note
            val db = OmniDevDatabase.getInstance(applicationContext)

            toolExecutionJournal = ToolExecutionJournal(db.toolExecutionDao())
            toolAwarenessEngine = ToolAwarenessEngine(applicationContext, db.systemKnowledgeDao())
            mcpRegistry = McpRegistry(McpConfigManager(applicationContext))

            // System and domain documentation note ProgressiveTrustEngine System and domain documentation note SmartLearningBridge System and domain documentation note System and domain documentation note System and domain documentation note
            val fallbackTrustEngine = ProgressiveTrustEngine(applicationContext)
            progressiveTrustEngine = fallbackTrustEngine

            // System and domain documentation note CausalChainPlannerTool System and domain documentation note SmartLearningBridge System and domain documentation note System and domain documentation note System and domain documentation note
            val fallbackCausalTool = CausalChainPlannerTool(CausalChainPlanner())
            causalChainPlannerTool = fallbackCausalTool

            smartLearningBridge = SmartLearningBridge(
                context = applicationContext,
                journal = toolExecutionJournal,
                awarenessEngine = toolAwarenessEngine,
                intelligenceEngine = null,
                mlEngine = null,
                monitoringSystem = null,
                progressiveTrustEngine = fallbackTrustEngine,
                causalChainPlannerTool = fallbackCausalTool
            )

            // ── Fallback initialization for Agent Brain 2.0 stack ──
            // Best-effort: if DB fails entirely we still create stubs so callers
            // don't crash on `lateinit` access.
            reflexionEngine = ReflexionEngine(dao = db.reflexionDao(), scope = appScope)
            episodicMemoryStore = EpisodicMemoryStore(dao = db.episodicMemoryDao(), scope = appScope)
            rollbackManager = RollbackManager(dao = db.rollbackDao())
            repoIndexer = RepoIndexer(dao = db.repoIndexDao())
            repoContextEngine = RepoContextEngine(dao = db.repoIndexDao(), indexer = repoIndexer)
            buildDoctorPro = BuildDoctorPro(dao = db.buildDiagnosticDao())
        }
    }

    /**
     * If the user previously connected with GitHub Copilot, re-fetch the available
     * models in the background and inject them into [ModelRegistry].
     */
    private fun restoreCopilotModelsAsync() {
        appScope.launch {
            try {
                val apiKeyRepo = com.omnidev.workspace.data.repository.ApiKeyRepository(applicationContext)
                val oauthToken = apiKeyRepo.getApiKey(ModelProvider.GITHUB_COPILOT)
                if (!oauthToken.isNullOrBlank()) {
                    CopilotModelRefresher.refreshModels(oauthToken)
                }
            } catch (e: Exception) {
                // Startup restoration is best-effort — never crash the app.
                Log.w("OmniDevApp", "Copilot model restore failed on startup: ${e.message}")
            }
        }
    }

    companion object {
        /** Application singleton for accessing context where DI isn't available. */
        lateinit var instance: OmniDevApp
            private set
    }
}
