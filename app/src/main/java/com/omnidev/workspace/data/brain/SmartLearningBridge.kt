package com.omnidev.workspace.data.brain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolExecutionResult
import com.omnidev.workspace.data.tools.ml.ToolMachineLearningEngine
import com.omnidev.workspace.data.tools.monitoring.ToolMonitoringSystem
import com.omnidev.workspace.data.tools.orchestration.ToolIntelligenceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Local learning bridge for the Agent Brain.
 *
 * Persistent knowledge engines are shared across runs, while mutable task state (intent,
 * tool history, timers and active Reflexion lessons) is isolated by [forkForRun]. This keeps
 * parallel Team workers from contaminating each other's feedback.
 */
class SmartLearningBridge(
    private val context: Context,
    private val journal: ToolExecutionJournal,
    private val awarenessEngine: ToolAwarenessEngine,
    private val intelligenceEngine: ToolIntelligenceEngine?,
    private val mlEngine: ToolMachineLearningEngine?,
    private val monitoringSystem: ToolMonitoringSystem?,
    private val reflexionEngine: ReflexionEngine? = null,
    private val episodicMemoryStore: EpisodicMemoryStore? = null,
    private val progressiveTrustEngine: ProgressiveTrustEngine? = null,
    private val causalChainPlannerTool: com.omnidev.workspace.data.tools.CausalChainPlannerTool? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    /** Persistent knowledge is shared; all run-local mutable attribution is forked. */
    fun forkForRun() = SmartLearningBridge(
        context = context,
        journal = journal.forkForRun(),
        awarenessEngine = awarenessEngine,
        intelligenceEngine = intelligenceEngine,
        mlEngine = mlEngine,
        monitoringSystem = monitoringSystem,
        reflexionEngine = reflexionEngine?.forkForRun(),
        episodicMemoryStore = episodicMemoryStore,
        progressiveTrustEngine = progressiveTrustEngine,
        causalChainPlannerTool = causalChainPlannerTool,
        scope = scope
    )

    companion object {
        private const val TAG = "SmartLearning"
        private const val MAX_CONTEXT_CHARS = 2000
        private const val MAX_TOOL_HISTORY_ITEMS = 5
        private const val PERSIST_INTERVAL_MS = 30_000L
        private const val MIN_ML_CONFIDENCE = 0.6
        private const val MIN_RL_CONFIDENCE = 0.6
        private const val MIN_RL_CONSENSUS_CONFIDENCE = 0.55
        private const val MIN_ML_ALTERNATIVE_CONFIDENCE = 0.4
        private const val MAX_RECOMMENDATION_CANDIDATES = 2
        private const val REFLEXION_MAX_CHARS = 500
        private const val EPISODIC_MAX_CHARS = 600
    }

    private val sessionToolHistory = mutableListOf<String>()
    private val toolExecutionStartTimes = ConcurrentHashMap<String, Long>()
    private val availableToolNamesSnapshot = AtomicReference<List<String>>(emptyList())
    private var sessionId: String = "session_${System.currentTimeMillis()}"
    private var persistenceJob: kotlinx.coroutines.Job? = null

    @Volatile private var currentUserIntent: String = ""
    @Volatile private var currentTaskStartMs: Long = 0L
    @Volatile private var currentTaskIterations: Int = 0

    suspend fun onSessionStart(agentMode: String = "ASSISTANT") = withContext(Dispatchers.IO) {
        sessionId = "session_${System.currentTimeMillis()}"
        synchronized(sessionToolHistory) { sessionToolHistory.clear() }
        currentUserIntent = ""
        currentTaskStartMs = System.currentTimeMillis()
        currentTaskIterations = 0
        journal.startNewSession(agentMode)
        intelligenceEngine?.restore()
        startPersistenceLoop()
        Log.d(TAG, "Session started: $sessionId | mode=$agentMode")
    }

    fun onTaskStart(userIntent: String) {
        synchronized(sessionToolHistory) { sessionToolHistory.clear() }
        currentUserIntent = userIntent.take(200)
        currentTaskStartMs = System.currentTimeMillis()
        currentTaskIterations = 0
    }

    /**
     * Closes the feedback loop for every task, including tasks that used zero tools.
     * Previously no-tool tasks vanished from episodic memory entirely, so conversational
     * successes/failures could never influence future behavior.
     */
    fun onTaskEnd(
        outcome: EpisodeOutcome,
        finalSummary: String = ""
    ) {
        val toolsUsed = synchronized(sessionToolHistory) { sessionToolHistory.toList() }
        val totalTime = (System.currentTimeMillis() - currentTaskStartMs).coerceAtLeast(0L)

        scope.launch(Dispatchers.IO) {
            try {
                reflexionEngine?.reportTaskOutcome(success = outcome == EpisodeOutcome.SUCCESS)
            } catch (t: Throwable) {
                Log.w(TAG, "reflexion outcome feedback failed: ${t.message}")
            }
        }

        if (currentUserIntent.isNotBlank()) {
            val summary = if (finalSummary.isNotBlank()) {
                finalSummary
            } else {
                buildString {
                    append("intent: $currentUserIntent | outcome: $outcome")
                    if (toolsUsed.isNotEmpty()) append(" | tools: ${toolsUsed.takeLast(8).joinToString(",")}")
                }
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
                    Log.w(TAG, "periodic persist failed: ${e.message}")
                }
            }
        }
    }

    suspend fun registerTools(tools: List<ToolDefinition>) = withContext(Dispatchers.IO) {
        availableToolNamesSnapshot.set(tools.map { it.name })
        awarenessEngine.initialize(tools)
    }

    fun onToolExecutionStart(toolName: String, callId: String = toolName) {
        toolExecutionStartTimes[callId] = System.currentTimeMillis()
    }

    suspend fun onToolExecutionEnd(
        toolName: String,
        parameters: Map<String, Any?>,
        result: ToolExecutionResult,
        agentContext: String = "",
        callId: String = toolName
    ) = withContext(Dispatchers.Default) {
        val startTime = toolExecutionStartTimes.remove(callId) ?: System.currentTimeMillis()
        val executionTimeMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
        val recentBefore = synchronized(sessionToolHistory) { sessionToolHistory.takeLast(3) }

        scope.launch(Dispatchers.IO) {
            journal.recordToolExecution(
                toolName = toolName,
                parameters = parameters,
                result = result,
                executionTimeMs = executionTimeMs,
                agentContext = agentContext
            )
        }

        scope.launch(Dispatchers.IO) {
            awarenessEngine.learnFromExecution(
                toolName = toolName,
                success = !result.isError,
                errorMessage = if (result.isError) result.output else "",
                executionTimeMs = executionTimeMs,
                params = parameters
            )
        }

        scope.launch {
            mlEngine?.recordExecution(
                toolName = toolName,
                parameters = parameters.mapNotNull { (k, v) -> v?.let { k to it } }.toMap(),
                result = result,
                executionTimeMs = executionTimeMs,
                contextualData = mapOf(
                    "session_id" to sessionId,
                    "recent_tools" to recentBefore.joinToString(","),
                    "hour" to Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                )
            )
        }

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

        monitoringSystem?.let { monitor ->
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

        reflexionEngine?.recordExperienceAsync(
            toolName = toolName,
            parameters = parameters,
            result = result,
            executionTimeMs = executionTimeMs,
            userIntent = currentUserIntent
        )

        if (!result.isError) {
            progressiveTrustEngine?.onOperationSuccess(toolName)
        } else {
            progressiveTrustEngine?.onOperationFailure(toolName)
        }

        val dependencyPair = synchronized(sessionToolHistory) {
            val previous = sessionToolHistory.lastOrNull()
            sessionToolHistory.add(toolName)
            if (sessionToolHistory.size > 50) sessionToolHistory.removeAt(0)
            previous?.let { it to toolName }
        }

        if (!result.isError && dependencyPair != null) {
            scope.launch(Dispatchers.IO) {
                discoverAndRecordDependency(dependencyPair.first, dependencyPair.second)
            }
        }

        Log.d(TAG, "tool=$toolName success=${!result.isError} duration=${executionTimeMs}ms")
    }

    /**
     * Builds a bounded prompt enrichment ordered by expected decision value.
     * High-value task-specific memories are placed first so truncation cannot silently discard
     * them behind generic tool-awareness text.
     */
    suspend fun buildFullContextEnrichment(): String = withContext(Dispatchers.IO) {
        val parts = mutableListOf<String>()

        if (currentUserIntent.isNotBlank()) {
            try {
                episodicMemoryStore?.buildPromptInjection(
                    query = currentUserIntent,
                    topK = 2,
                    maxChars = EPISODIC_MAX_CHARS
                )?.takeIf { it.isNotBlank() }?.let(parts::add)
            } catch (t: Throwable) {
                Log.w(TAG, "episodic injection failed: ${t.message}")
            }
        }

        try {
            val lastTool = synchronized(sessionToolHistory) { sessionToolHistory.lastOrNull() }
            reflexionEngine?.buildPromptInjection(
                contextQuery = currentUserIntent.ifBlank { lastTool.orEmpty() },
                currentToolName = lastTool,
                maxChars = REFLEXION_MAX_CHARS
            )?.takeIf { it.isNotBlank() }?.let(parts::add)
        } catch (t: Throwable) {
            Log.w(TAG, "reflexion injection failed: ${t.message}")
        }

        getToolRecommendation()?.let { parts.add("\n🎯 Local next-tool signal: $it") }

        try {
            progressiveTrustEngine?.buildPromptInjection()
                ?.takeIf { it.isNotBlank() }
                ?.let(parts::add)
        } catch (t: Throwable) {
            Log.w(TAG, "trust injection failed: ${t.message}")
        }

        try {
            causalChainPlannerTool?.getLastPlanInjection(maxChars = 320)
                ?.takeIf { it.isNotBlank() }
                ?.let(parts::add)
        } catch (t: Throwable) {
            Log.w(TAG, "causal injection failed: ${t.message}")
        }

        awarenessEngine.buildSystemPromptContext()
            .takeIf { it.isNotBlank() }
            ?.let(parts::add)

        journal.buildMemoryContext()
            ?.takeIf { it.isNotBlank() }
            ?.let(parts::add)

        val historySnapshot = synchronized(sessionToolHistory) { sessionToolHistory.toList() }
        if (historySnapshot.size > 2) {
            parts.add("\nRecent tool path: ${historySnapshot.takeLast(MAX_TOOL_HISTORY_ITEMS).joinToString(" → ")}")
        }

        packPriorityContext(parts, MAX_CONTEXT_CHARS)
    }

    private fun packPriorityContext(parts: List<String>, maxChars: Int): String {
        if (parts.isEmpty() || maxChars <= 0) return ""
        val out = StringBuilder(minOf(maxChars, parts.sumOf { it.length }))
        for (part in parts) {
            if (out.length >= maxChars) break
            val remaining = maxChars - out.length
            val clean = part.trim()
            if (clean.isBlank()) continue
            if (out.isNotEmpty()) out.append('\n')
            out.append(clean.take(remaining.coerceAtLeast(0)))
        }
        return out.toString()
    }

    suspend fun buildEnrichedSystemPrompt(baseSystemPrompt: String): String = withContext(Dispatchers.IO) {
        val enrichment = buildFullContextEnrichment()
        if (enrichment.isBlank()) return@withContext baseSystemPrompt
        "$baseSystemPrompt\n\n$enrichment"
    }

    suspend fun getToolRecommendation(): String? = withContext(Dispatchers.Default) {
        val history = synchronized(sessionToolHistory) { sessionToolHistory.toList() }
        if (history.isEmpty()) return@withContext null

        val lastTool = history.last()
        val context = buildExecutionContext()
        val availableTools = availableToolNamesSnapshot.get()
        if (availableTools.isEmpty()) return@withContext null
        val recentToolsContext = history.takeLast(3).joinToString(",")

        val rlPrediction = intelligenceEngine?.predictBestTool(
            taskDescription = buildRecommendationTaskDescription(lastTool, recentToolsContext, context.timeOfDay),
            availableTools = availableTools,
            currentContext = context
        )

        val mlPrediction = mlEngine?.predictNextTool(
            currentTool = lastTool,
            recentTools = history.takeLast(3),
            contextualData = mapOf("hour" to context.timeOfDay)
        )

        if (rlPrediction != null &&
            rlPrediction.confidence.toDouble() > MIN_RL_CONSENSUS_CONFIDENCE &&
            mlPrediction != null && mlPrediction.confidence > MIN_ML_CONFIDENCE
        ) {
            val topMl = mlPrediction.suggestedTools.firstOrNull()?.first?.trim()
            val topRl = rlPrediction.recommendedTool.trim()
            if (topMl != null && topMl == topRl) {
                return@withContext "$topMl after $lastTool (RL+ML consensus)"
            }
        }

        val rlConfidence = rlPrediction?.confidence?.toDouble()
        if (rlConfidence != null && rlConfidence > MIN_RL_CONFIDENCE) {
            val alternatives = rlPrediction.alternatives
                .take(MAX_RECOMMENDATION_CANDIDATES)
                .joinToString(", ") { "${it.name} (${(it.score * 100).toInt()}%)" }
            return@withContext buildString {
                append("${rlPrediction.recommendedTool} after $lastTool (${(rlPrediction.confidence * 100).toInt()}%)")
                if (alternatives.isNotBlank()) append("; alternatives: $alternatives")
            }
        }

        if (mlPrediction != null && mlPrediction.confidence > MIN_ML_CONFIDENCE) {
            val suggested = mlPrediction.suggestedTools
                .take(MAX_RECOMMENDATION_CANDIDATES)
                .filter { it.second > MIN_ML_ALTERNATIVE_CONFIDENCE }
                .joinToString(", ") { "${it.first} (${(it.second * 100).toInt()}%)" }
            if (suggested.isNotBlank()) return@withContext "$suggested after $lastTool"
        }

        null
    }

    private fun buildRecommendationTaskDescription(
        lastTool: String,
        recentToolsContext: String,
        hour: Int
    ): String = "NextToolRecommendation(last=$lastTool,recent=[$recentToolsContext],hour=$hour)"

    suspend fun getContextForTool(toolName: String): String? = withContext(Dispatchers.IO) {
        val awarenessInfo = awarenessEngine.getToolKnowledge(toolName)
        val historyReport = journal.getToolHistory(toolName)

        buildString {
            awarenessInfo?.let { append(it) }
            if (historyReport.totalUses > 0) {
                appendLine("\n$toolName: ${historyReport.totalUses} uses | success ${(historyReport.successRate * 100).toInt()}%")
                if (historyReport.commonErrors.isNotEmpty()) {
                    appendLine("Common error: ${historyReport.commonErrors.first().take(80)}")
                }
                if (historyReport.commonNextTools.isNotEmpty()) {
                    appendLine("Common next tools: ${historyReport.commonNextTools.take(3).joinToString(", ")}")
                }
                historyReport.learningNotes.firstOrNull()?.let { appendLine(it) }
            }
        }.takeIf { it.isNotBlank() }
    }

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
            sessionToolCount = synchronized(sessionToolHistory) { sessionToolHistory.size },
            environmentStatus = buildEnvironmentStatus(awarenessStats)
        )
    }

    suspend fun performMaintenance() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Learning maintenance completed")
    }

    /** Per-tool quality is deliberately conservative: speed/output length are weak proxies. */
    private fun estimateQuality(result: ToolExecutionResult, timeMs: Long): Float {
        if (result.isError) return 0f
        if (result.output.isBlank()) return 0.35f
        val lower = result.output.take(500).lowercase()
        var quality = when {
            listOf("partial", "unverified", "warning", "not found", "unavailable").any(lower::contains) -> 0.52f
            result.output.length in 20..4000 -> 0.72f
            result.output.length > 4000 -> 0.64f
            else -> 0.58f
        }
        if (timeMs > 10_000L) quality -= 0.05f
        return quality.coerceIn(0.3f, 0.8f)
    }

    private fun buildExecutionContext(): ToolIntelligenceEngine.ExecutionContext {
        val cal = Calendar.getInstance()
        val previous = synchronized(sessionToolHistory) { sessionToolHistory.lastOrNull() }
        return ToolIntelligenceEngine.ExecutionContext(
            previousTool = previous,
            timeOfDay = cal.get(Calendar.HOUR_OF_DAY),
            dayOfWeek = cal.get(Calendar.DAY_OF_WEEK),
            batteryLevel = readBatteryLevel(),
            networkType = readNetworkType()
        )
    }

    private fun readBatteryLevel(): Int = try {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 } ?: -1
    } catch (_: Throwable) {
        -1
    }

    private fun readNetworkType(): String = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "unknown"
        val network = cm.activeNetwork ?: return "none"
        val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "other"
        }
    } catch (_: SecurityException) {
        "unknown"
    } catch (_: Throwable) {
        "unknown"
    }

    private suspend fun discoverAndRecordDependency(toolA: String, toolB: String) {
        val key = "$toolA→$toolB"
        val recentHistory = synchronized(sessionToolHistory) { sessionToolHistory.takeLast(30) }
        var occurrences = 0
        for (i in 0 until (recentHistory.size - 1).coerceAtLeast(0)) {
            if (recentHistory.getOrNull(i) == toolA && recentHistory.getOrNull(i + 1) == toolB) {
                occurrences++
            }
        }

        if (occurrences >= 3) {
            awarenessEngine.recordPattern(
                patternName = key,
                description = "$toolA was followed by $toolB $occurrences times",
                confidence = (occurrences / 10f).coerceIn(0.5f, 1.0f)
            )
        }
    }

    private fun buildEnvironmentStatus(stats: ToolAwarenessEngine.AwarenessStats): String = buildString {
        stats.environmentCache.forEach { (env, available) ->
            val icon = if (available == "true") "✅" else "❌"
            append("$icon $env  ")
        }
    }

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
