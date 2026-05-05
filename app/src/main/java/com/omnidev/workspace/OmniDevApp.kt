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
 * النظام الجديد يتضمن:
 * - SmartLearningBridge: الجسر المنسق للتعلم والذاكرة
 * - ToolExecutionJournal: سجل التنفيذ الدائم
 * - ToolAwarenessEngine: محرك الوعي بالأدوات والبيئة
 */
class OmniDevApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ─── مكونات النظام الذكي (Agent Brain) ────────────────────────────

    /** مجلة تنفيذ الأدوات — الذاكرة الدائمة */
    lateinit var toolExecutionJournal: ToolExecutionJournal
        private set

    /** محرك الوعي بالأدوات والنظام */
    lateinit var toolAwarenessEngine: ToolAwarenessEngine
        private set


    /** سجل أدوات MCP (الخوادم الخارجية) */
    lateinit var mcpRegistry: McpRegistry
        private set

    /** الجسر الذكي المنسق — يربط كل مكونات الذكاء */

    lateinit var smartLearningBridge: SmartLearningBridge
        private set

    // ═══════════════════════════════════════════════════════════════════════════
    // 🧠 Agent Brain 2.0 + Action Insurance + Live Repo Context + Build Doctor Pro
    //    (mobile-first: hash embeddings, regex parsing, Deflate diffs — all on
    //     low-end Android with 2-4 GB RAM, no native libs, no extra LLM calls).
    // ═══════════════════════════════════════════════════════════════════════════

    /** محرك Reflexion — دروس مستفادة من تجارب الـ Agent. */
    lateinit var reflexionEngine: ReflexionEngine
        private set

    /** ذاكرة المهام الكاملة (Episodic Memory). */
    lateinit var episodicMemoryStore: EpisodicMemoryStore
        private set

    /** نظام التراجع — Action Insurance. */
    lateinit var rollbackManager: RollbackManager
        private set

    /** فهرس المستودع المحلي (incremental). */
    lateinit var repoIndexer: RepoIndexer
        private set

    /** محرك سياق المستودع للـ retrieval. */
    lateinit var repoContextEngine: RepoContextEngine
        private set

    /** Build Doctor Pro — تشخيص + ذاكرة حلول البناء. */
    lateinit var buildDoctorPro: BuildDoctorPro
        private set

    /** محرك التخطيط السببي — تحليل سلاسل الأوامر قبل تنفيذها. */
    lateinit var causalChainPlannerTool: CausalChainPlannerTool
        private set

    /** محرك الثقة التدريجية — يتتبّع الثقة ويمنح الصلاحيات. */
    lateinit var progressiveTrustEngine: ProgressiveTrustEngine
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

        // Initialize universal launcher IPC binding manager
        LauncherConnectionManager.initialize(applicationContext)

        // Initialize Omni-Link extension discovery
        ExtensionConnectionManager.initialize(applicationContext)

        // ═══════════════════════════════════════════════════════════════
        // 🧠 تهيئة نظام الذاكرة والوعي الذكي (Agent Brain System)
        // ═══════════════════════════════════════════════════════════════
        initializeAgentBrainSystem()

        // Restore dynamic Copilot models from the persisted cache
        restoreCopilotModelsAsync()
    }

    /**
     * تهيئة نظام عقل الـ Agent الكامل
     * يتضمن: ذاكرة التنفيذ + الوعي بالأدوات + نظام التعلم
     */
    private fun initializeAgentBrainSystem() {
        try {
            val db = OmniDevDatabase.getInstance(applicationContext)

            // 1. إنشاء مجلة التنفيذ
            toolExecutionJournal = ToolExecutionJournal(
                dao = db.toolExecutionDao(),
                scope = appScope
            )

            // 2. إنشاء محرك الوعي بالأدوات
            toolAwarenessEngine = ToolAwarenessEngine(
                context = applicationContext,
                systemKnowledgeDao = db.systemKnowledgeDao(),
                scope = appScope
            )

            // 3. إنشاء مكونات التعلم الذكي
            val intelligenceEngine = ToolIntelligenceEngine(applicationContext, appScope)
            val mlEngine = ToolMachineLearningEngine(applicationContext)
            val monitoringSystem = ToolMonitoringSystem


            // 4. إنشاء محرك MCP
            val mcpConfigManager = McpConfigManager(applicationContext)
            mcpRegistry = McpRegistry(mcpConfigManager)

            // 5. إنشاء الجسر الذكي المنسق مع Progressive Trust Engine
            val trustEngine = ProgressiveTrustEngine(applicationContext)
            progressiveTrustEngine = trustEngine

            // 6f. Causal Chain Planner — تحليل سلاسل الأوامر قبل تنفيذها (in-memory, no DB)
            //     مُبكَّر قبل SmartLearningBridge حتى يمكن تمريره كـ dependency
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

            // 6a. Reflexion — لقطات الدروس المستفادة (rule-based, on-device)
            reflexionEngine = ReflexionEngine(
                dao = db.reflexionDao(),
                maxLessons = 2000,           // ~2 MB في DB
                topKForInjection = 3,        // 3 دروس فقط في الـ prompt (موفر للـ tokens)
                scope = appScope
            )

            // 6b. Episodic Memory — حلقات المهام السابقة
            episodicMemoryStore = EpisodicMemoryStore(
                dao = db.episodicMemoryDao(),
                maxEpisodes = 2000,
                scope = appScope
            )

            // 6c. Rollback Manager — Action Insurance
            //     50 MB max storage، 200 snapshot max، diff-based للملفات الكبيرة
            rollbackManager = RollbackManager(
                dao = db.rollbackDao(),
                maxSnapshotsPerGroup = 200,
                maxBytesEvictable = 50L * 1024 * 1024
            )

            // 6d. Repo Indexer + Context Engine — Live Repository Context
            //     time budget 30s لكل pass، يعمل incremental
            repoIndexer = RepoIndexer(
                dao = db.repoIndexDao(),
                maxFileSizeBytes = 500L * 1024,
                maxSymbolsPerScope = 5000,
                chunkSize = 50
            )
            repoContextEngine = RepoContextEngine(dao = db.repoIndexDao(), indexer = repoIndexer)

            // 6e. Build Doctor Pro — تشخيص + ذاكرة حلول
            buildDoctorPro = BuildDoctorPro(
                dao = db.buildDiagnosticDao(),
                maxEntries = 500
            )

            // 7. تهيئة النظام في الخلفية (اكتشاف البيئة + إحصاءات الذاكرة)
            appScope.launch {
                try {
                    toolAwarenessEngine.initialize()
                    smartLearningBridge.onSessionStart()
                    val lessons = db.reflexionDao().count()
                    val episodes = db.episodicMemoryDao().count()
                    val diagnostics = db.buildDiagnosticDao().count()
                    Log.i("OmniDevApp",
                        "✅ Agent Brain 2.0 جاهز  •  دروس=$lessons  حلقات=$episodes  حلول-بناء=$diagnostics")
                } catch (e: Exception) {
                    Log.e("OmniDevApp", "⚠️ خطأ في تهيئة Agent Brain: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.e("OmniDevApp", "❌ فشل في تهيئة Agent Brain System: ${e.message}")
            // إنشاء نسخ طوارئ حتى لا يتعطل التطبيق
            val db = OmniDevDatabase.getInstance(applicationContext)

            toolExecutionJournal = ToolExecutionJournal(db.toolExecutionDao())
            toolAwarenessEngine = ToolAwarenessEngine(applicationContext, db.systemKnowledgeDao())
            mcpRegistry = McpRegistry(McpConfigManager(applicationContext))

            // إنشاء ProgressiveTrustEngine قبل SmartLearningBridge حتى يمكن تمريره
            val fallbackTrustEngine = ProgressiveTrustEngine(applicationContext)
            progressiveTrustEngine = fallbackTrustEngine

            // إنشاء CausalChainPlannerTool قبل SmartLearningBridge حتى يمكن تمريره
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
