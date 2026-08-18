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
 * ToolAwarenessEngine — Tool and System Awareness Engine
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Builds deep, continuous awareness about:
 * 1. Available tools, capabilities, and requirements
 * 2. System state and environment (Android, Termux, Shizuku, etc.)
 * 3. Dependencies between tools
 * 4. Limitations and boundaries for each tool
 * 5. Best usage strategies
 *
 * Inspired by Claude Code approach to:
 * - Understanding environment before execution
 * - Updating knowledge based on experience
 * - Providing smart context enrichment
 */
class ToolAwarenessEngine(
    private val context: Context,
    private val systemKnowledgeDao: SystemKnowledgeDao,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    companion object {
        private const val TAG = "ToolAwareness"

        // Knowledge types
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

    // ─── Context note Context note ───────────────────────────────────────────────

    private val runtimeEnvironmentCache = mutableMapOf<String, String>()
    private var isInitialized = false

    // ─── Context note Context note ───────────────────────────────────────────────

    /**
     * Context note Context note: Context note Context note Context note Context note Context note
     */
    suspend fun initialize(availableTools: List<ToolDefinition> = emptyList()) = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext
        
        Log.d(TAG, "🔍 Info Info Info Info...")

        // 1. Context note Context note Context note
        discoverSystemEnvironment()

        // 2. Context note Context note Context note
        if (availableTools.isNotEmpty()) {
            registerToolCapabilities(availableTools)
        }

        // 3. Context note Context note Context note
        discoverDeviceCapabilities()

        // 4. Context note Context note Context note
        discoverRuntimeEnvironments()

        // 5. Context note Context note Context note Context note
        registerInitialBestPractices()

        isInitialized = true
        Log.d(TAG, "✅ System discovery completed - ${systemKnowledgeDao.getCount()} Knowledge saved")
    }

    // ─── Context note Context note ───────────────────────────────────────────────

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

        // Context note Android API
        val apiLevel = Build.VERSION.SDK_INT
        when {
            apiLevel >= 33 -> saveOrUpdateKnowledge(
                type = TYPE_SYSTEM_INFO, subject = "android_api",
                content = "Android 13+ (API $apiLevel): Info Info. MediaStore Info Scoped Storage Info.",
                priority = 2
            )
            apiLevel >= 30 -> saveOrUpdateKnowledge(
                type = TYPE_SYSTEM_INFO, subject = "android_api",
                content = "Android 11+ (API $apiLevel): Scoped Storage. Info Info Info Info MANAGE_EXTERNAL_STORAGE.",
                priority = 2
            )
            apiLevel >= 26 -> saveOrUpdateKnowledge(
                type = TYPE_SYSTEM_INFO, subject = "android_api",
                content = "Android 8+ (API $apiLevel): JobScheduler Info. Background Limits Info.",
                priority = 3
            )
        }
    }

    private suspend fun discoverDeviceCapabilities() {
        val pm = context.packageManager

        // Context note Context note
        val hasCamera = pm.hasSystemFeature("android.hardware.camera")
        if (hasCamera) {
            saveKnowledge(TYPE_SYSTEM_CAPABILITY, "camera", "Info Info Info", priority = 8)
        }

        // Context note Context note
        val hasBluetooth = pm.hasSystemFeature("android.hardware.bluetooth")
        if (hasBluetooth) {
            saveKnowledge(TYPE_SYSTEM_CAPABILITY, "bluetooth", "Info Info Info", priority = 8)
        }

        // Context note Context note
        val runtime = Runtime.getRuntime()
        val maxMemMB = runtime.maxMemory() / (1024 * 1024)
        saveKnowledge(
            TYPE_SYSTEM_INFO, "memory",
            "Info JVM Info: ${maxMemMB}MB - Info Info streaming Info Info",
            priority = 4,
            tags = "memory,performance,heap"
        )

        // Context note Context note
        try {
            val dataDir = context.filesDir
            val free = dataDir.freeSpace / (1024 * 1024)
            val total = dataDir.totalSpace / (1024 * 1024)
            saveKnowledge(
                TYPE_SYSTEM_INFO, "storage",
                "Info: ${free}MB Info Info Info ${total}MB",
                priority = 5,
                tags = "storage,disk,space"
            )
        } catch (_: Exception) {}
    }

    private suspend fun discoverRuntimeEnvironments() {
        // Context note Termux
        val termuxInstalled = isPackageInstalled("com.termux")
        runtimeEnvironmentCache["termux"] = termuxInstalled.toString()
        if (termuxInstalled) {
            saveKnowledge(
                TYPE_ENVIRONMENT, "termux",
                "Termux Info: Info Info Python, Node.js, bash, gcc, git Info termux_bridge",
                confidence = 0.9f,
                priority = 2,
                tags = "termux,python,nodejs,bash,linux"
            )
        } else {
            saveKnowledge(
                TYPE_WARNING, "termux",
                "Termux Info Info: Info AgentRuntimeTool Info Info agent_sandbox",
                confidence = 1.0f,
                priority = 3,
                tags = "termux,warning"
            )
        }

        // Context note Shizuku
        val shizukuInstalled = isPackageInstalled("moe.shizuku.privileged.api")
        runtimeEnvironmentCache["shizuku"] = shizukuInstalled.toString()
        if (shizukuInstalled) {
            saveKnowledge(
                TYPE_ENVIRONMENT, "shizuku",
                "Shizuku Info: Info Info Info ADB-level Info root Info shizuku_command",
                confidence = 0.8f,
                priority = 2,
                tags = "shizuku,adb,privileged,root"
            )
        } else {
            saveKnowledge(
                TYPE_WARNING, "shizuku",
                "Shizuku Info Info: Info Info Info Info Info Info",
                confidence = 1.0f,
                priority = 3,
                tags = "shizuku,warning"
            )
        }

        // Context note Python Context note
        val pythonExists = File("/data/data/com.termux/files/usr/bin/python3").exists() ||
                           File("/data/data/com.termux/files/usr/bin/python").exists()
        if (pythonExists) {
            saveKnowledge(
                TYPE_ENVIRONMENT, "python",
                "Python Info Info Termux: Info agent_runtime/python_run Info Info",
                confidence = 0.95f,
                priority = 2,
                tags = "python,termux,runtime,code"
            )
        }

        // Context note Git
        val gitExists = File("/data/data/com.termux/files/usr/bin/git").exists()
        if (gitExists) {
            saveKnowledge(
                TYPE_ENVIRONMENT, "git",
                "Git Info Info Termux: Info git_manager Info terminal Info Git",
                confidence = 0.95f,
                priority = 3,
                tags = "git,termux,vcs"
            )
        }
    }

    private suspend fun registerToolCapabilities(tools: List<ToolDefinition>) {
        tools.forEach { tool ->
            val capability = buildString {
                append("Info: ${tool.name}")
                append(" | Info: ${tool.description.take(200)}")
                if (tool.parameters.isNotEmpty()) {
                    append(" | Info: ${tool.parameters.joinToString(", ") { p ->
                        "${p.name}(${if (p.required) "Info" else "Info"})"
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
        Log.d(TAG, "📋 Info ${tools.size} Info Info Info Info")
    }

    private suspend fun registerInitialBestPractices() {
        val practices = listOf(
            Triple(
                "file_operations",
                "Info Info Info: Info read_file_lines Info Info. Info Info (+1MB) Info find_files Info grep_search Info Info Info Info.",
                "file,read,performance"
            ),
            Triple(
                "memory_usage",
                "Info Info Info Info search_knowledge Info Info Info Info Info. Info Info Info Info remember_fact. Info Info Info Info Info Info.",
                "memory,context,efficiency"
            ),
            Triple(
                "terminal_safety",
                "Info Info Info terminal Info: Info dry-run Info echo Info. Info rm -rf. Info paths Info Info.",
                "terminal,safety,commands"
            ),
            Triple(
                "web_search_strategy",
                "Info: Info Info web_search (Info). Info web_scraper Info Info. Info headless_browser Info Info Info Info JavaScript.",
                "web,search,strategy"
            ),
            Triple(
                "git_workflow",
                "Info Info Git: Info Info Info Info → Info Info Info Info → commit Info → Info Info Info Info main",
                "git,workflow,best_practice"
            ),
            Triple(
                "error_handling",
                "Info Info: Info Info Info Info → Info Info Info → Info Info Info → Info Info Info remember_fact",
                "error,debugging,recovery"
            ),
            Triple(
                "tool_selection",
                "Info Info Info Info. Info: Info Info Info Info grep_search (Info) Info read_file. Info Info get_device_info Info shizuku_command.",
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

    // ─── Context note Context note ───────────────────────────────────────────

    /**
     * Context note Context note Context note Context note Context note Context note Context note
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
                    content = "⚠️ $toolName Info Info Info. Info: ${errorMessage.take(150)}",
                    confidence = 0.9f,
                    priority = 2,
                    tags = "permission,requirement,$toolName"
                )
            }

            !success && errorMessage.contains("not available", ignoreCase = true) -> {
                saveOrUpdateKnowledge(
                    type = TYPE_TOOL_LIMITATION,
                    subject = toolName,
                    content = "🚫 $toolName Info Info Info Info Info: ${errorMessage.take(150)}",
                    confidence = 0.95f,
                    priority = 1,
                    tags = "unavailable,limitation,$toolName"
                )
            }

            !success && executionTimeMs > 30_000 -> {
                saveOrUpdateKnowledge(
                    type = TYPE_TOOL_LIMITATION,
                    subject = "${toolName}_timeout",
                    content = "⏱️ $toolName Info Info Info Info Info (${executionTimeMs}ms). Info Info Info.",
                    confidence = 0.8f,
                    priority = 2,
                    tags = "timeout,performance,$toolName"
                )
            }

            success && executionTimeMs < 200 -> {
                // Context note Context note Context note - Context note Context note
                saveOrUpdateKnowledge(
                    type = TYPE_TOOL_CAPABILITY,
                    subject = "${toolName}_performance",
                    content = "⚡ $toolName Info Info (avg ~${executionTimeMs}ms) - Info Info Info",
                    confidence = 0.7f,
                    priority = 7,
                    tags = "fast,performance,$toolName"
                )
            }
        }
    }

    /**
     * Context note Context note Context note Context note Context note
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
     * Context note Context note Context note Context note Agent
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

    // ─── Context note System Prompt Context ───────────────────────────────────

    /**
     * Context note Context note Context note Context note System Prompt
     * Context note Context note Context note Context note Agent "Context note" Context note Context note
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

            // Context note Context note
            if (environments.isNotEmpty() || systemInfo.isNotEmpty()) {
                appendLine("\n📱 Info Info:")
                (environments + systemInfo.filter { it.subject.contains("android") || it.subject == "memory" })
                    .take(6).forEach { k ->
                        appendLine("  • ${k.content.take(120)}")
                    }
            }

            // Context note Context note (Context note Context note!)
            if (limitations.isNotEmpty() || warnings.isNotEmpty()) {
                appendLine("\n⚠️ Info Info (Info Info Info):")
                (limitations + warnings).take(5).forEach { k ->
                    appendLine("  ✗ ${k.content.take(120)}")
                }
            }

            // Context note Context note
            if (bestPractices.isNotEmpty()) {
                appendLine("\n💡 Info Info:")
                bestPractices.take(5).forEach { k ->
                    appendLine("  ✓ [${k.subject}] ${k.content.take(150)}")
                }
            }

            // Context note Context note
            if (capabilities.isNotEmpty()) {
                appendLine("\n⚡ Info Info: ${capabilities.joinToString(", ") { it.subject }}")
            }

            appendLine("══════════════════════════════════════════════")
        }
    }

    /**
     * Context note Context note Context note Context note Context note Context note Context note
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
     * Context note Context note Context note Context note Context note Context note Context note Context note System Prompt
     */
    suspend fun getCriticalKnowledge(): List<SystemKnowledgeEntry> = withContext(Dispatchers.IO) {
        systemKnowledgeDao.getForSystemPrompt(maxPriority = 3, limit = 10)
    }

    // ─── Context note Context note ─────────────────────────────────────────────

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
            Log.w(TAG, "Failed to save knowledge: $subject - ${e.message}")
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

    // ─── Flow Context note ─────────────────────────────────────────────────

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

    // ─── Context note ────────────────────────────────────────────────────

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
