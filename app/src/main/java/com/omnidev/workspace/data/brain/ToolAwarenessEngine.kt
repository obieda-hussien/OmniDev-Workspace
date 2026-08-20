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
 * ToolAwarenessEngine — Tool & System Awareness Engine
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Builds deep, continuous awareness regarding:
 * 1. Available tools, capabilities, and requirements
 * 2. System & environment state (Android, Termux, Shizuku, etc.)
 * 3. Tool dependencies
 * 4. Tool boundaries and limitations
 * 5. Best usage strategies
 *
 * Inspired by Claude Code approach to:
 * - Environment understanding prior to execution
 * - Knowledge updates based on execution history
 * - Smart context enrichment
 */
class ToolAwarenessEngine(
    private val context: Context,
    private val systemKnowledgeDao: SystemKnowledgeDao,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    companion object {
        private const val TAG = "ToolAwareness"

        // Knowledge categories
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

    // ─── System awareness note System awareness note ───────────────────────────────────────────────

    private val runtimeEnvironmentCache = mutableMapOf<String, String>()
    private var isInitialized = false

    // ─── System awareness note System awareness note ───────────────────────────────────────────────

    /**
     * System awareness note System awareness note: System awareness note System awareness note System awareness note System awareness note System awareness note
     */
    suspend fun initialize(availableTools: List<ToolDefinition> = emptyList()) = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext
        
        Log.d(TAG, "🔍 System awareness note System awareness note System awareness note System awareness note...")

        // 1. System awareness note System awareness note System awareness note
        discoverSystemEnvironment()

        // 2. System awareness note System awareness note System awareness note
        if (availableTools.isNotEmpty()) {
            registerToolCapabilities(availableTools)
        }

        // 3. System awareness note System awareness note System awareness note
        discoverDeviceCapabilities()

        // 4. System awareness note System awareness note System awareness note
        discoverRuntimeEnvironments()

        // 5. System awareness note System awareness note System awareness note System awareness note
        registerInitialBestPractices()

        isInitialized = true
        Log.d(TAG, "✅ System environment discovery completed - ${systemKnowledgeDao.getCount()} entries saved")
    }

    // ─── System awareness note System awareness note ───────────────────────────────────────────────

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

        // System awareness note Android API
        val apiLevel = Build.VERSION.SDK_INT
        when {
            apiLevel >= 33 -> saveOrUpdateKnowledge(
                type = TYPE_SYSTEM_INFO, subject = "android_api",
                content = "Android 13+ (API $apiLevel): System awareness note System awareness note. MediaStore System awareness note Scoped Storage System awareness note.",
                priority = 2
            )
            apiLevel >= 30 -> saveOrUpdateKnowledge(
                type = TYPE_SYSTEM_INFO, subject = "android_api",
                content = "Android 11+ (API $apiLevel): Scoped Storage. System awareness note System awareness note System awareness note System awareness note MANAGE_EXTERNAL_STORAGE.",
                priority = 2
            )
            apiLevel >= 26 -> saveOrUpdateKnowledge(
                type = TYPE_SYSTEM_INFO, subject = "android_api",
                content = "Android 8+ (API $apiLevel): JobScheduler System awareness note. Background Limits System awareness note.",
                priority = 3
            )
        }
    }

    private suspend fun discoverDeviceCapabilities() {
        val pm = context.packageManager

        // System awareness note System awareness note
        val hasCamera = pm.hasSystemFeature("android.hardware.camera")
        if (hasCamera) {
            saveKnowledge(TYPE_SYSTEM_CAPABILITY, "camera", "System awareness note System awareness note System awareness note", priority = 8)
        }

        // System awareness note System awareness note
        val hasBluetooth = pm.hasSystemFeature("android.hardware.bluetooth")
        if (hasBluetooth) {
            saveKnowledge(TYPE_SYSTEM_CAPABILITY, "bluetooth", "System awareness note System awareness note System awareness note", priority = 8)
        }

        // System awareness note System awareness note
        val runtime = Runtime.getRuntime()
        val maxMemMB = runtime.maxMemory() / (1024 * 1024)
        saveKnowledge(
            TYPE_SYSTEM_INFO, "memory",
            "System awareness note JVM System awareness note: ${maxMemMB}MB - System awareness note System awareness note streaming System awareness note System awareness note",
            priority = 4,
            tags = "memory,performance,heap"
        )

        // System awareness note System awareness note
        try {
            val dataDir = context.filesDir
            val free = dataDir.freeSpace / (1024 * 1024)
            val total = dataDir.totalSpace / (1024 * 1024)
            saveKnowledge(
                TYPE_SYSTEM_INFO, "storage",
                "System awareness note: ${free}MB System awareness note System awareness note System awareness note ${total}MB",
                priority = 5,
                tags = "storage,disk,space"
            )
        } catch (_: Exception) {}
    }

    private suspend fun discoverRuntimeEnvironments() {
        // System awareness note Termux
        val termuxInstalled = isPackageInstalled("com.termux")
        runtimeEnvironmentCache["termux"] = termuxInstalled.toString()
        if (termuxInstalled) {
            saveKnowledge(
                TYPE_ENVIRONMENT, "termux",
                "Termux System awareness note: System awareness note System awareness note Python, Node.js, bash, gcc, git System awareness note termux_bridge",
                confidence = 0.9f,
                priority = 2,
                tags = "termux,python,nodejs,bash,linux"
            )
        } else {
            saveKnowledge(
                TYPE_WARNING, "termux",
                "Termux System awareness note System awareness note: System awareness note AgentRuntimeTool System awareness note System awareness note agent_sandbox",
                confidence = 1.0f,
                priority = 3,
                tags = "termux,warning"
            )
        }

        // System awareness note Shizuku
        val shizukuInstalled = isPackageInstalled("moe.shizuku.privileged.api")
        runtimeEnvironmentCache["shizuku"] = shizukuInstalled.toString()
        if (shizukuInstalled) {
            saveKnowledge(
                TYPE_ENVIRONMENT, "shizuku",
                "Shizuku System awareness note: System awareness note System awareness note System awareness note ADB-level System awareness note root System awareness note shizuku_command",
                confidence = 0.8f,
                priority = 2,
                tags = "shizuku,adb,privileged,root"
            )
        } else {
            saveKnowledge(
                TYPE_WARNING, "shizuku",
                "Shizuku System awareness note System awareness note: System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note",
                confidence = 1.0f,
                priority = 3,
                tags = "shizuku,warning"
            )
        }

        // System awareness note Python System awareness note
        val pythonExists = File("/data/data/com.termux/files/usr/bin/python3").exists() ||
                           File("/data/data/com.termux/files/usr/bin/python").exists()
        if (pythonExists) {
            saveKnowledge(
                TYPE_ENVIRONMENT, "python",
                "Python System awareness note System awareness note Termux: System awareness note agent_runtime/python_run System awareness note System awareness note",
                confidence = 0.95f,
                priority = 2,
                tags = "python,termux,runtime,code"
            )
        }

        // System awareness note Git
        val gitExists = File("/data/data/com.termux/files/usr/bin/git").exists()
        if (gitExists) {
            saveKnowledge(
                TYPE_ENVIRONMENT, "git",
                "Git System awareness note System awareness note Termux: System awareness note git_manager System awareness note terminal System awareness note Git",
                confidence = 0.95f,
                priority = 3,
                tags = "git,termux,vcs"
            )
        }
    }

    private suspend fun registerToolCapabilities(tools: List<ToolDefinition>) {
        tools.forEach { tool ->
            val capability = buildString {
                append("System awareness note: ${tool.name}")
                append(" | System awareness note: ${tool.description.take(200)}")
                if (tool.parameters.isNotEmpty()) {
                    append(" | System awareness note: ${tool.parameters.joinToString(", ") { p ->
                        "${p.name}(${if (p.required) "System awareness note" else "System awareness note"})"
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
        Log.d(TAG, "📋 System awareness note ${tools.size} System awareness note System awareness note System awareness note System awareness note")
    }

    private suspend fun registerInitialBestPractices() {
        val practices = listOf(
            Triple(
                "file_operations",
                "System awareness note System awareness note System awareness note: System awareness note read_file_lines System awareness note System awareness note. System awareness note System awareness note (+1MB) System awareness note find_files System awareness note grep_search System awareness note System awareness note System awareness note System awareness note.",
                "file,read,performance"
            ),
            Triple(
                "memory_usage",
                "System awareness note System awareness note System awareness note System awareness note search_knowledge System awareness note System awareness note System awareness note System awareness note System awareness note. System awareness note System awareness note System awareness note System awareness note remember_fact. System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.",
                "memory,context,efficiency"
            ),
            Triple(
                "terminal_safety",
                "System awareness note System awareness note System awareness note terminal System awareness note: System awareness note dry-run System awareness note echo System awareness note. System awareness note rm -rf. System awareness note paths System awareness note System awareness note.",
                "terminal,safety,commands"
            ),
            Triple(
                "web_search_strategy",
                "System awareness note: System awareness note System awareness note web_search (System awareness note). System awareness note web_scraper System awareness note System awareness note. System awareness note headless_browser System awareness note System awareness note System awareness note System awareness note JavaScript.",
                "web,search,strategy"
            ),
            Triple(
                "git_workflow",
                "System awareness note System awareness note Git: System awareness note System awareness note System awareness note System awareness note → System awareness note System awareness note System awareness note System awareness note → commit System awareness note → System awareness note System awareness note System awareness note System awareness note main",
                "git,workflow,best_practice"
            ),
            Triple(
                "error_handling",
                "System awareness note System awareness note: System awareness note System awareness note System awareness note System awareness note → System awareness note System awareness note System awareness note → System awareness note System awareness note System awareness note → System awareness note System awareness note System awareness note remember_fact",
                "error,debugging,recovery"
            ),
            Triple(
                "tool_selection",
                "System awareness note System awareness note System awareness note System awareness note. System awareness note: System awareness note System awareness note System awareness note System awareness note grep_search (System awareness note) System awareness note read_file. System awareness note System awareness note get_device_info System awareness note shizuku_command.",
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

    // ─── System awareness note System awareness note ───────────────────────────────────────────

    /**
     * System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note
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
                    content = "⚠️ $toolName System awareness note System awareness note System awareness note. System awareness note: ${errorMessage.take(150)}",
                    confidence = 0.9f,
                    priority = 2,
                    tags = "permission,requirement,$toolName"
                )
            }

            !success && errorMessage.contains("not available", ignoreCase = true) -> {
                saveOrUpdateKnowledge(
                    type = TYPE_TOOL_LIMITATION,
                    subject = toolName,
                    content = "🚫 $toolName System awareness note System awareness note System awareness note System awareness note System awareness note: ${errorMessage.take(150)}",
                    confidence = 0.95f,
                    priority = 1,
                    tags = "unavailable,limitation,$toolName"
                )
            }

            !success && executionTimeMs > 30_000 -> {
                saveOrUpdateKnowledge(
                    type = TYPE_TOOL_LIMITATION,
                    subject = "${toolName}_timeout",
                    content = "⏱️ $toolName System awareness note System awareness note System awareness note System awareness note System awareness note (${executionTimeMs}ms). System awareness note System awareness note System awareness note.",
                    confidence = 0.8f,
                    priority = 2,
                    tags = "timeout,performance,$toolName"
                )
            }

            success && executionTimeMs < 200 -> {
                // System awareness note System awareness note System awareness note - System awareness note System awareness note
                saveOrUpdateKnowledge(
                    type = TYPE_TOOL_CAPABILITY,
                    subject = "${toolName}_performance",
                    content = "⚡ $toolName System awareness note System awareness note (avg ~${executionTimeMs}ms) - System awareness note System awareness note System awareness note",
                    confidence = 0.7f,
                    priority = 7,
                    tags = "fast,performance,$toolName"
                )
            }
        }
    }

    /**
     * System awareness note System awareness note System awareness note System awareness note System awareness note
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
     * System awareness note System awareness note System awareness note System awareness note Agent
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

    // ─── System awareness note System Prompt Context ───────────────────────────────────

    /**
     * System awareness note System awareness note System awareness note System awareness note System Prompt
     * System awareness note System awareness note System awareness note System awareness note Agent "System awareness note" System awareness note System awareness note
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

            // System awareness note System awareness note
            if (environments.isNotEmpty() || systemInfo.isNotEmpty()) {
                appendLine("\n📱 System awareness note System awareness note:")
                (environments + systemInfo.filter { it.subject.contains("android") || it.subject == "memory" })
                    .take(6).forEach { k ->
                        appendLine("  • ${k.content.take(120)}")
                    }
            }

            // System awareness note System awareness note (System awareness note System awareness note!)
            if (limitations.isNotEmpty() || warnings.isNotEmpty()) {
                appendLine("\n⚠️ System awareness note System awareness note (System awareness note System awareness note System awareness note):")
                (limitations + warnings).take(5).forEach { k ->
                    appendLine("  ✗ ${k.content.take(120)}")
                }
            }

            // System awareness note System awareness note
            if (bestPractices.isNotEmpty()) {
                appendLine("\n💡 System awareness note System awareness note:")
                bestPractices.take(5).forEach { k ->
                    appendLine("  ✓ [${k.subject}] ${k.content.take(150)}")
                }
            }

            // System awareness note System awareness note
            if (capabilities.isNotEmpty()) {
                appendLine("\n⚡ System awareness note System awareness note: ${capabilities.joinToString(", ") { it.subject }}")
            }

            appendLine("══════════════════════════════════════════════")
        }
    }

    /**
     * System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note
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
     * System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System Prompt
     */
    suspend fun getCriticalKnowledge(): List<SystemKnowledgeEntry> = withContext(Dispatchers.IO) {
        systemKnowledgeDao.getForSystemPrompt(maxPriority = 3, limit = 10)
    }

    // ─── System awareness note System awareness note ─────────────────────────────────────────────

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
            Log.w(TAG, "Failed to save knowledge entry: $subject - ${e.message}")
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

    // ─── Flow System awareness note ─────────────────────────────────────────────────

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

    // ─── System awareness note ────────────────────────────────────────────────────

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
