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
 * SmartLearningBridge —
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *      :
 * - ToolExecutionJournal ( )
 * - ToolAwarenessEngine ( )
 * - ToolIntelligenceEngine (RL-based decision making)
 * - ToolMachineLearningEngine (ML prediction)
 * - ToolMonitoringSystem (real-time monitoring)
 *
 * :
 * 1.
 * 2.  System Prompt Context
 * 3.
 * 4.
 * 5.
 *
 *  :
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
     * Agent Brain 2.0 —  Reflexion (   ).
     * :  null     .
     */
    private val reflexionEngine: com.omnidev.workspace.data.brain.ReflexionEngine? = null,
    /**
     * Agent Brain 2.0 —    (episodes ).
     * :  null    episodes .
     */
    private val episodicMemoryStore: com.omnidev.workspace.data.brain.EpisodicMemoryStore? = null,
    /**
     * Progressive Trust Engine —     .
     * :  null    trust tracking.
     */
    private val progressiveTrustEngine: com.omnidev.workspace.data.brain.ProgressiveTrustEngine? = null,
    /**
     * Causal Chain Planner Tool —       System Prompt.
     * :  null   .
     */
    private val causalChainPlannerTool: com.omnidev.workspace.data.tools.CausalChainPlannerTool? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    /** Run-local intent, timing and tool history; learned knowledge remains shared. */
    fun forkForRun() = SmartLearningBridge(context, journal.forkForRun(), awarenessEngine,
        intelligenceEngine, mlEngine, monitoringSystem, reflexionEngine, episodicMemoryStore,
        progressiveTrustEngine, causalChainPlannerTool, scope)

    companion object {
        private const val TAG = "SmartLearning"

        //     System Prompt —   2-4 GB RAM
        private const val MAX_CONTEXT_CHARS = 2000
        private const val MAX_TOOL_HISTORY_ITEMS = 5
        private const val PERSIST_INTERVAL_MS = 30_000L
        private const val MIN_ML_CONFIDENCE = 0.6
        private const val MIN_RL_CONFIDENCE = 0.6
        private const val MIN_RL_CONSENSUS_CONFIDENCE = 0.55
        private const val MIN_ML_ALTERNATIVE_CONFIDENCE = 0.4
        private const val MAX_RECOMMENDATION_CANDIDATES = 2

        // Agent Brain 2.0 —   (    )
        private const val REFLEXION_MAX_CHARS = 500
        private const val EPISODIC_MAX_CHARS = 600
    }

    // ───  ───────────────────────────────────────────────────────

    private val sessionToolHistory = mutableListOf<String>()
    private val toolExecutionStartTimes = ConcurrentHashMap<String, Long>()
    private val availableToolNamesSnapshot = AtomicReference<List<String>>(emptyList())
    private var sessionId: String = "session_${System.currentTimeMillis()}"
    private var persistenceJob: kotlinx.coroutines.Job? = null

    // Agent Brain 2.0 —  user intent  + start time  episode logging
    @Volatile private var currentUserIntent: String = ""
    @Volatile private var currentTaskStartMs: Long = 0L
    @Volatile private var currentTaskIterations: Int = 0

    // ───    ─────────────────────────────────────────────

    /**
     *   Agent  -
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
        Log.d(TAG, "🚀   : $sessionId | : $agentMode")
    }

    /**
     *   AgentPipeline      (user message).
     *   user intent  episodes  +   episode .
     */
    fun onTaskStart(userIntent: String) {
        synchronized(sessionToolHistory) { sessionToolHistory.clear() }
        currentUserIntent = userIntent.take(200)
        currentTaskStartMs = System.currentTimeMillis()
        currentTaskIterations = 0
    }

    /**
     *     (//). :
     *   1) ReflexionEngine
     *   2) EpisodicMemoryStore   episode
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

    /**    iterations  (  AgentPipeline). */
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
     *
     *   AgentPipeline
     */
    suspend fun registerTools(tools: List<ToolDefinition>) = withContext(Dispatchers.IO) {
        availableToolNamesSnapshot.set(tools.map { it.name })
        awarenessEngine.initialize(tools)
    }

    /**
     *
     * @param toolName
     * @param callId      (    )
     */
    fun onToolExecutionStart(toolName: String, callId: String = toolName) {
        toolExecutionStartTimes[callId] = System.currentTimeMillis()
    }

    /**
     * ══════════════════════════════════════════════════════
     * onToolExecutionEnd —
     * ══════════════════════════════════════════════════════
     *
     * @param callId       onToolExecutionStart
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

        // ─── 1.     ─────────────────────────────
        scope.launch(Dispatchers.IO) {
            journal.recordToolExecution(
                toolName = toolName,
                parameters = parameters,
                result = result,
                executionTimeMs = executionTimeMs,
                agentContext = agentContext
            )
        }

        // ─── 2.     ────────────────────────────────
        scope.launch(Dispatchers.IO) {
            awarenessEngine.learnFromExecution(
                toolName = toolName,
                success = !result.isError,
                errorMessage = if (result.isError) result.output else "",
                executionTimeMs = executionTimeMs,
                params = parameters
            )
        }

        // ─── 3.  ML Engine ─────────────────────────────────────
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

        // ─── 4.  RL Intelligence Engine ───────────────────────
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

        // ─── 5.     ──────────────────────────────
        monitoringSystem?.let { monitor ->
            //    (     )
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

        // ─── 5b. Agent Brain 2.0 — Reflexion learning   ───
        //      (failures / slow / large output)
        //       .
        reflexionEngine?.recordExperienceAsync(
            toolName = toolName,
            parameters = parameters,
            result = result,
            executionTimeMs = executionTimeMs,
            userIntent = currentUserIntent
        )

        // ─── 5c. Progressive Trust —       ──
        //  synchronously (< 1ms) —
        if (!result.isError) {
            progressiveTrustEngine?.onOperationSuccess(toolName)
        } else {
            progressiveTrustEngine?.onOperationFailure(toolName)
        }

        // ─── 6.     ─────────────────────────
        synchronized(sessionToolHistory) {
            sessionToolHistory.add(toolName)
            if (sessionToolHistory.size > 50) sessionToolHistory.removeAt(0)
        }

        // ─── 7.    ─────────────────────────────
        if (sessionToolHistory.size >= 2 && !result.isError) {
            val prevTool = sessionToolHistory.getOrNull(sessionToolHistory.size - 2)
            if (prevTool != null) {
                scope.launch(Dispatchers.IO) {
                    discoverAndRecordDependency(prevTool, toolName)
                }
            }
        }

        Log.d(TAG, "🔄  : $toolName | : ${!result.isError} | : ${executionTimeMs}ms")
    }

    // ───  System Prompt Enrichment ───────────────────────────────

    /**
     * ══════════════════════════════════════════════════════
     * buildFullContextEnrichment —
     * ══════════════════════════════════════════════════════
     *     System Prompt    Agent
     *   :
     */
    suspend fun buildFullContextEnrichment(): String = withContext(Dispatchers.IO) {
        val parts = mutableListOf<String>()

        // 1.
        val awarenessCtx = awarenessEngine.buildSystemPromptContext()
        if (awarenessCtx.isNotBlank()) parts.add(awarenessCtx)

        // 2.
        val memoryCtx = journal.buildMemoryContext()
        if (memoryCtx != null) parts.add(memoryCtx)

        // 3. Agent Brain 2.0 — Episodic Memory (  )
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

        // 4. Agent Brain 2.0 — Reflexion (   / )
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

        // 4b. Progressive Trust —       prompt
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

        // 5.    ( N )
        val historySnapshot = synchronized(sessionToolHistory) { sessionToolHistory.toList() }
        if (historySnapshot.size > 2) {
            val sessionCtx = buildString {
                appendLine("\n🔗   :")
                appendLine(" : ${historySnapshot.takeLast(MAX_TOOL_HISTORY_ITEMS).joinToString(" → ")}")
            }
            parts.add(sessionCtx)
        }

        // 6.    ( ML)
        val recommendation = getToolRecommendation()
        if (recommendation != null) {
            parts.add("\n🎯 : $recommendation")
        }

        //
        val combined = parts.joinToString("")
        if (combined.length > MAX_CONTEXT_CHARS) {
            combined.take(MAX_CONTEXT_CHARS) + "\n[...context truncated...]"
        } else {
            combined
        }
    }

    /**
     *  System Prompt
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

    // ───   ─────────────────────────────────────────────

    /**
     *
     */
    suspend fun getToolRecommendation(): String? = withContext(Dispatchers.Default) {
        if (sessionToolHistory.isEmpty()) return@withContext null

        val lastTool = sessionToolHistory.lastOrNull() ?: return@withContext null
        val context = buildExecutionContext()
        val availableTools = availableToolNamesSnapshot.get()
        if (availableTools.isEmpty()) return@withContext null
        val recentToolsContext = sessionToolHistory.takeLast(3).joinToString(",")

        //  RL Intelligence Engine
        val rlPrediction = intelligenceEngine?.predictBestTool(
            taskDescription = buildRecommendationTaskDescription(lastTool, recentToolsContext, context.timeOfDay),
            availableTools = availableTools,
            currentContext = context
        )

        //  ML Engine
        val mlPrediction = mlEngine?.predictNextTool(
            currentTool = lastTool,
            recentTools = sessionToolHistory.takeLast(3),
            contextualData = mapOf(
                "hour" to context.timeOfDay
            )
        )

        //  :  RL + ML
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
                return@withContext " $lastTool  : $topMlToolName ( RL+ML)"
            }
        }

        // RL
        val rlConfidence = rlPrediction?.confidence?.toDouble()
        if (rlConfidence != null && rlConfidence > MIN_RL_CONFIDENCE) {
            val alternatives = rlPrediction.alternatives
                .take(MAX_RECOMMENDATION_CANDIDATES)
                .joinToString("  ") { "${it.name} (${(it.score * 100).toInt()}%)" }
            return@withContext if (alternatives.isBlank()) {
                " $lastTool  : ${rlPrediction.recommendedTool} (${(rlPrediction.confidence * 100).toInt()}%)"
            } else {
                " $lastTool  : ${rlPrediction.recommendedTool} (${(rlPrediction.confidence * 100).toInt()}%) — : $alternatives"
            }
        }

        if (mlPrediction != null && mlPrediction.confidence > MIN_ML_CONFIDENCE) {
            val suggested = mlPrediction.suggestedTools.take(MAX_RECOMMENDATION_CANDIDATES)
                .filter { it.second > MIN_ML_ALTERNATIVE_CONFIDENCE }
                .joinToString("  ") { "${it.first} (${(it.second * 100).toInt()}%)" }
            if (suggested.isNotBlank()) {
                return@withContext " $lastTool  : $suggested"
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
     *
     */
    suspend fun getContextForTool(toolName: String): String? = withContext(Dispatchers.IO) {
        val awarenessInfo = awarenessEngine.getToolKnowledge(toolName)
        val historyReport = journal.getToolHistory(toolName)

        buildString {
            awarenessInfo?.let { append(it) }

            if (historyReport.totalUses > 0) {
                appendLine("\n📊  $toolName: ${historyReport.totalUses}  | : ${(historyReport.successRate * 100).toInt()}%")

                if (historyReport.commonErrors.isNotEmpty()) {
                    appendLine("⚠️  : ${historyReport.commonErrors.first().take(80)}")
                }

                if (historyReport.commonNextTools.isNotEmpty()) {
                    appendLine("🔗   : ${historyReport.commonNextTools.take(3).joinToString(", ")}")
                }

                historyReport.learningNotes.firstOrNull()?.let {
                    appendLine("💡 $it")
                }
            }
        }.takeIf { it.isNotBlank() }
    }

    // ───   ────────────────────────────────────────────

    /**
     *
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
     *
     */
    suspend fun performMaintenance() = withContext(Dispatchers.IO) {
        //
        val threeMonthsAgo = System.currentTimeMillis() - (90L * 24 * 3600_000)
        //

        Log.d(TAG, "🧹   ")
    }

    // ───   ─────────────────────────────────────────────

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
            batteryLevel = 80, //
            networkType = "wifi" //
        )
    }

    private suspend fun discoverAndRecordDependency(toolA: String, toolB: String) {
        //
        val key = "$toolA→$toolB"

        //
        val recentHistory = sessionToolHistory.takeLast(30)
        var occurrences = 0
        val safeSize = recentHistory.size
        for (i in 0 until safeSize - 1) {
            if (recentHistory.getOrNull(i) == toolA && recentHistory.getOrNull(i + 1) == toolB) {
                occurrences++
            }
        }

        //     3
        if (occurrences >= 3) {
            awarenessEngine.recordPattern(
                patternName = key,
                description = " :  $toolA  $toolB  $occurrences ",
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
