package com.omnidev.workspace

import android.app.Application
import android.util.Log
import com.omnidev.workspace.core.policy.TierPolicyBootstrap
import com.omnidev.workspace.core.policy.TierPolicyHolder
import com.omnidev.workspace.core.privileged.PrivilegedExecutionFacadeBootstrap
import com.omnidev.workspace.core.privileged.PrivilegedExecutionFacadeHolder
import com.omnidev.workspace.data.auth.CopilotModelRefresher
import com.omnidev.workspace.data.brain.SmartLearningBridge
import com.omnidev.workspace.data.mcp.McpConfigManager
import com.omnidev.workspace.data.mcp.McpRegistry
import com.omnidev.workspace.data.brain.ToolAwarenessEngine
import com.omnidev.workspace.data.brain.ToolExecutionJournal
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.debug.CrashHandler
import com.omnidev.workspace.data.debug.DebugLogManager
import com.omnidev.workspace.data.ipc.ExtensionConnectionManager
import com.omnidev.workspace.data.ipc.LauncherConnectionManager
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import com.omnidev.workspace.data.model.ModelProvider
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

            // 5. إنشاء الجسر الذكي المنسق
            smartLearningBridge = SmartLearningBridge(

                context = applicationContext,
                journal = toolExecutionJournal,
                awarenessEngine = toolAwarenessEngine,
                intelligenceEngine = intelligenceEngine,
                mlEngine = mlEngine,
                monitoringSystem = monitoringSystem,
                scope = appScope
            )

            // 5. تهيئة النظام في الخلفية (اكتشاف البيئة)
            appScope.launch {
                try {
                    toolAwarenessEngine.initialize()
                    smartLearningBridge.onSessionStart()
                    Log.i("OmniDevApp", "✅ Agent Brain System تم تهيئته بنجاح")
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
            smartLearningBridge = SmartLearningBridge(

                context = applicationContext,
                journal = toolExecutionJournal,
                awarenessEngine = toolAwarenessEngine,
                intelligenceEngine = null,
                mlEngine = null,
                monitoringSystem = null
            )
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
