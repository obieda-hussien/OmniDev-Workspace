package com.omnidev.workspace.data.brain

import android.util.Log
import com.omnidev.workspace.data.db.dao.ToolExecutionDao
import com.omnidev.workspace.data.db.entities.ToolExecutionEntry
import com.omnidev.workspace.data.tools.ToolExecutionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Calendar

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * ToolExecutionJournal — Context note Context note Context note Context note
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Context note Context note "Context note Context note" Context note Agent Context note. Context note:
 * - Context note Context note Context note Context note Context note
 * - Context note Context note Context note Context note Context note
 * - Context note Context note Context note
 * - Context note Context note Context note Context note
 *
 * Context note Context note:
 * - Claude Code: Context note Context note Context note Context note
 * - GitHub Copilot Agent: Context note Context note Context note Context note
 * - Gemini Assistant: Context note Context note Context note Context note Context note
 *
 * Features:
 * 1. Context note Context note Context note Context note Context note (SQLite)
 * 2. Context note Context note Context note Context note Context note
 * 3. Context note Context note Context note Context note
 * 4. Context note context enrichment Context note System Prompt
 * 5. Context note Context note Context note Context note Context note
 * 6. Context note Context note Context note
 */
class ToolExecutionJournal(
    private val dao: ToolExecutionDao,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    companion object {
        private const val TAG = "ToolJournal"
        private const val MAX_RESULT_SUMMARY_LENGTH = 500
        private const val MAX_ENTRIES_TO_KEEP = 10_000
        private const val CLEANUP_THRESHOLD = 12_000
    }

    // ─── Context note Context note ──────────────────────────────────────────────

    private var currentSessionId: String = generateSessionId()
    private var currentAgentMode: String = "ASSISTANT"
    private var previousToolName: String = ""
    private var sessionToolCount: Int = 0

    fun startNewSession(agentMode: String = "ASSISTANT") {
        currentSessionId = generateSessionId()
        currentAgentMode = agentMode
        previousToolName = ""
        sessionToolCount = 0
        Log.d(TAG, "📔 Info Info: $currentSessionId | Info: $agentMode")
    }

    fun updateAgentMode(mode: String) {
        currentAgentMode = mode
    }

    // ─── Context note Context note ───────────────────────────────────────────────

    /**
     * Context note Context note Context note Context note Context note Context note Context note
     */
    suspend fun recordToolExecution(
        toolName: String,
        parameters: Map<String, Any?>,
        result: ToolExecutionResult,
        executionTimeMs: Long,
        agentContext: String = ""
    ): Long = withContext(Dispatchers.IO) {
        val calendar = Calendar.getInstance()
        val hourOfDay = calendar.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)

        // Context note Context note Context note
        val resultSummary = buildResultSummary(result)

        // Context note Context note Context note
        val learningNote = generateLearningNote(
            toolName = toolName,
            success = !result.isError,
            executionTimeMs = executionTimeMs,
            errorMessage = if (result.isError) result.output else ""
        )

        // Context note Context note Context note
        val quality = estimateResultQuality(result, executionTimeMs)

        val entry = ToolExecutionEntry(
            toolName = toolName,
            parametersJson = parametersToJson(parameters),
            resultSummary = resultSummary,
            success = !result.isError,
            executionTimeMs = executionTimeMs,
            resultSize = result.output.length,
            agentContext = agentContext.take(300),
            previousToolName = previousToolName,
            sessionId = currentSessionId,
            agentMode = currentAgentMode,
            errorMessage = if (result.isError) result.output.take(300) else "",
            resultQuality = quality,
            hourOfDay = hourOfDay,
            dayOfWeek = dayOfWeek,
            learningNote = learningNote,
            flaggedForReview = shouldFlag(toolName, result, executionTimeMs)
        )

        val id = dao.insert(entry)

        // Context note Context note Context note
        previousToolName = toolName
        sessionToolCount++

        // Context note Context note
        if (sessionToolCount % 100 == 0) {
            scope.launch { cleanupOldEntries() }
        }

        Log.d(TAG, "📝 Info: $toolName | Info: ${!result.isError} | Info: ${executionTimeMs}ms")
        id
    }

    // ─── Context note Context note ─────────────────────────────────────────────

    /**
     * Context note Context note Context note Context note Context note System Prompt
     * Context note Context note Agent Context note Context note Context note Context note Context note
     */
    suspend fun buildMemoryContext(maxItems: Int = 8): String? = withContext(Dispatchers.IO) {
        val stats = dao.getToolStats()
        if (stats.isEmpty()) return@withContext null

        val recentFailures = dao.getFailures(limit = 5)
        val totalCount = dao.getTotalCount()
        val successCount = dao.getSuccessCount()

        buildString {
            appendLine("\n═══ 🧠 AGENT EXECUTION MEMORY ═══")
            appendLine("📊 Context note Context note: $totalCount | Context note: $successCount (${if (totalCount > 0) (successCount * 100 / totalCount) else 0}%)")

            // Context note Context note Context note
            val topTools = stats.take(5)
            if (topTools.isNotEmpty()) {
                appendLine("\n🔧 Info Info Info:")
                topTools.forEach { s ->
                    val rate = if (s.total > 0) (s.successes * 100 / s.total) else 0
                    appendLine("  • ${s.toolName}: ${s.total} Info | Info: $rate% | Info: ${s.avgTime.toLong()}ms")
                }
            }

            // Context note Context note
            if (recentFailures.isNotEmpty()) {
                appendLine("\n⚠️ Info Info (Info Info Info):")
                recentFailures.take(3).forEach { f ->
                    appendLine("  ✗ ${f.toolName}: ${f.errorMessage.take(100)}")
                }
            }

            // Context note Context note
            val patterns = discoverSessionPatterns()
            if (patterns.isNotEmpty()) {
                appendLine("\n🔗 Info Info:")
                patterns.take(3).forEach { p -> appendLine("  → $p") }
            }

            appendLine("═══════════════════════════════════")
        }
    }

    /**
     * Context note Context note Context note Context note
     */
    suspend fun getSessionContext(sessionId: String = currentSessionId): String = withContext(Dispatchers.IO) {
        val entries = dao.getBySession(sessionId)
        if (entries.isEmpty()) return@withContext ""

        buildString {
            appendLine("📔 Info Info Info (${entries.size} Info):")
            entries.takeLast(10).forEach { e ->
                val status = if (e.success) "✅" else "❌"
                appendLine("  $status ${e.toolName} (${e.executionTimeMs}ms)")
                if (!e.success && e.errorMessage.isNotBlank()) {
                    appendLine("     Info: ${e.errorMessage.take(80)}")
                }
            }
        }
    }

    /**
     * Context note Context note Context note Context note Context note Context note
     */
    suspend fun getToolHistory(toolName: String): ToolHistoryReport = withContext(Dispatchers.IO) {
        val entries = dao.getByTool(toolName, limit = 20)
        val failures = dao.getRecentFailures(toolName)
        val nextTools = dao.getToolsUsedAfter(toolName, limit = 5)

        val successRate = if (entries.isNotEmpty()) {
            entries.count { it.success }.toFloat() / entries.size
        } else 0.5f

        val avgTime = if (entries.isNotEmpty()) {
            entries.map { it.executionTimeMs }.average().toLong()
        } else 0L

        val commonErrors = failures.mapNotNull { it.errorMessage.takeIf { e -> e.isNotBlank() } }
            .groupingBy { it }.eachCount()
            .toList().sortedByDescending { it.second }
            .take(3).map { it.first }

        val commonNextTools = nextTools.map { it.toolName }

        ToolHistoryReport(
            toolName = toolName,
            totalUses = entries.size,
            successRate = successRate,
            avgExecutionTimeMs = avgTime,
            commonErrors = commonErrors,
            commonNextTools = commonNextTools,
            lastUsed = entries.firstOrNull()?.timestamp ?: 0L,
            learningNotes = entries.mapNotNull { it.learningNote.takeIf { n -> n.isNotBlank() } }.take(3)
        )
    }

    // ─── Context note Context note ───────────────────────────────────────────

    /**
     * Context note Context note Context note Context note Context note
     */
    private suspend fun discoverSessionPatterns(): List<String> = withContext(Dispatchers.IO) {
        val recent = dao.getRecent(50)
        val patterns = mutableListOf<String>()

        // Context note 1: Context note Context note
        if (recent.size >= 4) {
            val sequences = mutableMapOf<String, Int>()
            val safeSize = recent.size
            for (i in 0 until safeSize - 1) {
                val seq = "${recent.getOrNull(i)?.toolName} → ${recent.getOrNull(i + 1)?.toolName}"
                sequences[seq] = (sequences[seq] ?: 0) + 1
            }
            sequences.filter { it.value >= 2 }.forEach { (seq, count) ->
                patterns.add("Info Info ($count Info): $seq")
            }
        }

        // Context note 2: Context note Context note Context note Context note Context note Context note
        val sessionEntries = dao.getBySession(currentSessionId)
        val toolFailRates = sessionEntries.groupBy { it.toolName }.mapValues { (_, entries) ->
            val failCount = entries.count { !it.success }
            failCount.toFloat() / entries.size
        }
        toolFailRates.filter { it.value > 0.5f && toolFailRates[it.key]?.let { r -> r > 0 } == true }
            .forEach { (tool, rate) ->
                patterns.add("⚠️ ${tool} Context note Context note: ${(rate * 100).toInt()}% Context note Context note Context note")
            }

        patterns
    }

    /**
     * Context note Context note Context note Context note Context note Context note
     */
    suspend fun analyzeAllTools(): ToolPerformanceSummary = withContext(Dispatchers.IO) {
        val stats = dao.getToolStats()
        val totalOps = dao.getTotalCount()
        val successOps = dao.getSuccessCount()

        val best = stats.filter { it.total >= 5 }
            .maxByOrNull { if (it.total > 0) it.successes.toDouble() / it.total else 0.0 }

        val worst = stats.filter { it.total >= 5 }
            .minByOrNull { if (it.total > 0) it.successes.toDouble() / it.total else 1.0 }

        val slowest = stats.filter { it.total >= 3 }.maxByOrNull { it.avgTime }
        val mostUsed = stats.firstOrNull()
        val problematic = dao.getProblematicTools()

        ToolPerformanceSummary(
            totalOperations = totalOps,
            overallSuccessRate = if (totalOps > 0) successOps.toFloat() / totalOps else 0f,
            bestPerformingTool = best?.toolName,
            worstPerformingTool = worst?.toolName,
            slowestTool = slowest?.toolName,
            mostUsedTool = mostUsed?.toolName,
            problematicTools = problematic,
            toolStats = stats
        )
    }

    // ─── Context note Context note ─────────────────────────────────────────────

    private fun buildResultSummary(result: ToolExecutionResult): String {
        return if (result.isError) {
            "ERROR: ${result.output.take(MAX_RESULT_SUMMARY_LENGTH)}"
        } else {
            result.output.take(MAX_RESULT_SUMMARY_LENGTH)
        }
    }

    private fun generateLearningNote(
        toolName: String,
        success: Boolean,
        executionTimeMs: Long,
        errorMessage: String
    ): String {
        return when {
            !success && errorMessage.contains("permission", ignoreCase = true) ->
                "⚠️ Info Info Info Info $toolName"
            !success && errorMessage.contains("timeout", ignoreCase = true) ->
                "⏱️ $toolName Info Info Info - Info Info Info"
            !success && errorMessage.contains("not found", ignoreCase = true) ->
                "🔍 $toolName: Info Info Info - Info Info Info"
            !success && errorMessage.contains("network", ignoreCase = true) ->
                "🌐 $toolName: Info Info - Info Info"
            !success ->
                "❌ Info Info $toolName - ${errorMessage.take(100)}"
            executionTimeMs > 10_000 ->
                "⚡ $toolName Info (${executionTimeMs}ms) - Info Info Info Info"
            success && executionTimeMs < 500 ->
                "✅ $toolName Info Info (${executionTimeMs}ms)"
            else -> ""
        }
    }

    private fun estimateResultQuality(result: ToolExecutionResult, executionTimeMs: Long): Float {
        if (result.isError) return 0.0f
        val hasContent = result.output.length > 10
        val isReasonablyFast = executionTimeMs < 5000
        val hasStructure = result.output.contains("\n") || result.output.length > 50

        return when {
            !hasContent -> 0.2f
            hasContent && isReasonablyFast && hasStructure -> 0.9f
            hasContent && isReasonablyFast -> 0.7f
            hasContent -> 0.5f
            else -> 0.3f
        }
    }

    private fun shouldFlag(toolName: String, result: ToolExecutionResult, executionTimeMs: Long): Boolean {
        return result.isError && (
            result.output.contains("crash", ignoreCase = true) ||
            result.output.contains("exception", ignoreCase = true) ||
            executionTimeMs > 30_000
        )
    }

    private fun parametersToJson(params: Map<String, Any?>): String {
        return try {
            val obj = JSONObject()
            params.entries.take(10).forEach { (k, v) ->
                obj.put(k, v?.toString()?.take(200) ?: "null")
            }
            obj.toString()
        } catch (e: Exception) {
            "{}"
        }
    }

    private suspend fun cleanupOldEntries() = withContext(Dispatchers.IO) {
        val total = dao.getTotalCount()
        if (total > CLEANUP_THRESHOLD) {
            dao.keepOnlyLatest(MAX_ENTRIES_TO_KEEP)
            Log.d(TAG, "🧹 Info: Info ${total - MAX_ENTRIES_TO_KEEP} Info Info")
        }
    }

    private fun generateSessionId(): String {
        return "S${System.currentTimeMillis()}"
    }

    // ─── Flow Context note ─────────────────────────────────────────────────

    fun observeRecentExecutions(): Flow<List<ToolExecutionEntry>> = dao.observeRecent()

    suspend fun deleteExecutionById(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteById(id)
    }

    // ─── Data Classes ─────────────────────────────────────────────────

    data class ToolHistoryReport(
        val toolName: String,
        val totalUses: Int,
        val successRate: Float,
        val avgExecutionTimeMs: Long,
        val commonErrors: List<String>,
        val commonNextTools: List<String>,
        val lastUsed: Long,
        val learningNotes: List<String>
    )

    data class ToolPerformanceSummary(
        val totalOperations: Int,
        val overallSuccessRate: Float,
        val bestPerformingTool: String?,
        val worstPerformingTool: String?,
        val slowestTool: String?,
        val mostUsedTool: String?,
        val problematicTools: List<String>,
        val toolStats: List<com.omnidev.workspace.data.db.dao.ToolUsageStats>
    )
}
