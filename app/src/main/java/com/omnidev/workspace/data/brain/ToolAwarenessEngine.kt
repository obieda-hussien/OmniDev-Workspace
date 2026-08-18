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
 * ToolAwarenessEngine — محرك الوعي بالأدوات والنظام
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * يبني وعياً عميقاً ومستمراً عن:
 * 1. الأدوات المتاحة وقدراتها ومتطلباتها
 * 2. حالة النظام والبيئة (Android, Termux, Shizuku, etc.)
 * 3. التبعيات بين الأدوات
 * 4. القيود والحدود لكل أداة
 * 5. أفضل استراتيجيات الاستخدام
 *
 * مستوحى من نهج Claude Code في:
 * - فهم البيئة قبل التنفيذ
 * - تحديث المعرفة بناءً على التجربة
 * - تقديم context enrichment ذكي
 */
class ToolAwarenessEngine(
    private val context: Context,
    private val systemKnowledgeDao: SystemKnowledgeDao,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    companion object {
        private const val TAG = "ToolAwareness"

        // أنواع المعرفة
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

    // ─── الحالة الحالية ───────────────────────────────────────────────

    private val runtimeEnvironmentCache = mutableMapOf<String, String>()
    private var isInitialized = false

    // ─── الإعداد الأولي ───────────────────────────────────────────────

    /**
     * الإعداد الأولي: يكتشف البيئة ويسجل المعرفة الأساسية
     */
    suspend fun initialize(availableTools: List<ToolDefinition> = emptyList()) = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext
        
        Log.d(TAG, "🔍 بدء اكتشاف النظام والأدوات...")

        // 1. معلومات النظام الأساسية
        discoverSystemEnvironment()

        // 2. فحص الأدوات المتاحة
        if (availableTools.isNotEmpty()) {
            registerToolCapabilities(availableTools)
        }

        // 3. فحص قدرات الجهاز
        discoverDeviceCapabilities()

        // 4. فحص البيئات المتاحة
        discoverRuntimeEnvironments()

        // 5. تسجيل أفضل الممارسات المبدئية
        registerInitialBestPractices()

        isInitialized = true
        Log.d(TAG, "✅ اكتمل اكتشاف النظام - ${systemKnowledgeDao.getCount()} معرفة محفوظة")
    }

    // ─── اكتشاف البيئة ───────────────────────────────────────────────

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

        // حالة Android API
        val apiLevel = Build.VERSION.SDK_INT
        when {
            apiLevel >= 33 -> saveOrUpdateKnowledge(
                type = TYPE_SYSTEM_INFO, subject = "android_api",
                content = "Android 13+ (API $apiLevel): كامل القدرات. MediaStore محدود، Scoped Storage إلزامي.",
                priority = 2
            )
            apiLevel >= 30 -> saveOrUpdateKnowledge(
                type = TYPE_SYSTEM_INFO, subject = "android_api",
                content = "Android 11+ (API $apiLevel): Scoped Storage. بعض عمليات الملفات تحتاج MANAGE_EXTERNAL_STORAGE.",
                priority = 2
            )
            apiLevel >= 26 -> saveOrUpdateKnowledge(
                type = TYPE_SYSTEM_INFO, subject = "android_api",
                content = "Android 8+ (API $apiLevel): JobScheduler متاح. Background Limits مُطبَّقة.",
                priority = 3
            )
        }
    }

    private suspend fun discoverDeviceCapabilities() {
        val pm = context.packageManager

        // فحص الكاميرا
        val hasCamera = pm.hasSystemFeature("android.hardware.camera")
        if (hasCamera) {
            saveKnowledge(TYPE_SYSTEM_CAPABILITY, "camera", "الجهاز يدعم الكاميرا", priority = 8)
        }

        // فحص البلوتوث
        val hasBluetooth = pm.hasSystemFeature("android.hardware.bluetooth")
        if (hasBluetooth) {
            saveKnowledge(TYPE_SYSTEM_CAPABILITY, "bluetooth", "الجهاز يدعم البلوتوث", priority = 8)
        }

        // حجم الذاكرة
        val runtime = Runtime.getRuntime()
        val maxMemMB = runtime.maxMemory() / (1024 * 1024)
        saveKnowledge(
            TYPE_SYSTEM_INFO, "memory",
            "ذاكرة JVM متاحة: ${maxMemMB}MB - استخدم عمليات streaming للملفات الكبيرة",
            priority = 4,
            tags = "memory,performance,heap"
        )

        // حجم التخزين
        try {
            val dataDir = context.filesDir
            val free = dataDir.freeSpace / (1024 * 1024)
            val total = dataDir.totalSpace / (1024 * 1024)
            saveKnowledge(
                TYPE_SYSTEM_INFO, "storage",
                "التخزين: ${free}MB حر من أصل ${total}MB",
                priority = 5,
                tags = "storage,disk,space"
            )
        } catch (_: Exception) {}
    }

    private suspend fun discoverRuntimeEnvironments() {
        // فحص Termux
        val termuxInstalled = isPackageInstalled("com.termux")
        runtimeEnvironmentCache["termux"] = termuxInstalled.toString()
        if (termuxInstalled) {
            saveKnowledge(
                TYPE_ENVIRONMENT, "termux",
                "Termux متاح: يمكن تشغيل Python, Node.js, bash, gcc, git عبر termux_bridge",
                confidence = 0.9f,
                priority = 2,
                tags = "termux,python,nodejs,bash,linux"
            )
        } else {
            saveKnowledge(
                TYPE_WARNING, "termux",
                "Termux غير متاح: استخدم AgentRuntimeTool للكود أو agent_sandbox",
                confidence = 1.0f,
                priority = 3,
                tags = "termux,warning"
            )
        }

        // فحص Shizuku
        val shizukuInstalled = isPackageInstalled("moe.shizuku.privileged.api")
        runtimeEnvironmentCache["shizuku"] = shizukuInstalled.toString()
        if (shizukuInstalled) {
            saveKnowledge(
                TYPE_ENVIRONMENT, "shizuku",
                "Shizuku متاح: يمكن تنفيذ أوامر ADB-level بدون root عبر shizuku_command",
                confidence = 0.8f,
                priority = 2,
                tags = "shizuku,adb,privileged,root"
            )
        } else {
            saveKnowledge(
                TYPE_WARNING, "shizuku",
                "Shizuku غير متاح: بعض أوامر النظام المتميزة لن تعمل",
                confidence = 1.0f,
                priority = 3,
                tags = "shizuku,warning"
            )
        }

        // فحص Python مباشرة
        val pythonExists = File("/data/data/com.termux/files/usr/bin/python3").exists() ||
                           File("/data/data/com.termux/files/usr/bin/python").exists()
        if (pythonExists) {
            saveKnowledge(
                TYPE_ENVIRONMENT, "python",
                "Python متاح في Termux: استخدم agent_runtime/python_run لتنفيذ الكود",
                confidence = 0.95f,
                priority = 2,
                tags = "python,termux,runtime,code"
            )
        }

        // فحص Git
        val gitExists = File("/data/data/com.termux/files/usr/bin/git").exists()
        if (gitExists) {
            saveKnowledge(
                TYPE_ENVIRONMENT, "git",
                "Git متاح في Termux: استخدم git_manager أو terminal لعمليات Git",
                confidence = 0.95f,
                priority = 3,
                tags = "git,termux,vcs"
            )
        }
    }

    private suspend fun registerToolCapabilities(tools: List<ToolDefinition>) {
        tools.forEach { tool ->
            val capability = buildString {
                append("الأداة: ${tool.name}")
                append(" | الوصف: ${tool.description.take(200)}")
                if (tool.parameters.isNotEmpty()) {
                    append(" | المعاملات: ${tool.parameters.joinToString(", ") { p ->
                        "${p.name}(${if (p.required) "مطلوب" else "اختياري"})"
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
        Log.d(TAG, "📋 سُجِّل ${tools.size} أداة في قاعدة المعرفة")
    }

    private suspend fun registerInitialBestPractices() {
        val practices = listOf(
            Triple(
                "file_operations",
                "عند قراءة الملفات: استخدم read_file_lines للملفات الصغيرة. للملفات الكبيرة (+1MB) استخدم find_files أو grep_search أولاً لتحديد المقطع المطلوب.",
                "file,read,performance"
            ),
            Triple(
                "memory_usage",
                "ابدأ كل مهمة بـ search_knowledge للبحث عن معلومات ذات صلة. احفظ القرارات المهمة بـ remember_fact. لا تكرر البحث عن نفس المعلومة.",
                "memory,context,efficiency"
            ),
            Triple(
                "terminal_safety",
                "قبل تنفيذ أوامر terminal خطرة: استخدم dry-run أو echo أولاً. تجنب rm -rf. استخدم paths مطلقة دائماً.",
                "terminal,safety,commands"
            ),
            Triple(
                "web_search_strategy",
                "للبحث: ابدأ بـ web_search (سريع). استخدم web_scraper للصفحات المحددة. استخدم headless_browser فقط للمواقع التي تحتاج JavaScript.",
                "web,search,strategy"
            ),
            Triple(
                "git_workflow",
                "سير عمل Git: تحقق من الفروع أولاً → اعمل في فرع مؤقت → commit متكرر → لا تدفع مباشرة للـ main",
                "git,workflow,best_practice"
            ),
            Triple(
                "error_handling",
                "عند الفشل: اقرأ رسالة الخطأ بالكامل → تحقق من الصلاحيات → جرب بديلاً أبسط → سجل التعلم بـ remember_fact",
                "error,debugging,recovery"
            ),
            Triple(
                "tool_selection",
                "اختر الأداة الأبسط أولاً. مثال: للبحث في الكود استخدم grep_search (أسرع) قبل read_file. للنظام استخدم get_device_info قبل shizuku_command.",
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

    // ─── التحديث الديناميكي ───────────────────────────────────────────

    /**
     * يحدث المعرفة بناءً على تجربة تنفيذ أداة
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
                    content = "⚠️ $toolName تحتاج صلاحيات خاصة. خطأ: ${errorMessage.take(150)}",
                    confidence = 0.9f,
                    priority = 2,
                    tags = "permission,requirement,$toolName"
                )
            }

            !success && errorMessage.contains("not available", ignoreCase = true) -> {
                saveOrUpdateKnowledge(
                    type = TYPE_TOOL_LIMITATION,
                    subject = toolName,
                    content = "🚫 $toolName غير متاحة في هذه البيئة: ${errorMessage.take(150)}",
                    confidence = 0.95f,
                    priority = 1,
                    tags = "unavailable,limitation,$toolName"
                )
            }

            !success && executionTimeMs > 30_000 -> {
                saveOrUpdateKnowledge(
                    type = TYPE_TOOL_LIMITATION,
                    subject = "${toolName}_timeout",
                    content = "⏱️ $toolName تنتهي مدتها عند معاملات معينة (${executionTimeMs}ms). استخدم نطاقاً أضيق.",
                    confidence = 0.8f,
                    priority = 2,
                    tags = "timeout,performance,$toolName"
                )
            }

            success && executionTimeMs < 200 -> {
                // أداة سريعة جداً - معلومة مفيدة
                saveOrUpdateKnowledge(
                    type = TYPE_TOOL_CAPABILITY,
                    subject = "${toolName}_performance",
                    content = "⚡ $toolName سريعة جداً (avg ~${executionTimeMs}ms) - يمكن استخدامها بحرية",
                    confidence = 0.7f,
                    priority = 7,
                    tags = "fast,performance,$toolName"
                )
            }
        }
    }

    /**
     * يكتشف ويسجل تبعية بين أداتين
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
     * يسجل نمطاً اكتشفه الـ Agent
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

    // ─── بناء System Prompt Context ───────────────────────────────────

    /**
     * يبني حقن السياق لـ System Prompt
     * هذا ما يجعل الـ Agent "واعياً" بالبيئة والأدوات
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

            // معلومات البيئة
            if (environments.isNotEmpty() || systemInfo.isNotEmpty()) {
                appendLine("\n📱 البيئة المتاحة:")
                (environments + systemInfo.filter { it.subject.contains("android") || it.subject == "memory" })
                    .take(6).forEach { k ->
                        appendLine("  • ${k.content.take(120)}")
                    }
            }

            // القيود والتحذيرات (مهم جداً!)
            if (limitations.isNotEmpty() || warnings.isNotEmpty()) {
                appendLine("\n⚠️ قيود معروفة (تجنّب هذه الأخطاء):")
                (limitations + warnings).take(5).forEach { k ->
                    appendLine("  ✗ ${k.content.take(120)}")
                }
            }

            // أفضل الممارسات
            if (bestPractices.isNotEmpty()) {
                appendLine("\n💡 أفضل الممارسات:")
                bestPractices.take(5).forEach { k ->
                    appendLine("  ✓ [${k.subject}] ${k.content.take(150)}")
                }
            }

            // قدرات خاصة
            if (capabilities.isNotEmpty()) {
                appendLine("\n⚡ قدرات متاحة: ${capabilities.joinToString(", ") { it.subject }}")
            }

            appendLine("══════════════════════════════════════════════")
        }
    }

    /**
     * يبحث عن معرفة ذات صلة بأداة معينة
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
     * يسترجع كل المعرفة ذات الأولوية العالية لحقنها في System Prompt
     */
    suspend fun getCriticalKnowledge(): List<SystemKnowledgeEntry> = withContext(Dispatchers.IO) {
        systemKnowledgeDao.getForSystemPrompt(maxPriority = 3, limit = 10)
    }

    // ─── الدوال المساعدة ─────────────────────────────────────────────

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
            Log.w(TAG, "فشل في حفظ المعرفة: $subject - ${e.message}")
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

    // ─── Flow للواجهة ─────────────────────────────────────────────────

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

    // ─── إحصائيات ────────────────────────────────────────────────────

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
