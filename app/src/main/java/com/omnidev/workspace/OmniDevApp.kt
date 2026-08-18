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
 * [Localized] [Localized] [Localized]:
 * - SmartLearningBridge: [Localized] [Localized] [Localized] [Localized]
 * - ToolExecutionJournal: [Localized] [Localized] [Localized]
 * - ToolAwarenessEngine: [Localized] [Localized] [Localized] [Localized]
 */
class OmniDevApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ─── [Localized] [Localized] [Localized] (Agent Brain) ────────────────────────────

    /** [Localized] [Localized] [Localized] — [Localized] [Localized] */
    lateinit var toolExecutionJournal: ToolExecutionJournal
        private set

    /** [Localized] [Localized] [Localized] [Localized] */
    lateinit var toolAwarenessEngine: ToolAwarenessEngine
        private set


    /** [Localized] [Localized] MCP ([Localized] [Localized]) */
    lateinit var mcpRegistry: McpRegistry
        private set

    /** [Localized] [Localized] [Localized] — [Localized] [Localized] [Localized] [Localized] */

    lateinit var smartLearningBridge: SmartLearningBridge
        private set

    // ═══════════════════════════════════════════════════════════════════════════
    // 🧠 Agent Brain 2.0 + Action Insurance + Live Repo Context + Build Doctor Pro
    //    (mobile-first: hash embeddings, regex parsing, Deflate diffs — all on
    //     low-end Android with 2-4 GB RAM, no native libs, no extra LLM calls).
    // ═══════════════════════════════════════════════════════════════════════════

    /** [Localized] Reflexion — [Localized] [Localized] [Localized] [Localized] [Localized] Agent. */
    lateinit var reflexionEngine: ReflexionEngine
        private set

    /** [Localized] [Localized] [Localized] (Episodic Memory). */
    lateinit var episodicMemoryStore: EpisodicMemoryStore
        private set

    /** [Localized] [Localized] — Action Insurance. */
    lateinit var rollbackManager: RollbackManager
        private set

    /** [Localized] [Localized] [Localized] (incremental). */
    lateinit var repoIndexer: RepoIndexer
        private set

    /** [Localized] [Localized] [Localized] [Localized] retrieval. */
    lateinit var repoContextEngine: RepoContextEngine
        private set

    /** Build Doctor Pro — [Localized] + [Localized] [Localized] [Localized]. */
    lateinit var buildDoctorPro: BuildDoctorPro
        private set

    /** [Localized] [Localized] [Localized] — [Localized] [Localized] [Localized] [Localized] [Localized]. */
    lateinit var causalChainPlannerTool: CausalChainPlannerTool
        private set

    /** [Localized] [Localized] [Localized] — [Localized] [Localized] [Localized] [Localized]. */
    lateinit var progressiveTrustEngine: ProgressiveTrustEngine
        private set

    /** [Localized] [Localized] [Localized] — [Localized] [Localized] [Localized] Agent [Localized] [Localized] [Localized]. */
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
        // 🧠 [Localized] [Localized] [Localized] [Localized] [Localized] (Agent Brain System)
        // ═══════════════════════════════════════════════════════════════
        initializeAgentBrainSystem()

        // Restore dynamic Copilot models from the persisted cache
        restoreCopilotModelsAsync()
    }

    /**
     * [Localized] [Localized] [Localized] [Localized] Agent [Localized]
     * [Localized]: [Localized] [Localized] + [Localized] [Localized] + [Localized] [Localized]
     */
    private fun initializeAgentBrainSystem() {
        try {
            val db = OmniDevDatabase.getInstance(applicationContext)

            // 1. [Localized] [Localized] [Localized]
            toolExecutionJournal = ToolExecutionJournal(
                dao = db.toolExecutionDao(),
                scope = appScope
            )

            // 2. [Localized] [Localized] [Localized] [Localized]
            toolAwarenessEngine = ToolAwarenessEngine(
                context = applicationContext,
                systemKnowledgeDao = db.systemKnowledgeDao(),
                scope = appScope
            )

            // 3. [Localized] [Localized] [Localized] [Localized]
            val intelligenceEngine = ToolIntelligenceEngine(applicationContext, appScope)
            val mlEngine = ToolMachineLearningEngine(applicationContext)
            val monitoringSystem = ToolMonitoringSystem


            // 4. [Localized] [Localized] MCP
            val mcpConfigManager = McpConfigManager(applicationContext)
            mcpRegistry = McpRegistry(mcpConfigManager)

            // 5. [Localized] [Localized] [Localized] [Localized] [Localized] Progressive Trust Engine
            val trustEngine = ProgressiveTrustEngine(applicationContext)
            progressiveTrustEngine = trustEngine

            // 6f. Causal Chain Planner — [Localized] [Localized] [Localized] [Localized] [Localized] (in-memory, no DB)
            //     [Localized] [Localized] SmartLearningBridge [Localized] [Localized] [Localized] [Localized] dependency
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

            // 6a. Reflexion — [Localized] [Localized] [Localized] (rule-based, on-device)
            reflexionEngine = ReflexionEngine(
                dao = db.reflexionDao(),
                maxLessons = 2000,           // ~2 MB [Localized] DB
                topKForInjection = 3,        // 3 [Localized] [Localized] [Localized] [Localized] prompt ([Localized] [Localized] tokens)
                scope = appScope
            )

            // 6b. Episodic Memory — [Localized] [Localized] [Localized]
            episodicMemoryStore = EpisodicMemoryStore(
                dao = db.episodicMemoryDao(),
                maxEpisodes = 2000,
                scope = appScope
            )

            // 6c. Rollback Manager — Action Insurance
            //     50 MB max storage[Localized] 200 snapshot max[Localized] diff-based [Localized] [Localized]
            rollbackManager = RollbackManager(
                dao = db.rollbackDao(),
                maxSnapshotsPerGroup = 200,
                maxBytesEvictable = 50L * 1024 * 1024
            )

            // 6d. Repo Indexer + Context Engine — Live Repository Context
            //     time budget 30s [Localized] pass[Localized] [Localized] incremental
            repoIndexer = RepoIndexer(
                dao = db.repoIndexDao(),
                maxFileSizeBytes = 500L * 1024,
                maxSymbolsPerScope = 5000,
                chunkSize = 50
            )
            repoContextEngine = RepoContextEngine(dao = db.repoIndexDao(), indexer = repoIndexer)

            // 6e. Build Doctor Pro — [Localized] + [Localized] [Localized]
            buildDoctorPro = BuildDoctorPro(
                dao = db.buildDiagnosticDao(),
                maxEntries = 500
            )

            // 7. [Localized] [Localized] [Localized] [Localized] ([Localized] [Localized] + [Localized] [Localized])
            appScope.launch {
                try {
                    toolAwarenessEngine.initialize()
                    smartLearningBridge.onSessionStart()
                    val lessons = db.reflexionDao().count()
                    val episodes = db.episodicMemoryDao().count()
                    val diagnostics = db.buildDiagnosticDao().count()
                    Log.i("OmniDevApp",
                        "✅ Agent Brain 2.0 [Localized]  •  [Localized]=$lessons  [Localized]=$episodes  [Localized]-[Localized]=$diagnostics")
                } catch (e: Exception) {
                    Log.e("OmniDevApp", "⚠️ [Localized] [Localized] [Localized] Agent Brain: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.e("OmniDevApp", "❌ [Localized] [Localized] [Localized] Agent Brain System: ${e.message}")
            // [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]
            val db = OmniDevDatabase.getInstance(applicationContext)

            toolExecutionJournal = ToolExecutionJournal(db.toolExecutionDao())
            toolAwarenessEngine = ToolAwarenessEngine(applicationContext, db.systemKnowledgeDao())
            mcpRegistry = McpRegistry(McpConfigManager(applicationContext))

            // [Localized] ProgressiveTrustEngine [Localized] SmartLearningBridge [Localized] [Localized] [Localized]
            val fallbackTrustEngine = ProgressiveTrustEngine(applicationContext)
            progressiveTrustEngine = fallbackTrustEngine

            // [Localized] CausalChainPlannerTool [Localized] SmartLearningBridge [Localized] [Localized] [Localized]
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
