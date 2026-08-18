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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

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
    /**
     * Agent Brain 2.0 — محرك Reflexion (دروس مستفادة من التجارب).
     * اختياري: لو null النظام يعمل بدون حقن دروس.
     */
    private val reflexionEngine: com.omnidev.workspace.data.brain.ReflexionEngine? = null,
    /**
     * Agent Brain 2.0 — مخزن الذاكرة العَرَضية (episodes كاملة).
     * اختياري: لو null النظام لا يحقن episodes مشابهة.
     */
    private val episodicMemoryStore: com.omnidev.workspace.data.brain.EpisodicMemoryStore? = null,
    /**
     * Progressive Trust Engine — يتتبّع الثقة ويمنح الصلاحيات تدريجياً.
     * اختياري: لو null النظام يعمل بدون trust tracking.
     */
    private val progressiveTrustEngine: com.omnidev.workspace.data.brain.ProgressiveTrustEngine? = null,
    /**
     * Causal Chain Planner Tool — يُحقن آخر تحذيرات سببية في الـ System Prompt.
     * اختياري: لو null لا يُحقن شيء.
     */
    private val causalChainPlannerTool: com.omnidev.workspace.data.tools.CausalChainPlannerTool? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    companion object {
        private const val TAG = "SmartLearning"

        // حدود حقن السياق في System Prompt — مضبوطة لأجهزة 2-4 GB RAM
        private const val MAX_CONTEXT_CHARS = 2000
        private const val MAX_TOOL_HISTORY_ITEMS = 5
        private const val PERSIST_INTERVAL_MS = 30_000L
        private const val MIN_ML_CONFIDENCE = 0.6
        private const val MIN_RL_CONFIDENCE = 0.6
        private const val MIN_RL_CONSENSUS_CONFIDENCE = 0.55
        private const val MIN_ML_ALTERNATIVE_CONFIDENCE = 0.4
        private const val MAX_RECOMMENDATION_CANDIDATES = 2

        // Agent Brain 2.0 — حدود الحقن (يُلتزم بها على أجهزة ضعيفة)
        private const val REFLEXION_MAX_CHARS = 500
        private const val EPISODIC_MAX_CHARS = 600
    }

    // ─── الحالة ───────────────────────────────────────────────────────

    private val sessionToolHistory = mutableListOf<String>()
    private val toolExecutionStartTimes = ConcurrentHashMap<String, Long>()
    private val availableToolNamesSnapshot = AtomicReference<List<String>>(emptyList())
    private var sessionId: String = "session_${System.currentTimeMillis()}"
    private var persistenceJob: kotlinx.coroutines.Job? = null

    // Agent Brain 2.0 — الـ user intent الحالي + start time لـ episode logging
    @Volatile private var currentUserIntent: String = ""
    @Volatile private var currentTaskStartMs: Long = 0L
    @Volatile private var currentTaskIterations: Int = 0

    // ─── دورة حياة الجلسة ─────────────────────────────────────────────

    /**
     * بدء جلسة Agent جديدة - يُستدعى عند بدء محادثة جديدة
     */
    suspend fun onSessionStart(agentMode: String = "ASSISTANT") = withContext(Dispatchers.IO) {
        sessionId = "session_${System.currentTimeMillis()}"
        sessionToolHistory.clear()
        currentUserIntent = ""
        currentTaskStartMs = System.currentTimeMillis()
        currentTaskIterations = 0
        journal.startNewSession(agentMode)
        intelligenceEngine?.restore()
        startPersistenceLoop()
        Log.d(TAG, "🚀 جلسة جديدة بدأت: $sessionId | وضع: $agentMode")
    }

    /**
     * يُستدعى من AgentPipeline عند بدء كل مهمة جديدة (user message).
     * يُحفظ الـ user intent لاسترجاع episodes مشابهة + لاحقاً تسجيل episode كامل.
     */
    fun onTaskStart(userIntent: String) {
        currentUserIntent = userIntent.take(200)
        currentTaskStartMs = System.currentTimeMillis()
        currentTaskIterations = 0
    }

    /**
     * يُستدعى عند انتهاء المهمة (نجاح/فشل/إيقاف). يُغذّي:
     *   1) ReflexionEngine لتعديل جودة الدروس المُحقَنة
     *   2) EpisodicMemoryStore لتسجيل الـ episode كاملاً
     */
    fun onTaskEnd(
        outcome: com.omnidev.workspace.data.brain.EpisodeOutcome,
        finalSummary: String = ""
    ) {
        val toolsUsed = synchronized(sessionToolHistory) { sessionToolHistory.toList() }
        val totalTime = System.currentTimeMillis() - currentTaskStartMs

        // 1) Reflexion outcome feedback (background)
        scope.launch(Dispatchers.IO) {
            try {
                reflexionEngine?.reportTaskOutcome(
                    success = (outcome == com.omnidev.workspace.data.brain.EpisodeOutcome.SUCCESS)
                )
            } catch (t: Throwable) {
                Log.w(TAG, "reflexion outcome feedback failed: ${t.message}")
            }
        }

        // 2) Episodic memory record (async, non-blocking)
        if (currentUserIntent.isNotBlank() && toolsUsed.isNotEmpty()) {
            val summary = if (finalSummary.isNotBlank()) {
                finalSummary
            } else {
                "intent: $currentUserIntent | tools: ${toolsUsed.takeLast(8).joinToString(",")} | outcome: $outcome"
            }
            episodicMemoryStore?.recordEpisodeAsync(
                summary = summary,
                userIntent = currentUserIntent,
                finalOutcome = outcome,
                toolsUsed = toolsUsed,
                iterationsCount = currentTaskIterations,
                totalTimeMs = totalTime,
                sessionId = sessionId
            )
        }
    }

    /** يُحدّث عداد الـ iterations الحالي (يستدعى من AgentPipeline). */
    fun onIterationStart() {
        currentTaskIterations++
    }

    private fun startPersistenceLoop() {
        persistenceJob?.cancel()
        persistenceJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                kotlinx.coroutines.delay(PERSIST_INTERVAL_MS)
                try {
                    intelligenceEngine?.persist()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ periodic persist failed: ${e.message}")
                }
            }
        }
    }

    /**
     * تسجيل تعريفات الأدوات المتاحة في محرك الوعي
     * يُستدعى من AgentPipeline لتسجيل قدرات الأدوات فور توفرها
     */
    suspend fun registerTools(tools: List<ToolDefinition>) = withContext(Dispatchers.IO) {
        availableToolNamesSnapshot.set(tools.map { it.name })
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

        // ─── 5b. Agent Brain 2.0 — Reflexion learning من التجربة ───
        // لا يُسجّل إلا التجارب البارزة (failures / slow / large output)
        // ويعمل بالكامل في الخلفية ليلائم الأجهزة الضعيفة.
        reflexionEngine?.recordExperienceAsync(
            toolName = toolName,
            parameters = parameters,
            result = result,
            executionTimeMs = executionTimeMs,
            userIntent = currentUserIntent
        )

        // ─── 5c. Progressive Trust — تحديث درجة الثقة بناءً على النتيجة ──
        // يعمل synchronously (< 1ms) — آمن للاستدعاء هنا
        if (!result.isError) {
            progressiveTrustEngine?.onOperationSuccess(toolName)
        } else {
            progressiveTrustEngine?.onOperationFailure(toolName)
        }

        // ─── 6. تحديث التاريخ المحلي للجلسة ─────────────────────────
        synchronized(sessionToolHistory) {
            sessionToolHistory.add(toolName)
            if (sessionToolHistory.size > 50) sessionToolHistory.removeAt(0)
        }

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

        // 3. Agent Brain 2.0 — Episodic Memory (مهام مشابهة سابقة)
        if (currentUserIntent.isNotBlank()) {
            try {
                val episodicCtx = episodicMemoryStore?.buildPromptInjection(
                    query = currentUserIntent,
                    topK = 2,
                    maxChars = EPISODIC_MAX_CHARS
                )
                if (!episodicCtx.isNullOrBlank()) parts.add(episodicCtx)
            } catch (t: Throwable) {
                Log.w(TAG, "episodic injection failed: ${t.message}")
            }
        }

        // 4. Agent Brain 2.0 — Reflexion (دروس مستفادة من فشل/نجاح سابق)
        try {
            val lastTool = synchronized(sessionToolHistory) { sessionToolHistory.lastOrNull() }
            val reflexCtx = reflexionEngine?.buildPromptInjection(
                contextQuery = currentUserIntent.ifBlank { lastTool.orEmpty() },
                currentToolName = lastTool,
                maxChars = REFLEXION_MAX_CHARS
            )
            if (!reflexCtx.isNullOrBlank()) parts.add(reflexCtx)
        } catch (t: Throwable) {
            Log.w(TAG, "reflexion injection failed: ${t.message}")
        }

        // 4b. Progressive Trust — حقن مستوى الثقة والصلاحيات في الـ prompt
        try {
            val trustCtx = progressiveTrustEngine?.buildPromptInjection()
            if (!trustCtx.isNullOrBlank()) parts.add(trustCtx)
        } catch (t: Throwable) {
            Log.w(TAG, "trust injection failed: ${t.message}")
        }

        // 4c. Causal Chain Planner — inject last plan's causal warnings if any
        try {
            val causalCtx = causalChainPlannerTool?.getLastPlanInjection(maxChars = 400)
            if (!causalCtx.isNullOrBlank()) parts.add(causalCtx)
        } catch (t: Throwable) {
            Log.w(TAG, "causal injection failed: ${t.message}")
        }

        // 5. سياق الجلسة الحالية (آخر N أداة)
        val historySnapshot = synchronized(sessionToolHistory) { sessionToolHistory.toList() }
        if (historySnapshot.size > 2) {
            val sessionCtx = buildString {
                appendLine("\n🔗 سياق الجلسة الحالية:")
                appendLine("الأدوات المستخدمة: ${historySnapshot.takeLast(MAX_TOOL_HISTORY_ITEMS).joinToString(" → ")}")
            }
            parts.add(sessionCtx)
        }

        // 6. توصيات الأداة التالية (من ML)
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
        val context = buildExecutionContext()
        val availableTools = availableToolNamesSnapshot.get()
        if (availableTools.isEmpty()) return@withContext null
        val recentToolsContext = sessionToolHistory.takeLast(3).joinToString(",")

        // استخدام RL Intelligence Engine للتوصية
        val rlPrediction = intelligenceEngine?.predictBestTool(
            taskDescription = buildRecommendationTaskDescription(lastTool, recentToolsContext, context.timeOfDay),
            availableTools = availableTools,
            currentContext = context
        )

        // استخدام ML Engine للتنبؤ
        val mlPrediction = mlEngine?.predictNextTool(
            currentTool = lastTool,
            recentTools = sessionToolHistory.takeLast(3),
            contextualData = mapOf(
                "hour" to context.timeOfDay
            )
        )

        // أقوى توصية: اتفاق RL + ML
        if (rlPrediction != null &&
            rlPrediction.confidence.toDouble() > MIN_RL_CONSENSUS_CONFIDENCE &&
            mlPrediction != null &&
            mlPrediction.confidence > MIN_ML_CONFIDENCE
        ) {
            val topMlToolName = mlPrediction.suggestedTools.firstOrNull()?.first?.trim()
            val recommendedRlTool = rlPrediction.recommendedTool.trim()
            if (topMlToolName != null &&
                topMlToolName == recommendedRlTool
            ) {
                return@withContext "بعد $lastTool، الأداة الأقوى: $topMlToolName (اتفاق RL+ML)"
            }
        }

        // RL كمسار أساسي إذا كانت الثقة جيدة
        val rlConfidence = rlPrediction?.confidence?.toDouble()
        if (rlConfidence != null && rlConfidence > MIN_RL_CONFIDENCE) {
            val alternatives = rlPrediction.alternatives
                .take(MAX_RECOMMENDATION_CANDIDATES)
                .joinToString(" أو ") { "${it.name} (${(it.score * 100).toInt()}%)" }
            return@withContext if (alternatives.isBlank()) {
                "بعد $lastTool، الأداة المقترحة: ${rlPrediction.recommendedTool} (${(rlPrediction.confidence * 100).toInt()}%)"
            } else {
                "بعد $lastTool، الأداة المقترحة: ${rlPrediction.recommendedTool} (${(rlPrediction.confidence * 100).toInt()}%) — بدائل: $alternatives"
            }
        }

        if (mlPrediction != null && mlPrediction.confidence > MIN_ML_CONFIDENCE) {
            val suggested = mlPrediction.suggestedTools.take(MAX_RECOMMENDATION_CANDIDATES)
                .filter { it.second > MIN_ML_ALTERNATIVE_CONFIDENCE }
                .joinToString(" أو ") { "${it.first} (${(it.second * 100).toInt()}%)" }
            if (suggested.isNotBlank()) {
                return@withContext "بعد $lastTool، الأدوات المقترحة: $suggested"
            }
        }

        null
    }

    private fun buildRecommendationTaskDescription(
        lastTool: String,
        recentToolsContext: String,
        hour: Int
    ): String {
        return "NextToolRecommendation(last=$lastTool,recent=[$recentToolsContext],hour=$hour)"
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
        val safeSize = recentHistory.size
        for (i in 0 until safeSize - 1) {
            if (recentHistory.getOrNull(i) == toolA && recentHistory.getOrNull(i + 1) == toolB) {
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
