package com.omnidev.workspace.data.brain

import android.content.Context
import android.util.Log
import com.omnidev.workspace.data.db.dao.ToolExecutionDao
import com.omnidev.workspace.data.db.dao.SystemKnowledgeDao
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolExecutionResult
import com.omnidev.workspace.data.tools.orchestration.ToolIntelligenceEngine
import com.omnidev.workspace.data.tools.ml.ToolMachineLearningEngine
import com.omnidev.workspace.data.tools.monitoring.ToolMonitoringSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * SmartLearningBridge — الجسر الذكي للتعلم والتحسين
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * هذا هو العقل المنسق الذي يربط:
 * - ToolExecutionJournal (الذاكرة الدائمة)
 * - ToolAwarenessEngine (الوعي بالبيئة)
 * - ToolIntelligenceEngine (RL-based decision making)
 * - ToolMachineLearningEngine (ML prediction)
 * - ToolMonitoringSystem (real-time monitoring)
 *
 * وظيفته:
 * 1. ينسق التعلم بين كل المحركات
 * 2. يبني System Prompt Context الموحد
 * 3. يقدم توصيات ذكية بالأداة الأنسب
 * 4. يكتشف ويحل المشاكل تلقائياً
 * 5. يتحسن مع كل تنفيذ
 *
 * مستوحى من:
 * - Claude Code: Self-improving context awareness
 * - GitHub Copilot: Contextual tool suggestion
 * - Gemini Assistant: Cross-session learning
 */
class SmartLearningBridge(
    private val context: Context,
    private val journal: ToolExecutionJournal,
    private val awarenessEngine: ToolAwarenessEngine,
    private val intelligenceEngine: ToolIntelligenceEngine?,
    private val mlEngine: ToolMachineLearningEngine?,
    private val monitoringSystem: ToolMonitoringSystem?,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    companion object {
        private const val TAG = "SmartLearning"

        // حدود حقن السياق في System Prompt
        private const val MAX_CONTEXT_CHARS = 2000
        private const val MAX_TOOL_HISTORY_ITEMS = 5
    }

    // ─── الحالة ───────────────────────────────────────────────────────

    private val sessionToolHistory = mutableListOf<String>()
    private val toolExecutionStartTimes = ConcurrentHashMap<String, Long>()
    private var sessionId: String = "session_${System.currentTimeMillis()}"

    // ─── دورة حياة الجلسة ─────────────────────────────────────────────

    /**
     * بدء جلسة Agent جديدة - يُستدعى عند بدء محادثة جديدة
     */
    suspend fun onSessionStart(agentMode: String = "ASSISTANT") = withContext(Dispatchers.IO) {
        sessionId = "session_${System.currentTimeMillis()}"
        sessionToolHistory.clear()
        journal.startNewSession(agentMode)
        Log.d(TAG, "🚀 جلسة جديدة بدأت: $sessionId | وضع: $agentMode")
    }

    /**
     * تسجيل تعريفات الأدوات المتاحة في محرك الوعي
     * يُستدعى من AgentPipeline لتسجيل قدرات الأدوات فور توفرها
     */
    suspend fun registerTools(tools: List<ToolDefinition>) = withContext(Dispatchers.IO) {
        awarenessEngine.initialize(tools)
    }

    /**
     * تسجيل بداية تنفيذ أداة
     * @param toolName اسم الأداة
     * @param callId معرف فريد لهذا الاستدعاء المحدد (يتيح تتبع نفس الأداة بالتوازي)
     */
    fun onToolExecutionStart(toolName: String, callId: String = toolName) {
        toolExecutionStartTimes[callId] = System.currentTimeMillis()
    }

    /**
     * ══════════════════════════════════════════════════════
     * onToolExecutionEnd — قلب نظام التعلم
     * ══════════════════════════════════════════════════════
     * يُستدعى بعد كل تنفيذ أداة ليوزع التعلم على كل المحركات
     * @param callId معرف فريد مطابق لما مُرّر إلى onToolExecutionStart
     */
    suspend fun onToolExecutionEnd(
        toolName: String,
        parameters: Map<String, Any?>,
        result: ToolExecutionResult,
        agentContext: String = "",
        callId: String = toolName
    ) = withContext(Dispatchers.Default) {
        val startTime = toolExecutionStartTimes.remove(callId) ?: System.currentTimeMillis()
        val executionTimeMs = System.currentTimeMillis() - startTime

        // ─── 1. تسجيل في المجلة الدائمة ─────────────────────────────
        scope.launch(Dispatchers.IO) {
            journal.recordToolExecution(
                toolName = toolName,
                parameters = parameters,
                result = result,
                executionTimeMs = executionTimeMs,
                agentContext = agentContext
            )
        }

        // ─── 2. التعلم في محرك الوعي ────────────────────────────────
        scope.launch(Dispatchers.IO) {
            awarenessEngine.learnFromExecution(
                toolName = toolName,
                success = !result.isError,
                errorMessage = if (result.isError) result.output else "",
                executionTimeMs = executionTimeMs,
                params = parameters
            )
        }

        // ─── 3. تحديث ML Engine ─────────────────────────────────────
        scope.launch {
            mlEngine?.recordExecution(
                toolName = toolName,
                parameters = parameters.mapNotNull { (k, v) -> v?.let { k to it } }.toMap(),
                result = result,
                executionTimeMs = executionTimeMs,
                contextualData = mapOf(
                    "session_id" to sessionId,
                    "recent_tools" to sessionToolHistory.takeLast(3).joinToString(","),
                    "hour" to Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                )
            )
        }

        // ─── 4. تحديث RL Intelligence Engine ───────────────────────
        scope.launch {
            intelligenceEngine?.recordExecution(
                toolName = toolName,
                parameters = parameters.mapValues { it.value?.toString() ?: "" },
                executionTimeMs = executionTimeMs,
                success = !result.isError,
                resultQuality = estimateQuality(result, executionTimeMs),
                context = buildExecutionContext()
            )
        }

        // ─── 5. تسجيل في نظام المراقبة ──────────────────────────────
        monitoringSystem?.let { monitor ->
            // نسجّل كأثر فوري (بدء ثم نهاية في نفس الوقت) لأن التنفيذ انتهى بالفعل
            val traceId = monitor.startExecution(
                toolName = toolName,
                parameters = parameters.mapValues { it.value?.toString() ?: "" }
            )
            monitor.endExecution(
                traceId = traceId,
                success = !result.isError,
                errorMessage = if (result.isError) result.output.take(200) else null
            )
        }

        // ─── 6. تحديث التاريخ المحلي للجلسة ─────────────────────────
        sessionToolHistory.add(toolName)
        if (sessionToolHistory.size > 50) sessionToolHistory.removeAt(0)

        // ─── 7. تحليل التبعيات تلقائياً ─────────────────────────────
        if (sessionToolHistory.size >= 2 && !result.isError) {
            val prevTool = sessionToolHistory.getOrNull(sessionToolHistory.size - 2)
            if (prevTool != null) {
                scope.launch(Dispatchers.IO) {
                    discoverAndRecordDependency(prevTool, toolName)
                }
            }
        }

        Log.d(TAG, "🔄 تعلّم من: $toolName | نجاح: ${!result.isError} | وقت: ${executionTimeMs}ms")
    }

    // ─── بناء System Prompt Enrichment ───────────────────────────────

    /**
     * ══════════════════════════════════════════════════════
     * buildFullContextEnrichment — أهم دالة في النظام
     * ══════════════════════════════════════════════════════
     * تبني الحقن الكامل للـ System Prompt الذي يجعل الـ Agent
     * واعياً بكل شيء: الأدوات، النظام، التاريخ، الأنماط
     */
    suspend fun buildFullContextEnrichment(): String = withContext(Dispatchers.IO) {
        val parts = mutableListOf<String>()

        // 1. وعي الأدوات والنظام
        val awarenessCtx = awarenessEngine.buildSystemPromptContext()
        if (awarenessCtx.isNotBlank()) parts.add(awarenessCtx)

        // 2. ذاكرة التنفيذ
        val memoryCtx = journal.buildMemoryContext()
        if (memoryCtx != null) parts.add(memoryCtx)

        // 3. سياق الجلسة الحالية (آخر N أداة)
        if (sessionToolHistory.size > 2) {
            val sessionCtx = buildString {
                appendLine("\n🔗 سياق الجلسة الحالية:")
                appendLine("الأدوات المستخدمة: ${sessionToolHistory.takeLast(MAX_TOOL_HISTORY_ITEMS).joinToString(" → ")}")
            }
            parts.add(sessionCtx)
        }

        // 4. توصيات الأداة التالية (من ML)
        val recommendation = getToolRecommendation()
        if (recommendation != null) {
            parts.add("\n🎯 توصية: $recommendation")
        }

        // دمج وتقليص الحجم
        val combined = parts.joinToString("")
        if (combined.length > MAX_CONTEXT_CHARS) {
            combined.take(MAX_CONTEXT_CHARS) + "\n[...context truncated...]"
        } else {
            combined
        }
    }

    /**
     * يبني System Prompt أساسي محسّن
     */
    suspend fun buildEnrichedSystemPrompt(baseSystemPrompt: String): String = withContext(Dispatchers.IO) {
        val enrichment = buildFullContextEnrichment()
        if (enrichment.isBlank()) return@withContext baseSystemPrompt

        buildString {
            append(baseSystemPrompt)
            append("\n\n")
            append(enrichment)
        }
    }

    // ─── التوصيات الذكية ─────────────────────────────────────────────

    /**
     * يحصل على توصية الأداة التالية بناءً على السياق
     */
    suspend fun getToolRecommendation(): String? = withContext(Dispatchers.Default) {
        if (sessionToolHistory.isEmpty()) return@withContext null

        val lastTool = sessionToolHistory.lastOrNull() ?: return@withContext null

        // استخدام ML Engine للتنبؤ
        val mlPrediction = mlEngine?.predictNextTool(
            currentTool = lastTool,
            recentTools = sessionToolHistory.takeLast(3),
            contextualData = mapOf(
                "hour" to Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            )
        )

        if (mlPrediction != null && mlPrediction.confidence > 0.6) {
            val suggested = mlPrediction.suggestedTools.take(2)
                .filter { it.second > 0.4 }
                .joinToString(" أو ") { "${it.first} (${(it.second * 100).toInt()}%)" }
            if (suggested.isNotBlank()) {
                return@withContext "بعد $lastTool، الأدوات المقترحة: $suggested"
            }
        }

        null
    }

    /**
     * يحصل على معرفة ذات صلة بأداة معينة
     */
    suspend fun getContextForTool(toolName: String): String? = withContext(Dispatchers.IO) {
        val awarenessInfo = awarenessEngine.getToolKnowledge(toolName)
        val historyReport = journal.getToolHistory(toolName)

        buildString {
            awarenessInfo?.let { append(it) }

            if (historyReport.totalUses > 0) {
                appendLine("\n📊 سجل $toolName: ${historyReport.totalUses} استخدام | نجاح: ${(historyReport.successRate * 100).toInt()}%")

                if (historyReport.commonErrors.isNotEmpty()) {
                    appendLine("⚠️ أخطاء شائعة: ${historyReport.commonErrors.first().take(80)}")
                }

                if (historyReport.commonNextTools.isNotEmpty()) {
                    appendLine("🔗 عادةً يُستخدم بعدها: ${historyReport.commonNextTools.take(3).joinToString(", ")}")
                }

                historyReport.learningNotes.firstOrNull()?.let {
                    appendLine("💡 $it")
                }
            }
        }.takeIf { it.isNotBlank() }
    }

    // ─── التحليل والتحسين ────────────────────────────────────────────

    /**
     * تقرير شامل عن أداء النظام
     */
    suspend fun generatePerformanceReport(): PerformanceReport = withContext(Dispatchers.IO) {
        val journalSummary = journal.analyzeAllTools()
        val awarenessStats = awarenessEngine.getStats()

        PerformanceReport(
            totalToolExecutions = journalSummary.totalOperations,
            overallSuccessRate = journalSummary.overallSuccessRate,
            bestTool = journalSummary.bestPerformingTool,
            worstTool = journalSummary.worstPerformingTool,
            mostUsedTool = journalSummary.mostUsedTool,
            problematicTools = journalSummary.problematicTools,
            totalKnowledgeEntries = awarenessStats.totalKnowledge,
            sessionToolCount = sessionToolHistory.size,
            environmentStatus = buildEnvironmentStatus(awarenessStats)
        )
    }

    /**
     * يُنظّف البيانات القديمة
     */
    suspend fun performMaintenance() = withContext(Dispatchers.IO) {
        // حذف بيانات قديمة من قاعدة البيانات
        val threeMonthsAgo = System.currentTimeMillis() - (90L * 24 * 3600_000)
        // يمكن توسيع هذا لاحقاً

        Log.d(TAG, "🧹 صيانة دورية مكتملة")
    }

    // ─── الدوال المساعدة ─────────────────────────────────────────────

    private fun estimateQuality(result: ToolExecutionResult, timeMs: Long): Float {
        if (result.isError) return 0f
        return when {
            timeMs < 500 && result.output.length > 10 -> 0.9f
            timeMs < 3000 && result.output.length > 5 -> 0.75f
            result.output.length > 0 -> 0.5f
            else -> 0.3f
        }
    }

    private fun buildExecutionContext(): ToolIntelligenceEngine.ExecutionContext {
        val cal = Calendar.getInstance()
        return ToolIntelligenceEngine.ExecutionContext(
            previousTool = sessionToolHistory.lastOrNull(),
            timeOfDay = cal.get(Calendar.HOUR_OF_DAY),
            dayOfWeek = cal.get(Calendar.DAY_OF_WEEK),
            batteryLevel = 80, // يمكن الحصول على القيمة الحقيقية لاحقاً
            networkType = "wifi" // يمكن الحصول على القيمة الحقيقية لاحقاً
        )
    }

    private suspend fun discoverAndRecordDependency(toolA: String, toolB: String) {
        // فحص إذا كانت هذه التبعية مكتشفة من قبل
        val key = "$toolA→$toolB"

        // عدّ عدد مرات حدوث هذا التسلسل
        val recentHistory = sessionToolHistory.takeLast(30)
        var occurrences = 0
        for (i in 0 until recentHistory.size - 1) {
            if (recentHistory[i] == toolA && recentHistory[i + 1] == toolB) {
                occurrences++
            }
        }

        // إذا تكرر أكثر من 3 مرات، سجّله كنمط
        if (occurrences >= 3) {
            awarenessEngine.recordPattern(
                patternName = key,
                description = "تسلسل متكرر: بعد $toolA يُستخدم $toolB في $occurrences مناسبة",
                confidence = (occurrences / 10f).coerceIn(0.5f, 1.0f)
            )
        }
    }

    private fun buildEnvironmentStatus(stats: ToolAwarenessEngine.AwarenessStats): String {
        return buildString {
            stats.environmentCache.forEach { (env, available) ->
                val icon = if (available == "true") "✅" else "❌"
                append("$icon $env  ")
            }
        }
    }

    // ─── Data Classes ─────────────────────────────────────────────────

    data class PerformanceReport(
        val totalToolExecutions: Int,
        val overallSuccessRate: Float,
        val bestTool: String?,
        val worstTool: String?,
        val mostUsedTool: String?,
        val problematicTools: List<String>,
        val totalKnowledgeEntries: Int,
        val sessionToolCount: Int,
        val environmentStatus: String
    )
}
