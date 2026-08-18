package com.omnidev.workspace.data.brain

import android.content.Context
import android.os.Build
import android.util.Log
import com.omnidev.workspace.data.db.dao.SystemKnowledgeDao
import com.omnidev.workspace.data.db.entities.SystemKnowledgeEntry
import com.omnidev.workspace.data.tools.ToolDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * ToolAwarenessEngine — [Localized] [Localized] [Localized] [Localized]
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * [Localized] [Localized] [Localized] [Localized] [Localized]:
 * 1. [Localized] [Localized] [Localized] [Localized]
 * 2. [Localized] [Localized] [Localized] (Android, Termux, Shizuku, etc.)
 * 3. [Localized] [Localized] [Localized]
 * 4. [Localized] [Localized] [Localized] [Localized]
 * 5. [Localized] [Localized] [Localized]
 *
 * [Localized] [Localized] [Localized] Claude Code [Localized]:
 * - [Localized] [Localized] [Localized] [Localized]
 * - [Localized] [Localized] [Localized] [Localized] [Localized]
 * - [Localized] context enrichment [Localized]
 */
class ToolAwarenessEngine(
    private val context: Context,
    private val systemKnowledgeDao: SystemKnowledgeDao,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    companion object {
        private const val TAG = "ToolAwareness"

        // [Localized] [Localized]
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

    // ─── [Localized] [Localized] ───────────────────────────────────────────────

    private val runtimeEnvironmentCache = mutableMapOf<String, String>()
    private var isInitialized = false

    // ─── [Localized] [Localized] ───────────────────────────────────────────────

    /**
     * [Localized] [Localized]: [Localized] [Localized] [Localized] [Localized] [Localized]
     */
    suspend fun initialize(availableTools: List<ToolDefinition> = emptyList()) = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext
        
        Log.d(TAG, "🔍 [Localized] [Localized] [Localized] [Localized]...")

        // 1. [Localized] [Localized] [Localized]
        discoverSystemEnvironment()

        // 2. [Localized] [Localized] [Localized]
        if (availableTools.isNotEmpty()) {
            registerToolCapabilities(availableTools)
        }

        // 3. [Localized] [Localized] [Localized]
        discoverDeviceCapabilities()

        // 4. [Localized] [Localized] [Localized]
        discoverRuntimeEnvironments()

        // 5. [Localized] [Localized] [Localized] [Localized]
        registerInitialBestPractices()

        isInitialized = true
        Log.d(TAG, "✅ [Localized] [Localized] [Localized] - ${systemKnowledgeDao.getCount()} [Localized] [Localized]")
    }

    // ─── [Localized] [Localized] ───────────────────────────────────────────────

    private suspend fun discoverSystemEnvironment() {
        val deviceInfo = buildString {
            appendLine("Android SDK: ${Build.VERSION.SDK_INT}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("CPU ABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
        }

        saveOrUpdateKnowledge(
            type = TYPE_SYSTEM_INFO,
            subject = "device_info",
            content = deviceInfo,
            priority = 5,
            tags = "android,device,sdk,system"
        )

        // [Localized] Android API
        val apiLevel = Build.VERSION.SDK_INT
        when {
            apiLevel >= 33 -> saveOrUpdateKnowledge(
                type = TYPE_SYSTEM_INFO, subject = "android_api",
                content = "Android 13+ (API $apiLevel): [Localized] [Localized]. MediaStore [Localized] Scoped Storage [Localized].",
                priority = 2
            )
            apiLevel >= 30 -> saveOrUpdateKnowledge(
                type = TYPE_SYSTEM_INFO, subject = "android_api",
                content = "Android 11+ (API $apiLevel): Scoped Storage. [Localized] [Localized] [Localized] [Localized] MANAGE_EXTERNAL_STORAGE.",
                priority = 2
            )
            apiLevel >= 26 -> saveOrUpdateKnowledge(
                type = TYPE_SYSTEM_INFO, subject = "android_api",
                content = "Android 8+ (API $apiLevel): JobScheduler [Localized]. Background Limits [Localized].",
                priority = 3
            )
        }
    }

    private suspend fun discoverDeviceCapabilities() {
        val pm = context.packageManager

        // [Localized] [Localized]
        val hasCamera = pm.hasSystemFeature("android.hardware.camera")
        if (hasCamera) {
            saveKnowledge(TYPE_SYSTEM_CAPABILITY, "camera", "[Localized] [Localized] [Localized]", priority = 8)
        }

        // [Localized] [Localized]
        val hasBluetooth = pm.hasSystemFeature("android.hardware.bluetooth")
        if (hasBluetooth) {
            saveKnowledge(TYPE_SYSTEM_CAPABILITY, "bluetooth", "[Localized] [Localized] [Localized]", priority = 8)
        }

        // [Localized] [Localized]
        val runtime = Runtime.getRuntime()
        val maxMemMB = runtime.maxMemory() / (1024 * 1024)
        saveKnowledge(
            TYPE_SYSTEM_INFO, "memory",
            "[Localized] JVM [Localized]: ${maxMemMB}MB - [Localized] [Localized] streaming [Localized] [Localized]",
            priority = 4,
            tags = "memory,performance,heap"
        )

        // [Localized] [Localized]
        try {
            val dataDir = context.filesDir
            val free = dataDir.freeSpace / (1024 * 1024)
            val total = dataDir.totalSpace / (1024 * 1024)
            saveKnowledge(
                TYPE_SYSTEM_INFO, "storage",
                "[Localized]: ${free}MB [Localized] [Localized] [Localized] ${total}MB",
                priority = 5,
                tags = "storage,disk,space"
            )
        } catch (_: Exception) {}
    }

    private suspend fun discoverRuntimeEnvironments() {
        // [Localized] Termux
        val termuxInstalled = isPackageInstalled("com.termux")
        runtimeEnvironmentCache["termux"] = termuxInstalled.toString()
        if (termuxInstalled) {
            saveKnowledge(
                TYPE_ENVIRONMENT, "termux",
                "Termux [Localized]: [Localized] [Localized] Python, Node.js, bash, gcc, git [Localized] termux_bridge",
                confidence = 0.9f,
                priority = 2,
                tags = "termux,python,nodejs,bash,linux"
            )
        } else {
            saveKnowledge(
                TYPE_WARNING, "termux",
                "Termux [Localized] [Localized]: [Localized] AgentRuntimeTool [Localized] [Localized] agent_sandbox",
                confidence = 1.0f,
                priority = 3,
                tags = "termux,warning"
            )
        }

        // [Localized] Shizuku
        val shizukuInstalled = isPackageInstalled("moe.shizuku.privileged.api")
        runtimeEnvironmentCache["shizuku"] = shizukuInstalled.toString()
        if (shizukuInstalled) {
            saveKnowledge(
                TYPE_ENVIRONMENT, "shizuku",
                "Shizuku [Localized]: [Localized] [Localized] [Localized] ADB-level [Localized] root [Localized] shizuku_command",
                confidence = 0.8f,
                priority = 2,
                tags = "shizuku,adb,privileged,root"
            )
        } else {
            saveKnowledge(
                TYPE_WARNING, "shizuku",
                "Shizuku [Localized] [Localized]: [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]",
                confidence = 1.0f,
                priority = 3,
                tags = "shizuku,warning"
            )
        }

        // [Localized] Python [Localized]
        val pythonExists = File("/data/data/com.termux/files/usr/bin/python3").exists() ||
                           File("/data/data/com.termux/files/usr/bin/python").exists()
        if (pythonExists) {
            saveKnowledge(
                TYPE_ENVIRONMENT, "python",
                "Python [Localized] [Localized] Termux: [Localized] agent_runtime/python_run [Localized] [Localized]",
                confidence = 0.95f,
                priority = 2,
                tags = "python,termux,runtime,code"
            )
        }

        // [Localized] Git
        val gitExists = File("/data/data/com.termux/files/usr/bin/git").exists()
        if (gitExists) {
            saveKnowledge(
                TYPE_ENVIRONMENT, "git",
                "Git [Localized] [Localized] Termux: [Localized] git_manager [Localized] terminal [Localized] Git",
                confidence = 0.95f,
                priority = 3,
                tags = "git,termux,vcs"
            )
        }
    }

    private suspend fun registerToolCapabilities(tools: List<ToolDefinition>) {
        tools.forEach { tool ->
            val capability = buildString {
                append("[Localized]: ${tool.name}")
                append(" | [Localized]: ${tool.description.take(200)}")
                if (tool.parameters.isNotEmpty()) {
                    append(" | [Localized]: ${tool.parameters.joinToString(", ") { p ->
                        "${p.name}(${if (p.required) "[Localized]" else "[Localized]"})"
                    }}")
                }
            }

            saveKnowledge(
                type = TYPE_TOOL_CAPABILITY,
                subject = tool.name,
                content = capability,
                priority = 6,
                tags = "tool,${tool.name},capability",
                source = "tool_registry"
            )
        }
        Log.d(TAG, "📋 [Localized] ${tools.size} [Localized] [Localized] [Localized] [Localized]")
    }

    private suspend fun registerInitialBestPractices() {
        val practices = listOf(
            Triple(
                "file_operations",
                "[Localized] [Localized] [Localized]: [Localized] read_file_lines [Localized] [Localized]. [Localized] [Localized] (+1MB) [Localized] find_files [Localized] grep_search [Localized] [Localized] [Localized] [Localized].",
                "file,read,performance"
            ),
            Triple(
                "memory_usage",
                "[Localized] [Localized] [Localized] [Localized] search_knowledge [Localized] [Localized] [Localized] [Localized] [Localized]. [Localized] [Localized] [Localized] [Localized] remember_fact. [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].",
                "memory,context,efficiency"
            ),
            Triple(
                "terminal_safety",
                "[Localized] [Localized] [Localized] terminal [Localized]: [Localized] dry-run [Localized] echo [Localized]. [Localized] rm -rf. [Localized] paths [Localized] [Localized].",
                "terminal,safety,commands"
            ),
            Triple(
                "web_search_strategy",
                "[Localized]: [Localized] [Localized] web_search ([Localized]). [Localized] web_scraper [Localized] [Localized]. [Localized] headless_browser [Localized] [Localized] [Localized] [Localized] JavaScript.",
                "web,search,strategy"
            ),
            Triple(
                "git_workflow",
                "[Localized] [Localized] Git: [Localized] [Localized] [Localized] [Localized] → [Localized] [Localized] [Localized] [Localized] → commit [Localized] → [Localized] [Localized] [Localized] [Localized] main",
                "git,workflow,best_practice"
            ),
            Triple(
                "error_handling",
                "[Localized] [Localized]: [Localized] [Localized] [Localized] [Localized] → [Localized] [Localized] [Localized] → [Localized] [Localized] [Localized] → [Localized] [Localized] [Localized] remember_fact",
                "error,debugging,recovery"
            ),
            Triple(
                "tool_selection",
                "[Localized] [Localized] [Localized] [Localized]. [Localized]: [Localized] [Localized] [Localized] [Localized] grep_search ([Localized]) [Localized] read_file. [Localized] [Localized] get_device_info [Localized] shizuku_command.",
                "tool_selection,efficiency,performance"
            )
        )

        practices.forEach { (subject, content, tags) ->
            saveOrUpdateKnowledge(
                type = TYPE_BEST_PRACTICE,
                subject = subject,
                content = content,
                priority = 2,
                tags = "best_practice,$tags",
                source = "built_in"
            )
        }
    }

    // ─── [Localized] [Localized] ───────────────────────────────────────────

    /**
     * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]
     */
    suspend fun learnFromExecution(
        toolName: String,
        success: Boolean,
        errorMessage: String = "",
        executionTimeMs: Long = 0,
        params: Map<String, Any?> = emptyMap()
    ) = withContext(Dispatchers.IO) {
        when {
            !success && errorMessage.contains("permission", ignoreCase = true) -> {
                saveOrUpdateKnowledge(
                    type = TYPE_TOOL_REQUIREMENT,
                    subject = toolName,
                    content = "⚠️ $toolName [Localized] [Localized] [Localized]. [Localized]: ${errorMessage.take(150)}",
                    confidence = 0.9f,
                    priority = 2,
                    tags = "permission,requirement,$toolName"
                )
            }

            !success && errorMessage.contains("not available", ignoreCase = true) -> {
                saveOrUpdateKnowledge(
                    type = TYPE_TOOL_LIMITATION,
                    subject = toolName,
                    content = "🚫 $toolName [Localized] [Localized] [Localized] [Localized] [Localized]: ${errorMessage.take(150)}",
                    confidence = 0.95f,
                    priority = 1,
                    tags = "unavailable,limitation,$toolName"
                )
            }

            !success && executionTimeMs > 30_000 -> {
                saveOrUpdateKnowledge(
                    type = TYPE_TOOL_LIMITATION,
                    subject = "${toolName}_timeout",
                    content = "⏱️ $toolName [Localized] [Localized] [Localized] [Localized] [Localized] (${executionTimeMs}ms). [Localized] [Localized] [Localized].",
                    confidence = 0.8f,
                    priority = 2,
                    tags = "timeout,performance,$toolName"
                )
            }

            success && executionTimeMs < 200 -> {
                // [Localized] [Localized] [Localized] - [Localized] [Localized]
                saveOrUpdateKnowledge(
                    type = TYPE_TOOL_CAPABILITY,
                    subject = "${toolName}_performance",
                    content = "⚡ $toolName [Localized] [Localized] (avg ~${executionTimeMs}ms) - [Localized] [Localized] [Localized]",
                    confidence = 0.7f,
                    priority = 7,
                    tags = "fast,performance,$toolName"
                )
            }
        }
    }

    /**
     * [Localized] [Localized] [Localized] [Localized] [Localized]
     */
    suspend fun recordToolDependency(toolA: String, toolB: String, description: String) {
        saveKnowledge(
            type = TYPE_TOOL_DEPENDENCY,
            subject = "$toolA→$toolB",
            content = description,
            priority = 3,
            tags = "dependency,$toolA,$toolB"
        )
    }

    /**
     * [Localized] [Localized] [Localized] [Localized] Agent
     */
    suspend fun recordPattern(patternName: String, description: String, confidence: Float = 0.8f) {
        saveKnowledge(
            type = TYPE_PATTERN,
            subject = patternName,
            content = description,
            confidence = confidence,
            priority = 4,
            tags = "pattern,learned"
        )
    }

    // ─── [Localized] System Prompt Context ───────────────────────────────────

    /**
     * [Localized] [Localized] [Localized] [Localized] System Prompt
     * [Localized] [Localized] [Localized] [Localized] Agent "[Localized]" [Localized] [Localized]
     */
    suspend fun buildSystemPromptContext(): String = withContext(Dispatchers.IO) {
        val systemInfo = systemKnowledgeDao.getByType(TYPE_SYSTEM_INFO)
        val capabilities = systemKnowledgeDao.getByType(TYPE_SYSTEM_CAPABILITY)
        val environments = systemKnowledgeDao.getByType(TYPE_ENVIRONMENT)
        val limitations = systemKnowledgeDao.getByType(TYPE_TOOL_LIMITATION)
        val bestPractices = systemKnowledgeDao.getByType(TYPE_BEST_PRACTICE)
        val warnings = systemKnowledgeDao.getByType(TYPE_WARNING)

        buildString {
            appendLine("\n╔══════════════════════════════════════════════╗")
            appendLine("║  🧠 SYSTEM & TOOL AWARENESS CONTEXT         ║")
            appendLine("╚══════════════════════════════════════════════╝")

            // [Localized] [Localized]
            if (environments.isNotEmpty() || systemInfo.isNotEmpty()) {
                appendLine("\n📱 [Localized] [Localized]:")
                (environments + systemInfo.filter { it.subject.contains("android") || it.subject == "memory" })
                    .take(6).forEach { k ->
                        appendLine("  • ${k.content.take(120)}")
                    }
            }

            // [Localized] [Localized] ([Localized] [Localized]!)
            if (limitations.isNotEmpty() || warnings.isNotEmpty()) {
                appendLine("\n⚠️ [Localized] [Localized] ([Localized] [Localized] [Localized]):")
                (limitations + warnings).take(5).forEach { k ->
                    appendLine("  ✗ ${k.content.take(120)}")
                }
            }

            // [Localized] [Localized]
            if (bestPractices.isNotEmpty()) {
                appendLine("\n💡 [Localized] [Localized]:")
                bestPractices.take(5).forEach { k ->
                    appendLine("  ✓ [${k.subject}] ${k.content.take(150)}")
                }
            }

            // [Localized] [Localized]
            if (capabilities.isNotEmpty()) {
                appendLine("\n⚡ [Localized] [Localized]: ${capabilities.joinToString(", ") { it.subject }}")
            }

            appendLine("══════════════════════════════════════════════")
        }
    }

    /**
     * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]
     */
    suspend fun getToolKnowledge(toolName: String): String? = withContext(Dispatchers.IO) {
        val entries = systemKnowledgeDao.search(toolName, limit = 8)
        if (entries.isEmpty()) return@withContext null

        buildString {
            entries.forEach { k ->
                when (k.knowledgeType) {
                    TYPE_TOOL_LIMITATION -> appendLine("⚠️ ${k.content}")
                    TYPE_TOOL_REQUIREMENT -> appendLine("📋 ${k.content}")
                    TYPE_BEST_PRACTICE -> appendLine("💡 ${k.content}")
                    TYPE_WARNING -> appendLine("🚨 ${k.content}")
                    else -> appendLine("ℹ️ ${k.content}")
                }
            }
        }.takeIf { it.isNotBlank() }
    }

    /**
     * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] System Prompt
     */
    suspend fun getCriticalKnowledge(): List<SystemKnowledgeEntry> = withContext(Dispatchers.IO) {
        systemKnowledgeDao.getForSystemPrompt(maxPriority = 3, limit = 10)
    }

    // ─── [Localized] [Localized] ─────────────────────────────────────────────

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
            systemKnowledgeDao.insert(
                SystemKnowledgeEntry(
                    category = type,
                    key = subject,
                    knowledgeType = type,
                    subject = subject,
                    content = content,
                    confidence = confidence,
                    injectionPriority = priority,
                    searchTags = tags,
                    source = source
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "[Localized] [Localized] [Localized] [Localized]: $subject - ${e.message}")
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
    ) {
        val existing = systemKnowledgeDao.getBySubject(subject).firstOrNull { it.knowledgeType == type }
        if (existing != null) {
            systemKnowledgeDao.update(
                existing.copy(
                    content = content,
                    confidence = confidence,
                    verificationCount = existing.verificationCount + 1,
                    updatedAt = System.currentTimeMillis()
                )
            )
        } else {
            saveKnowledge(type, subject, content, confidence, priority, tags, source)
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    // ─── Flow [Localized] ─────────────────────────────────────────────────

    fun observeKnowledge(): Flow<List<SystemKnowledgeEntry>> = systemKnowledgeDao.observeAllValid()

    suspend fun updateKnowledgeEntry(
        id: Long,
        subject: String,
        content: String,
        confidence: Float
    ) = withContext(Dispatchers.IO) {
        val existing = systemKnowledgeDao.getById(id)
        if (existing == null) {
            Log.w(TAG, "⚠️ updateKnowledgeEntry: entry not found (id=$id)")
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

    // ─── [Localized] ────────────────────────────────────────────────────

    suspend fun getStats(): AwarenessStats = withContext(Dispatchers.IO) {
        val total = systemKnowledgeDao.getCount()
        val byType = mutableMapOf<String, Int>()

        listOf(
            TYPE_TOOL_CAPABILITY, TYPE_TOOL_LIMITATION, TYPE_SYSTEM_INFO,
            TYPE_ENVIRONMENT, TYPE_BEST_PRACTICE, TYPE_WARNING, TYPE_PATTERN
        ).forEach { type ->
            byType[type] = systemKnowledgeDao.getByType(type).size
        }

        AwarenessStats(
            totalKnowledge = total,
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
