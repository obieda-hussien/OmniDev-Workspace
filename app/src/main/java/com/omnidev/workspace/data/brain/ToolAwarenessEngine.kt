package com.omnidev.workspace.data.brain

import android.content.Context
import android.os.Build
import android.util.Log
import com.omnidev.workspace.data.db.dao.SystemKnowledgeDao
import com.omnidev.workspace.data.db.entities.SystemKnowledgeEntry
import com.omnidev.workspace.data.tools.EnvironmentSetupManager
import com.omnidev.workspace.data.tools.ShizukuCommandTool
import com.omnidev.workspace.data.tools.TermuxRunCommandBridge
import com.omnidev.workspace.data.tools.ToolDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Learns the execution environment that the agent can actually use.
 *
 * Runtime discovery must go through the same execution layer as the agent. In
 * particular, checking `/data/data/com.termux/...` with java.io.File from the
 * OmniDev process is invalid on modern Android because Termux is a different app
 * sandbox. The previous implementation therefore taught the model false runtime
 * availability and stale tool names such as `termux_bridge`.
 */
class ToolAwarenessEngine(
    private val context: Context,
    private val systemKnowledgeDao: SystemKnowledgeDao,
    @Suppress("UNUSED_PARAMETER")
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    companion object {
        private const val TAG = "ToolAwareness"

        const val TYPE_TOOL_CAPABILITY = "TOOL_CAPABILITY"
        const val TYPE_TOOL_REQUIREMENT = "TOOL_REQUIREMENT"
        const val TYPE_TOOL_LIMITATION = "TOOL_LIMITATION"
        const val TYPE_TOOL_DEPENDENCY = "TOOL_DEPENDENCY"
        const val TYPE_SYSTEM_INFO = "SYSTEM_INFO"
        const val TYPE_SYSTEM_CAPABILITY = "SYSTEM_CAPABILITY"
        const val TYPE_PATTERN = "PATTERN"
        const val TYPE_WARNING = "WARNING"
        const val TYPE_BEST_PRACTICE = "BEST_PRACTICE"
        const val TYPE_ENVIRONMENT = "ENVIRONMENT"
    }

    private val runtimeEnvironmentCache = mutableMapOf<String, String>()
    private var isInitialized = false
    private val initializationMutex = Mutex()

    suspend fun initialize(availableTools: List<ToolDefinition> = emptyList()) =
        withContext(Dispatchers.IO) {
            initializationMutex.withLock {
                if (isInitialized) return@withLock

                discoverSystemEnvironment()
                if (availableTools.isNotEmpty()) registerToolCapabilities(availableTools)
                discoverDeviceCapabilities()
                discoverRuntimeEnvironments()
                registerInitialBestPractices()

                isInitialized = true
                Log.d(TAG, "Tool awareness initialized: ${systemKnowledgeDao.getCount()} entries")
            }
        }

    private suspend fun discoverSystemEnvironment() {
        val deviceInfo = buildString {
            appendLine("Android SDK: ${Build.VERSION.SDK_INT}")
            appendLine("Android: ${Build.VERSION.RELEASE}")
            appendLine("Model: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("CPU ABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
        }
        saveOrUpdateKnowledge(
            TYPE_SYSTEM_INFO,
            "device_info",
            deviceInfo,
            priority = 5,
            tags = "android,device,sdk,system"
        )

        val scopedStorageNote = when {
            Build.VERSION.SDK_INT >= 33 ->
                "Android 13+ (API ${Build.VERSION.SDK_INT}); scoped storage and runtime media/notification permissions apply."
            Build.VERSION.SDK_INT >= 30 ->
                "Android 11+ (API ${Build.VERSION.SDK_INT}); scoped storage applies."
            else -> "Android API ${Build.VERSION.SDK_INT}."
        }
        saveOrUpdateKnowledge(
            TYPE_SYSTEM_INFO,
            "android_api",
            scopedStorageNote,
            priority = 3,
            tags = "android,api,storage"
        )
    }

    private suspend fun discoverDeviceCapabilities() {
        val pm = context.packageManager
        if (pm.hasSystemFeature("android.hardware.camera")) {
            saveOrUpdateKnowledge(
                TYPE_SYSTEM_CAPABILITY,
                "camera",
                "Camera hardware detected; permission must be checked before use.",
                priority = 8
            )
        }
        if (pm.hasSystemFeature("android.hardware.bluetooth")) {
            saveOrUpdateKnowledge(
                TYPE_SYSTEM_CAPABILITY,
                "bluetooth",
                "Bluetooth hardware detected; permission and adapter state must be checked before use.",
                priority = 8
            )
        }

        val runtime = Runtime.getRuntime()
        saveOrUpdateKnowledge(
            TYPE_SYSTEM_INFO,
            "memory",
            "App JVM max heap: ${runtime.maxMemory() / (1024 * 1024)} MB. Prefer streaming for large output/files.",
            priority = 4,
            tags = "memory,performance,heap"
        )

        runCatching {
            val dataDir = context.filesDir
            saveOrUpdateKnowledge(
                TYPE_SYSTEM_INFO,
                "storage",
                "App data filesystem: ${dataDir.freeSpace / (1024 * 1024)} MB free of ${dataDir.totalSpace / (1024 * 1024)} MB.",
                priority = 5,
                tags = "storage,disk,space"
            )
        }
    }

    private suspend fun discoverRuntimeEnvironments() {
        EnvironmentSetupManager.init(context.applicationContext)
        TermuxRunCommandBridge.init(context.applicationContext)

        val state = runCatching { EnvironmentSetupManager.probe(force = true) }.getOrNull()
        val termuxInstalled = TermuxRunCommandBridge.isTermuxInstalled(context)
        val termuxPermission = TermuxRunCommandBridge.hasRunCommandPermission(context)
        val termuxReady =
            state?.termuxPrefix != null && !TermuxRunCommandBridge.isKnownUnusable()

        runtimeEnvironmentCache["termux_installed"] = termuxInstalled.toString()
        runtimeEnvironmentCache["termux_permission"] = termuxPermission.toString()
        runtimeEnvironmentCache["termux_ready"] = termuxReady.toString()

        val termuxKnowledgeType = when {
            termuxReady -> TYPE_ENVIRONMENT
            else -> TYPE_WARNING
        }
        systemKnowledgeDao.invalidateOtherTypesForSubject(
            subject = "termux",
            source = "auto_discovery",
            keepType = termuxKnowledgeType
        )

        when {
            termuxReady -> saveOrUpdateKnowledge(
                TYPE_ENVIRONMENT,
                "termux",
                "Termux RunCommandService is ready. Use agent_runtime for shell, pkg/apt, Python, Node.js, npm, pip and CLI work. Do not use legacy termux_bridge/direct_terminal aliases.",
                confidence = 1.0f,
                priority = 2,
                tags = "termux,agent_runtime,python,nodejs,bash,linux"
            )
            termuxInstalled && !termuxPermission -> saveOrUpdateKnowledge(
                TYPE_WARNING,
                "termux",
                "Termux is installed but OmniDev lacks com.termux.permission.RUN_COMMAND. Grant the additional permission and set allow-external-apps=true in ~/.termux/termux.properties.",
                confidence = 1.0f,
                priority = 2,
                tags = "termux,permission,run_command,warning"
            )
            termuxInstalled -> saveOrUpdateKnowledge(
                TYPE_WARNING,
                "termux",
                "Termux is installed but its RunCommand transport is not healthy. Treat this as a circuit-breaker state: do not retry agent_runtime shell/package work until execution_diagnostics action=fix_termux succeeds.",
                confidence = 1.0f,
                priority = 2,
                tags = "termux,runtime,warning"
            )
            else -> saveOrUpdateKnowledge(
                TYPE_WARNING,
                "termux",
                "Termux is not installed. Developer package/runtime commands are unavailable until official Termux + RUN_COMMAND are configured.",
                confidence = 1.0f,
                priority = 3,
                tags = "termux,warning"
            )
        }

        val shizukuBinder = ShizukuCommandTool.isAvailable()
        val shizukuGranted = shizukuBinder && ShizukuCommandTool.hasPermission()
        val shizukuUid = if (shizukuGranted) ShizukuCommandTool.privilegedUidOrNull() else null
        runtimeEnvironmentCache["shizuku"] = shizukuGranted.toString()

        val shizukuKnowledgeType = if (shizukuGranted) TYPE_ENVIRONMENT else TYPE_WARNING
        systemKnowledgeDao.invalidateOtherTypesForSubject(
            subject = "shizuku",
            source = "auto_discovery",
            keepType = shizukuKnowledgeType
        )

        if (shizukuGranted) {
            saveOrUpdateKnowledge(
                TYPE_ENVIRONMENT,
                "shizuku",
                "Shizuku UserService is ready${shizukuUid?.let { " (uid=$it)" }.orEmpty()}. Prefer specialized device tools first; generic Android content/settings/pm/cmd work can use run_terminal which auto-routes to Shizuku. Keep agent_runtime for developer/Termux work only.",
                confidence = 1.0f,
                priority = 2,
                tags = "shizuku,user_service,adb,privileged"
            )
        } else {
            saveOrUpdateKnowledge(
                TYPE_WARNING,
                "shizuku",
                if (shizukuBinder) {
                    "Shizuku is running but permission is not granted to OmniDev."
                } else {
                    "Shizuku binder is not available."
                },
                confidence = 1.0f,
                priority = 3,
                tags = "shizuku,warning"
            )
        }

        val runtimes = state?.runtimes.orEmpty()
        runtimes["python3"]?.takeIf { it.available }
            ?: runtimes["python"]?.takeIf { it.available }
        if (runtimes["python3"]?.available == true || runtimes["python"]?.available == true) {
            val runtime = runtimes["python3"]?.takeIf { it.available } ?: runtimes["python"]
            saveOrUpdateKnowledge(
                TYPE_ENVIRONMENT,
                "python",
                "Python is available through Termux at ${runtime?.path}. Run it with agent_runtime action=python_run; install libraries with agent_runtime action=pip_install.",
                confidence = 1.0f,
                priority = 2,
                tags = "python,termux,agent_runtime"
            )
        }
        runtimes["node"]?.takeIf { it.available }?.let { runtime ->
            saveOrUpdateKnowledge(
                TYPE_ENVIRONMENT,
                "node",
                "Node.js is available through Termux at ${runtime.path}. Use agent_runtime for node/npm commands.",
                confidence = 1.0f,
                priority = 3,
                tags = "nodejs,npm,termux,agent_runtime"
            )
        }
        runtimes["git"]?.takeIf { it.available }?.let { runtime ->
            saveOrUpdateKnowledge(
                TYPE_ENVIRONMENT,
                "git",
                "Git is available through Termux at ${runtime.path}. Prefer git_manager for structured repository operations and agent_runtime for raw git CLI work.",
                confidence = 1.0f,
                priority = 3,
                tags = "git,termux,vcs"
            )
        }
    }

    private suspend fun registerToolCapabilities(tools: List<ToolDefinition>) {
        tools.distinctBy { it.name }.forEach { tool ->
            val parameters = tool.parameters.joinToString(", ") { p ->
                "${p.name}(${if (p.required) "required" else "optional"})"
            }
            saveOrUpdateKnowledge(
                TYPE_TOOL_CAPABILITY,
                tool.name,
                buildString {
                    append("Tool: ${tool.name} | ${tool.description.take(240)}")
                    if (parameters.isNotBlank()) append(" | parameters: $parameters")
                },
                priority = 6,
                tags = "tool,${tool.name},capability",
                source = "tool_registry"
            )
        }
        Log.d(TAG, "Registered ${tools.distinctBy { it.name }.size} visible tool capabilities")
    }

    private suspend fun registerInitialBestPractices() {
        val practices = listOf(
            Triple(
                "terminal_runtime_routing",
                "Prefer specialized domain tools first. Use agent_runtime only for developer shell/package/runtime work; generic Android content/settings/pm/cmd commands use the Shizuku domain (run_terminal auto-routes them); rish is only the explicit ADB-equivalent terminal backend. Never execute Termux private binaries through Shizuku PATH/LD_PRELOAD hacks.",
                "terminal,termux,shizuku,rish,routing"
            ),
            Triple(
                "package_installation",
                "Install Termux packages with agent_runtime action=pkg_install. Package installation is serialized and automatically attempts dpkg/dependency recovery before one retry.",
                "termux,packages,pkg,apt,recovery"
            ),
            Triple(
                "file_operations",
                "Prefer structured file tools for app/repository files and agent_runtime only when shell semantics are actually needed.",
                "file,terminal,performance"
            ),
            Triple(
                "memory_usage",
                "Search existing knowledge before storing duplicates; store durable discoveries after successful verification.",
                "memory,context,efficiency"
            ),
            Triple(
                "git_workflow",
                "Prefer git_manager for structured Git operations; use raw git CLI only when the structured tool lacks the required operation.",
                "git,workflow,best_practice"
            )
        )

        practices.forEach { (subject, content, tags) ->
            saveOrUpdateKnowledge(
                TYPE_BEST_PRACTICE,
                subject,
                content,
                priority = 2,
                tags = "best_practice,$tags",
                source = "built_in"
            )
        }
    }

    suspend fun learnFromExecution(
        toolName: String,
        success: Boolean,
        errorMessage: String = "",
        executionTimeMs: Long = 0,
        classification: String? = null,
        backend: String? = null,
        @Suppress("UNUSED_PARAMETER") params: Map<String, Any?> = emptyMap()
    ) = withContext(Dispatchers.IO) {
        updateRuntimeKnowledgeFromExecution(
            success = success,
            errorMessage = errorMessage,
            classification = classification,
            backend = backend
        )

        when {
            !success && errorMessage.contains("permission", ignoreCase = true) ->
                saveOrUpdateKnowledge(
                    TYPE_TOOL_REQUIREMENT,
                    toolName,
                    "$toolName requires an unavailable permission: ${errorMessage.take(200)}",
                    confidence = 0.9f,
                    priority = 2,
                    tags = "permission,requirement,$toolName"
                )

            !success && (
                errorMessage.contains("not available", ignoreCase = true) ||
                    errorMessage.contains("not installed", ignoreCase = true)
                ) -> saveOrUpdateKnowledge(
                    TYPE_TOOL_LIMITATION,
                    toolName,
                    "$toolName unavailable: ${errorMessage.take(200)}",
                    confidence = 0.95f,
                    priority = 2,
                    tags = "unavailable,limitation,$toolName"
                )

            !success && executionTimeMs > 30_000 -> saveOrUpdateKnowledge(
                TYPE_TOOL_LIMITATION,
                "${toolName}_timeout",
                "$toolName timed out after ${executionTimeMs}ms; reduce scope or split the operation.",
                confidence = 0.8f,
                priority = 3,
                tags = "timeout,performance,$toolName"
            )

            success && executionTimeMs in 1..199 -> saveOrUpdateKnowledge(
                TYPE_TOOL_CAPABILITY,
                "${toolName}_performance",
                "$toolName completed successfully in ${executionTimeMs}ms.",
                confidence = 0.7f,
                priority = 7,
                tags = "fast,performance,$toolName"
            )
        }
    }

    private suspend fun updateRuntimeKnowledgeFromExecution(
        success: Boolean,
        errorMessage: String,
        classification: String?,
        backend: String?
    ) {
        val cls = classification?.uppercase().orEmpty()
        val be = backend?.lowercase().orEmpty()
        val lower = errorMessage.lowercase()

        val termuxEvidence = be == "termux" ||
            cls.startsWith("TERMUX_") ||
            lower.contains("runcommandservice")
        if (termuxEvidence) {
            val healthy = success && cls !in setOf(
                "TERMUX_RUN_COMMAND_UNAVAILABLE",
                "TERMUX_EXTERNAL_APPS_DISABLED"
            )
            val type = if (healthy) TYPE_ENVIRONMENT else TYPE_WARNING
            systemKnowledgeDao.invalidateOtherTypesForSubject(
                subject = "termux",
                source = "auto_discovery",
                keepType = type
            )
            saveOrUpdateKnowledge(
                type,
                "termux",
                if (healthy) {
                    "Termux RunCommandService succeeded in the current session. Use agent_runtime only for developer/Linux package/runtime work."
                } else {
                    "Termux transport is currently unavailable in this session (${classification ?: "transport failure"}). Circuit-break it; do not retry agent_runtime shell/package work until fix_termux succeeds."
                },
                confidence = 1.0f,
                priority = 1,
                tags = "termux,runtime,current_state",
                source = "auto_discovery"
            )
            runtimeEnvironmentCache["termux_ready"] = healthy.toString()
        }

        val shizukuEvidence = be == "shizuku-user-service" ||
            cls.startsWith("SHIZUKU_") ||
            lower.contains("shizuku userservice") ||
            lower.contains("shizuku user service")
        if (shizukuEvidence) {
            val backendFailure = cls in setOf(
                "SHIZUKU_PERMISSION_REQUIRED",
                "SHIZUKU_UNAVAILABLE",
                "SHIZUKU_CONNECTION_TIMEOUT"
            ) || lower.contains("binder") || lower.contains("service disconnected")
            val healthy = success && !backendFailure
            val type = if (healthy) TYPE_ENVIRONMENT else TYPE_WARNING
            systemKnowledgeDao.invalidateOtherTypesForSubject(
                subject = "shizuku",
                source = "auto_discovery",
                keepType = type
            )
            saveOrUpdateKnowledge(
                type,
                "shizuku",
                if (healthy) {
                    "Shizuku UserService executed successfully in the current session. Prefer specialized device tools; generic Android shell work may use the Shizuku-routed path."
                } else {
                    "Shizuku is currently unavailable/degraded in this session (${classification ?: "backend failure"}). Do not repeat the same privileged strategy until the backend state changes."
                },
                confidence = 1.0f,
                priority = 1,
                tags = "shizuku,runtime,current_state",
                source = "auto_discovery"
            )
            runtimeEnvironmentCache["shizuku"] = healthy.toString()
        }
    }

    suspend fun recordToolDependency(toolA: String, toolB: String, description: String) {
        saveOrUpdateKnowledge(
            TYPE_TOOL_DEPENDENCY,
            "$toolA→$toolB",
            description,
            priority = 3,
            tags = "dependency,$toolA,$toolB"
        )
    }

    suspend fun recordPattern(patternName: String, description: String, confidence: Float = 0.8f) {
        saveOrUpdateKnowledge(
            TYPE_PATTERN,
            patternName,
            description,
            confidence = confidence,
            priority = 4,
            tags = "pattern,learned"
        )
    }

    suspend fun buildSystemPromptContext(): String = withContext(Dispatchers.IO) {
        val systemInfo = systemKnowledgeDao.getPromptByType(TYPE_SYSTEM_INFO)
        val capabilities = systemKnowledgeDao.getPromptByType(TYPE_SYSTEM_CAPABILITY)
        val environments = systemKnowledgeDao.getPromptByType(TYPE_ENVIRONMENT)
        val limitations = systemKnowledgeDao.getPromptByType(TYPE_TOOL_LIMITATION)
        val bestPractices = systemKnowledgeDao.getPromptByType(TYPE_BEST_PRACTICE)
        val warnings = systemKnowledgeDao.getPromptByType(TYPE_WARNING)

        buildString {
            appendLine("\n╔══════════════════════════════════════════════╗")
            appendLine("║  🧠 SYSTEM & TOOL AWARENESS CONTEXT         ║")
            appendLine("╚══════════════════════════════════════════════╝")

            if (environments.isNotEmpty() || systemInfo.isNotEmpty()) {
                appendLine("\nRuntime environment:")
                (environments + systemInfo)
                    .distinctBy { it.knowledgeType to it.subject }
                    .take(8)
                    .forEach { appendLine("  • ${it.content.take(220)}") }
            }

            if (limitations.isNotEmpty() || warnings.isNotEmpty()) {
                appendLine("\nCurrent limitations / setup requirements:")
                (limitations + warnings)
                    .distinctBy { it.knowledgeType to it.subject }
                    .take(6)
                    .forEach { appendLine("  • ${it.content.take(220)}") }
            }

            if (bestPractices.isNotEmpty()) {
                appendLine("\nRuntime/tool routing rules:")
                bestPractices.take(6).forEach {
                    appendLine("  • [${it.subject}] ${it.content.take(260)}")
                }
            }

            if (capabilities.isNotEmpty()) {
                appendLine(
                    "\nDetected hardware: " +
                        capabilities.distinctBy { it.subject }.joinToString(", ") { it.subject.take(60) }
                )
            }
            appendLine("══════════════════════════════════════════════")
        }
    }

    suspend fun getToolKnowledge(toolName: String): String? = withContext(Dispatchers.IO) {
        val entries = systemKnowledgeDao.search(toolName, limit = 8)
        if (entries.isEmpty()) return@withContext null

        buildString {
            entries.forEach { k ->
                val prefix = when (k.knowledgeType) {
                    TYPE_TOOL_LIMITATION -> "⚠️"
                    TYPE_TOOL_REQUIREMENT -> "📋"
                    TYPE_BEST_PRACTICE -> "💡"
                    TYPE_WARNING -> "🚨"
                    else -> "ℹ️"
                }
                appendLine("$prefix ${k.content.take(300)}")
            }
        }.takeIf { it.isNotBlank() }
    }

    suspend fun getCriticalKnowledge(): List<SystemKnowledgeEntry> = withContext(Dispatchers.IO) {
        systemKnowledgeDao.getForSystemPrompt(maxPriority = 3, limit = 10)
    }

    private suspend fun saveKnowledge(
        type: String,
        subject: String,
        content: String,
        confidence: Float = 1.0f,
        priority: Int = 5,
        tags: String = "",
        source: String = "auto_discovery"
    ) {
        try {
            systemKnowledgeDao.merge(
                SystemKnowledgeEntry(
                    knowledgeType = type,
                    subject = subject,
                    content = content,
                    confidence = confidence,
                    injectionPriority = priority,
                    searchTags = tags,
                    source = source
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to store tool knowledge '$subject': ${e.message}")
        }
    }

    private suspend fun saveOrUpdateKnowledge(
        type: String,
        subject: String,
        content: String,
        confidence: Float = 1.0f,
        priority: Int = 5,
        tags: String = "",
        source: String = "auto_discovery"
    ) = saveKnowledge(type, subject, content, confidence, priority, tags, source)

    fun observeKnowledge(): Flow<List<SystemKnowledgeEntry>> = systemKnowledgeDao.observeAllValid()

    suspend fun clearKnowledgeLog() = withContext(Dispatchers.IO) {
        initializationMutex.withLock {
            systemKnowledgeDao.clearAll()
            runtimeEnvironmentCache.clear()
            isInitialized = false
        }
    }

    suspend fun updateKnowledgeEntry(
        id: Long,
        subject: String,
        content: String,
        confidence: Float
    ) = withContext(Dispatchers.IO) {
        val existing = systemKnowledgeDao.getById(id)
        if (existing == null) {
            Log.w(TAG, "updateKnowledgeEntry: entry not found (id=$id)")
            return@withContext
        }
        systemKnowledgeDao.update(
            existing.copy(
                subject = subject,
                content = content,
                confidence = confidence.coerceIn(0f, 1f),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun invalidateKnowledgeById(id: Long) = withContext(Dispatchers.IO) {
        systemKnowledgeDao.invalidateById(id)
    }

    suspend fun getStats(): AwarenessStats = withContext(Dispatchers.IO) {
        val byType = systemKnowledgeDao.countsByType().associate { it.knowledgeType to it.count }
        AwarenessStats(
            totalKnowledge = byType.values.sum(),
            byType = byType,
            isInitialized = isInitialized,
            environmentCache = runtimeEnvironmentCache.toMap()
        )
    }

    data class AwarenessStats(
        val totalKnowledge: Int,
        val byType: Map<String, Int>,
        val isInitialized: Boolean,
        val environmentCache: Map<String, String>
    )
}
