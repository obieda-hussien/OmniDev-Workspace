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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * ToolAwarenessEngine —
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *     :
 * 1.
 * 2.    (Android, Termux, Shizuku, etc.)
 * 3.
 * 4.
 * 5.
 *
 *    Claude Code :
 * -
 * -
 * -  context enrichment
 */
class ToolAwarenessEngine(
    private val context: Context,
    private val systemKnowledgeDao: SystemKnowledgeDao,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    companion object {
        private const val TAG = "ToolAwareness"

        //
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

    // ───   ───────────────────────────────────────────────

    private val runtimeEnvironmentCache = mutableMapOf<String, String>()
    private var isInitialized = false
    private val initializationMutex = Mutex()

    // ───   ───────────────────────────────────────────────

    /**
     *  :
     */
    suspend fun initialize(availableTools: List<ToolDefinition> = emptyList()) = withContext(Dispatchers.IO) { initializationMutex.withLock {
        if (isInitialized) return@withLock
        
        Log.d(TAG, "🔍    ...")

        // 1.
        discoverSystemEnvironment()

        // 2.
        if (availableTools.isNotEmpty()) {
            registerToolCapabilities(availableTools)
        }

        // 3.
        discoverDeviceCapabilities()

        // 4.
        discoverRuntimeEnvironments()

        // 5.
        registerInitialBestPractices()

        isInitialized = true
        Log.d(TAG, "✅    - ${systemKnowledgeDao.getCount()}  ")
    } }

    // ───   ───────────────────────────────────────────────

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

        //  Android API
        val apiLevel = Build.VERSION.SDK_INT
        when {
            apiLevel >= 33 -> saveOrUpdateKnowledge(
                type = TYPE_SYSTEM_INFO, subject = "android_api",
                content = "Android 13+ (API $apiLevel):  . MediaStore  Scoped Storage .",
                priority = 2
            )
            apiLevel >= 30 -> saveOrUpdateKnowledge(
                type = TYPE_SYSTEM_INFO, subject = "android_api",
                content = "Android 11+ (API $apiLevel): Scoped Storage.     MANAGE_EXTERNAL_STORAGE.",
                priority = 2
            )
            apiLevel >= 26 -> saveOrUpdateKnowledge(
                type = TYPE_SYSTEM_INFO, subject = "android_api",
                content = "Android 8+ (API $apiLevel): JobScheduler . Background Limits .",
                priority = 3
            )
        }
    }

    private suspend fun discoverDeviceCapabilities() {
        val pm = context.packageManager

        //
        val hasCamera = pm.hasSystemFeature("android.hardware.camera")
        if (hasCamera) {
            saveKnowledge(TYPE_SYSTEM_CAPABILITY, "camera", "Camera hardware detected; permission must be checked before use.", priority = 8)
        }

        //
        val hasBluetooth = pm.hasSystemFeature("android.hardware.bluetooth")
        if (hasBluetooth) {
            saveKnowledge(TYPE_SYSTEM_CAPABILITY, "bluetooth", "Bluetooth hardware detected; permission and adapter state must be checked before use.", priority = 8)
        }

        //
        val runtime = Runtime.getRuntime()
        val maxMemMB = runtime.maxMemory() / (1024 * 1024)
        saveKnowledge(
            TYPE_SYSTEM_INFO, "memory",
            " JVM : ${maxMemMB}MB -   streaming  ",
            priority = 4,
            tags = "memory,performance,heap"
        )

        //
        try {
            val dataDir = context.filesDir
            val free = dataDir.freeSpace / (1024 * 1024)
            val total = dataDir.totalSpace / (1024 * 1024)
            saveKnowledge(
                TYPE_SYSTEM_INFO, "storage",
                ": ${free}MB    ${total}MB",
                priority = 5,
                tags = "storage,disk,space"
            )
        } catch (_: Exception) {}
    }

    private suspend fun discoverRuntimeEnvironments() {
        //  Termux
        val termuxInstalled = isPackageInstalled("com.termux")
        runtimeEnvironmentCache["termux"] = termuxInstalled.toString()
        if (termuxInstalled) {
            saveKnowledge(
                TYPE_ENVIRONMENT, "termux",
                "Termux :   Python, Node.js, bash, gcc, git  termux_bridge",
                confidence = 0.9f,
                priority = 2,
                tags = "termux,python,nodejs,bash,linux"
            )
        } else {
            saveKnowledge(
                TYPE_WARNING, "termux",
                "Termux  :  AgentRuntimeTool   agent_sandbox",
                confidence = 1.0f,
                priority = 3,
                tags = "termux,warning"
            )
        }

        //  Shizuku
        val shizukuInstalled = isPackageInstalled("moe.shizuku.privileged.api")
        runtimeEnvironmentCache["shizuku"] = shizukuInstalled.toString()
        if (shizukuInstalled) {
            saveKnowledge(
                TYPE_ENVIRONMENT, "shizuku",
                "Shizuku package detected. Check service and permission before shizuku_command; this does not imply root access.",
                confidence = 0.8f,
                priority = 2,
                tags = "shizuku,adb,privileged,root"
            )
        } else {
            saveKnowledge(
                TYPE_WARNING, "shizuku",
                "Shizuku  :      ",
                confidence = 1.0f,
                priority = 3,
                tags = "shizuku,warning"
            )
        }

        //  Python
        val pythonExists = File("/data/data/com.termux/files/usr/bin/python3").exists() ||
                           File("/data/data/com.termux/files/usr/bin/python").exists()
        if (pythonExists) {
            saveKnowledge(
                TYPE_ENVIRONMENT, "python",
                "Python   Termux:  agent_runtime/python_run  ",
                confidence = 0.95f,
                priority = 2,
                tags = "python,termux,runtime,code"
            )
        }

        //  Git
        val gitExists = File("/data/data/com.termux/files/usr/bin/git").exists()
        if (gitExists) {
            saveKnowledge(
                TYPE_ENVIRONMENT, "git",
                "Git   Termux:  git_manager  terminal  Git",
                confidence = 0.95f,
                priority = 3,
                tags = "git,termux,vcs"
            )
        }
    }

    private suspend fun registerToolCapabilities(tools: List<ToolDefinition>) {
        tools.forEach { tool ->
            val capability = buildString {
                append(": ${tool.name}")
                append(" | : ${tool.description.take(200)}")
                if (tool.parameters.isNotEmpty()) {
                    append(" | : ${tool.parameters.joinToString(", ") { p ->
                        "${p.name}(${if (p.required) "required" else "optional"})"
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
        Log.d(TAG, "📋  ${tools.size}    ")
    }

    private suspend fun registerInitialBestPractices() {
        val practices = listOf(
            Triple(
                "file_operations",
                "  :  read_file_lines  .   (+1MB)  find_files  grep_search    .",
                "file,read,performance"
            ),
            Triple(
                "memory_usage",
                "    search_knowledge     .     remember_fact.      .",
                "memory,context,efficiency"
            ),
            Triple(
                "terminal_safety",
                "   terminal :  dry-run  echo .  rm -rf.  paths  .",
                "terminal,safety,commands"
            ),
            Triple(
                "web_search_strategy",
                ":   web_search ().  web_scraper  .  headless_browser     JavaScript.",
                "web,search,strategy"
            ),
            Triple(
                "git_workflow",
                "  Git:     →     → commit  →     main",
                "git,workflow,best_practice"
            ),
            Triple(
                "error_handling",
                " :     →    →    →    remember_fact",
                "error,debugging,recovery"
            ),
            Triple(
                "tool_selection",
                "   . :     grep_search ()  read_file.   get_device_info  shizuku_command.",
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

    // ───   ───────────────────────────────────────────

    /**
     *
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
                    content = "⚠️ $toolName   . : ${errorMessage.take(150)}",
                    confidence = 0.9f,
                    priority = 2,
                    tags = "permission,requirement,$toolName"
                )
            }

            !success && errorMessage.contains("not available", ignoreCase = true) -> {
                saveOrUpdateKnowledge(
                    type = TYPE_TOOL_LIMITATION,
                    subject = toolName,
                    content = "🚫 $toolName     : ${errorMessage.take(150)}",
                    confidence = 0.95f,
                    priority = 1,
                    tags = "unavailable,limitation,$toolName"
                )
            }

            !success && executionTimeMs > 30_000 -> {
                saveOrUpdateKnowledge(
                    type = TYPE_TOOL_LIMITATION,
                    subject = "${toolName}_timeout",
                    content = "⏱️ $toolName      (${executionTimeMs}ms).   .",
                    confidence = 0.8f,
                    priority = 2,
                    tags = "timeout,performance,$toolName"
                )
            }

            success && executionTimeMs < 200 -> {
                //    -
                saveOrUpdateKnowledge(
                    type = TYPE_TOOL_CAPABILITY,
                    subject = "${toolName}_performance",
                    content = "⚡ $toolName   (avg ~${executionTimeMs}ms) -   ",
                    confidence = 0.7f,
                    priority = 7,
                    tags = "fast,performance,$toolName"
                )
            }
        }
    }

    /**
     *
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
     *     Agent
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

    // ───  System Prompt Context ───────────────────────────────────

    /**
     *     System Prompt
     *     Agent "English Text"
     */
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

            //
            if (environments.isNotEmpty() || systemInfo.isNotEmpty()) {
                appendLine("\n📱  :")
                (environments + systemInfo.filter { it.subject.contains("android") || it.subject == "memory" })
                    .take(6).forEach { k ->
                        appendLine("  • ${k.content.take(120)}")
                    }
            }

            //   ( !)
            if (limitations.isNotEmpty() || warnings.isNotEmpty()) {
                appendLine("\n⚠️   (  ):")
                (limitations + warnings).take(5).forEach { k ->
                    appendLine("  ✗ ${k.content.take(120)}")
                }
            }

            //
            if (bestPractices.isNotEmpty()) {
                appendLine("\n💡 Best Practices:")
                bestPractices.take(5).forEach { k ->
                    appendLine("  ✓ [${k.subject}] ${k.content.take(150)}")
                }
            }

            //
            if (capabilities.isNotEmpty()) {
                appendLine("\nDetected hardware (not permission grants): ${capabilities.distinctBy { it.subject }.joinToString(", ") { it.subject.take(60) }}")
            }

            appendLine("══════════════════════════════════════════════")
        }
    }

    /**
     *
     */
    suspend fun getToolKnowledge(toolName: String): String? = withContext(Dispatchers.IO) {
        val entries = systemKnowledgeDao.search(toolName, limit = 8)
        if (entries.isEmpty()) return@withContext null

        buildString {
            entries.forEach { k ->
                when (k.knowledgeType) {
                    TYPE_TOOL_LIMITATION -> appendLine("⚠️ ${k.content.take(300)}")
                    TYPE_TOOL_REQUIREMENT -> appendLine("📋 ${k.content.take(300)}")
                    TYPE_BEST_PRACTICE -> appendLine("💡 ${k.content.take(300)}")
                    TYPE_WARNING -> appendLine("🚨 ${k.content.take(300)}")
                    else -> appendLine("ℹ️ ${k.content.take(300)}")
                }
            }
        }.takeIf { it.isNotBlank() }
    }

    /**
     *         System Prompt
     */
    suspend fun getCriticalKnowledge(): List<SystemKnowledgeEntry> = withContext(Dispatchers.IO) {
        systemKnowledgeDao.getForSystemPrompt(maxPriority = 3, limit = 10)
    }

    // ───   ─────────────────────────────────────────────

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
            Log.w(TAG, "   : $subject - ${e.message}")
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
        saveKnowledge(type, subject, content, confidence, priority, tags, source)
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    // ─── Flow  ─────────────────────────────────────────────────

    fun observeKnowledge(): Flow<List<SystemKnowledgeEntry>> = systemKnowledgeDao.observeAllValid()

    suspend fun clearKnowledgeLog() = withContext(Dispatchers.IO) {
        initializationMutex.withLock { systemKnowledgeDao.clearAll() }
    }

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

    // ───  ────────────────────────────────────────────────────

    suspend fun getStats(): AwarenessStats = withContext(Dispatchers.IO) {
        val byType = systemKnowledgeDao.countsByType().associate { it.knowledgeType to it.count }
        val total = byType.values.sum()

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
