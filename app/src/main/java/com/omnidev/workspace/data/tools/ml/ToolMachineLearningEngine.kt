package com.omnidev.workspace.data.tools.ml

import android.content.Context
import android.util.Log
import com.omnidev.workspace.data.tools.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Local next-tool sequence learner optimized for Android.
 *
 * This intentionally replaces the old pseudo-ensemble (Naive Bayes + KNN + decision tree + a
 * five-output hash-collision neural net) with a model that matches the actual problem: predicting
 * the next discrete tool from a short tool sequence.
 *
 * Algorithm:
 * - variable-order Markov model (1/2/3-gram)
 * - Bayesian/Laplace smoothing for sparse transitions
 * - context backoff (coarse time bucket -> global sequence)
 * - tool reliability posterior to suppress historically failing tools
 * - online confidence calibration from evidence and top-vs-second margin
 * - Welford running statistics for latency/result-size anomaly detection
 *
 * No model download, API, tensor runtime, hourly retraining loop or unbounded KNN dataset exists.
 */
class ToolMachineLearningEngine(private val context: Context) {

    companion object {
        private const val TAG = "ToolSequenceML"
        private const val PREFS_NAME = "tool_sequence_ml_v3"
        private const val PREFS_KEY = "state"
        private const val MODEL_VERSION = 3
        private const val MAX_HISTORY_SIZE = 1_000
        private const val MAX_TRANSITION_KEYS = 1_024
        private const val MAX_TOOLS_PER_KEY = 48
        private const val SAVE_EVERY_UPDATES = 25
        private const val MIN_ANOMALY_SAMPLES = 8
        private const val SEPARATOR = "\u001F"
    }

    private data class TransitionStats(
        var count: Int = 0,
        var successfulCount: Int = 0,
        var rewardMean: Double = 0.5,
        var lastSeen: Long = 0L
    )

    data class ToolStats(
        var executionCount: Int = 0,
        var successCount: Int = 0,
        var successRate: Double = 0.0,
        var avgExecutionTime: Double = 0.0,
        var stdDevExecutionTime: Double = 0.0,
        var avgResultSize: Double = 0.0,
        var stdDevResultSize: Double = 0.0,
        internal var executionTimeM2: Double = 0.0,
        internal var resultSizeM2: Double = 0.0,
        internal var lastUsed: Long = 0L
    )

    data class ToolExecutionRecord(
        val timestamp: Long,
        val toolName: String,
        val parameters: Map<String, Any>,
        val success: Boolean,
        val executionTimeMs: Long,
        val resultSize: Int,
        val contextualData: Map<String, Any>
    )

    data class ToolPrediction(
        val suggestedTools: List<Pair<String, Double>>,
        val confidence: Double,
        val basedOnPattern: String
    )

    data class AnomalyDetectionResult(
        val isAnomaly: Boolean,
        val score: Double,
        val description: String
    )

    data class ToolRecommendation(
        val toolName: String,
        val reason: String,
        val confidence: Double,
        val priority: Int
    )

    data class PerformanceMetric(
        val toolName: String,
        val successRate: Double,
        val avgExecutionTime: Double,
        val executionCount: Int
    )

    data class PerformanceAnalysis(
        val totalExecutions: Int,
        val overallSuccessRate: Double,
        val topPerformers: List<PerformanceMetric>,
        val bottlenecks: List<PerformanceMetric>,
        val unreliableTools: List<PerformanceMetric>,
        val predictionAccuracy: Double,
        val trainingEpochs: Int
    )

    private val lock = Any()
    private val executionHistory = ArrayDeque<ToolExecutionRecord>()
    private val transitions = mutableMapOf<String, MutableMap<String, TransitionStats>>()
    private val toolStats = mutableMapOf<String, ToolStats>()
    private var totalPredictions = 0
    private var correctPredictions = 0
    private var updateCount = 0
    private var updatesSinceSave = 0

    init {
        loadState()
    }

    suspend fun recordExecution(
        toolName: String,
        parameters: Map<String, Any>,
        result: ToolExecutionResult,
        executionTimeMs: Long,
        contextualData: Map<String, Any> = emptyMap()
    ) = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val success = !result.isError
        val resultSize = result.output.length
        val reward = executionReward(result, executionTimeMs)

        synchronized(lock) {
            val recent = recentSequenceFromContext(contextualData)
                .ifEmpty { executionHistory.takeLastCompat(3).map { it.toolName } }

            evaluateOnlinePrediction(recent, contextualData, toolName)
            updateTransitions(recent, contextualData, toolName, success, reward, now)
            updateToolStats(toolName, success, executionTimeMs, resultSize, now)

            executionHistory.addLast(
                ToolExecutionRecord(
                    timestamp = now,
                    toolName = toolName,
                    parameters = parameters.take(8),
                    success = success,
                    executionTimeMs = executionTimeMs.coerceAtLeast(0L),
                    resultSize = resultSize,
                    contextualData = contextualData.filterKeys {
                        it in setOf("hour", "task_type", "recent_tools")
                    }
                )
            )
            while (executionHistory.size > MAX_HISTORY_SIZE) executionHistory.removeFirst()

            updateCount++
            updatesSinceSave++
            pruneModel(now)
        }

        if (updatesSinceSave >= SAVE_EVERY_UPDATES) {
            saveState()
        }
    }

    suspend fun predictNextTool(
        currentTool: String? = null,
        recentTools: List<String> = emptyList(),
        contextualData: Map<String, Any> = emptyMap()
    ): ToolPrediction = withContext(Dispatchers.Default) {
        synchronized(lock) {
            val sequence = buildList {
                addAll(recentTools.filter(String::isNotBlank))
                if (!currentTool.isNullOrBlank() && lastOrNull() != currentTool) add(currentTool)
            }.takeLast(3)

            val prediction = predictInternal(sequence, contextualData)
            totalPredictions++
            ToolPrediction(
                suggestedTools = prediction.first.take(5),
                confidence = prediction.second,
                basedOnPattern = sequence.joinToString(" → ")
            )
        }
    }

    /**
     * Robust-ish anomaly detector using online variance and Bayesian failure surprise.
     */
    suspend fun detectAnomalies(
        toolName: String,
        executionTimeMs: Long,
        result: ToolExecutionResult
    ): AnomalyDetectionResult = withContext(Dispatchers.Default) {
        synchronized(lock) {
            val stats = toolStats[toolName]
                ?: return@synchronized AnomalyDetectionResult(false, 0.0, "")
            if (stats.executionCount < MIN_ANOMALY_SAMPLES) {
                return@synchronized AnomalyDetectionResult(false, 0.0, "")
            }

            val reasons = mutableListOf<String>()
            var score = 0.0

            val timeStd = stats.stdDevExecutionTime.coerceAtLeast(1.0)
            val timeZ = abs(executionTimeMs - stats.avgExecutionTime) / timeStd
            if (timeZ >= 3.5) {
                score += 0.30
                reasons += "latency z=${format(timeZ)}"
            }

            val sizeStd = stats.stdDevResultSize.coerceAtLeast(1.0)
            val sizeZ = abs(result.output.length - stats.avgResultSize) / sizeStd
            if (sizeZ >= 3.5) {
                score += 0.18
                reasons += "result-size z=${format(sizeZ)}"
            }

            val reliability = bayesianReliability(stats)
            if (result.isError && reliability >= 0.82) {
                score += 0.30
                reasons += "unexpected failure for reliable tool"
            }
            if (result.persistentFailure) {
                score += 0.34
                reasons += "persistent failure"
            }
            if (result.isError && !result.retryable && result.classification != null) {
                score += 0.12
                reasons += "non-retryable ${result.classification}"
            }

            AnomalyDetectionResult(
                isAnomaly = score >= 0.30,
                score = score.coerceIn(0.0, 1.0),
                description = reasons.joinToString("; ")
            )
        }
    }

    suspend fun getRecommendations(
        currentContext: Map<String, Any>
    ): List<ToolRecommendation> = withContext(Dispatchers.Default) {
        synchronized(lock) {
            val recent = executionHistory.takeLastCompat(3).map { it.toolName }
            val prediction = predictInternal(recent, currentContext).first
            val sequenceRecommendations = prediction.take(5).mapIndexed { index, (tool, score) ->
                ToolRecommendation(
                    toolName = tool,
                    reason = "learned sequence after ${recent.joinToString(" → ").ifBlank { "start" }}",
                    confidence = score,
                    priority = index
                )
            }

            val reliable = toolStats.entries
                .filter { it.value.executionCount >= 6 }
                .sortedByDescending { bayesianReliability(it.value) }
                .take(5)
                .map { (tool, stats) ->
                    ToolRecommendation(
                        toolName = tool,
                        reason = "reliable tool (${(bayesianReliability(stats) * 100).toInt()}% posterior)",
                        confidence = bayesianReliability(stats),
                        priority = 10
                    )
                }

            (sequenceRecommendations + reliable)
                .distinctBy { it.toolName }
                .sortedWith(compareBy<ToolRecommendation> { it.priority }.thenByDescending { it.confidence })
                .take(10)
        }
    }

    suspend fun analyzeToolPerformance(): PerformanceAnalysis = withContext(Dispatchers.Default) {
        synchronized(lock) {
            val total = toolStats.values.sumOf { it.executionCount }
            val successes = toolStats.values.sumOf { it.successCount }

            fun ToolStats.metric(name: String) = PerformanceMetric(
                toolName = name,
                successRate = successRate,
                avgExecutionTime = avgExecutionTime,
                executionCount = executionCount
            )

            val eligible = toolStats.filterValues { it.executionCount >= 5 }
            val top = eligible.entries
                .sortedByDescending { bayesianReliability(it.value) }
                .take(10)
                .map { it.value.metric(it.key) }
            val bottlenecks = eligible.entries
                .sortedByDescending { it.value.avgExecutionTime }
                .take(5)
                .map { it.value.metric(it.key) }
            val unreliable = eligible.entries
                .filter { bayesianReliability(it.value) < 0.55 }
                .sortedBy { bayesianReliability(it.value) }
                .take(5)
                .map { it.value.metric(it.key) }

            PerformanceAnalysis(
                totalExecutions = total,
                overallSuccessRate = if (total == 0) 0.0 else successes.toDouble() / total.toDouble(),
                topPerformers = top,
                bottlenecks = bottlenecks,
                unreliableTools = unreliable,
                predictionAccuracy = if (totalPredictions == 0) 0.0
                else correctPredictions.toDouble() / totalPredictions.toDouble(),
                trainingEpochs = updateCount
            )
        }
    }

    /** No background trainer exists anymore; retained for source compatibility. */
    fun shutdown() {
        saveState()
    }

    private fun predictInternal(
        recentTools: List<String>,
        contextualData: Map<String, Any>
    ): Pair<List<Pair<String, Double>>, Double> {
        val recent = recentTools.filter(String::isNotBlank).takeLast(3)
        val hourBand = hourBand(contextualData)
        val aggregate = mutableMapOf<String, Double>()
        val evidence = mutableMapOf<String, Int>()
        var usedWeight = 0.0

        val orderWeights = mapOf(3 to 0.42, 2 to 0.29, 1 to 0.19)
        for (order in 3 downTo 1) {
            if (recent.size < order) continue
            val sequence = recent.takeLast(order)
            val weight = orderWeights.getValue(order)

            // Specific context gets more weight; global sequence provides robust backoff.
            val contextual = transitions[transitionKey(hourBand, sequence)]
            val global = transitions[transitionKey("*", sequence)]

            if (!contextual.isNullOrEmpty()) {
                addTransitionEvidence(aggregate, evidence, contextual, weight * 0.62)
                usedWeight += weight * 0.62
            }
            if (!global.isNullOrEmpty()) {
                addTransitionEvidence(aggregate, evidence, global, weight * 0.38)
                usedWeight += weight * 0.38
            }
        }

        // No sequence evidence: use calibrated tool reliability as a weak prior only.
        if (aggregate.isEmpty()) {
            toolStats.forEach { (tool, stats) ->
                if (stats.executionCount > 0) {
                    aggregate[tool] = bayesianReliability(stats) * 0.55
                    evidence[tool] = stats.executionCount
                }
            }
            usedWeight = 0.55
        } else {
            // Reliability prior prevents a frequent transition into a consistently broken tool.
            toolStats.forEach { (tool, stats) ->
                if (tool in aggregate) {
                    aggregate[tool] = (aggregate[tool] ?: 0.0) + bayesianReliability(stats) * 0.10
                    evidence[tool] = (evidence[tool] ?: 0) + stats.executionCount.coerceAtMost(20)
                }
            }
            usedWeight += 0.10
        }

        if (aggregate.isEmpty()) return emptyList<Pair<String, Double>>() to 0.0

        val denominator = usedWeight.coerceAtLeast(0.01)
        val normalized = aggregate.mapValues { (_, value) ->
            (value / denominator).coerceIn(0.0, 1.0)
        }.toList().sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })

        val top = normalized.first()
        val second = normalized.getOrNull(1)?.second ?: 0.0
        val margin = (top.second - second).coerceIn(0.0, 1.0)
        val topEvidence = evidence[top.first] ?: 0
        val evidenceStrength = topEvidence.toDouble() / (topEvidence + 8.0)
        val confidence = (
            top.second * 0.55 +
                evidenceStrength * 0.30 +
                margin * 0.15
            ).coerceIn(0.0, 0.98)

        return normalized to confidence
    }

    private fun addTransitionEvidence(
        aggregate: MutableMap<String, Double>,
        evidence: MutableMap<String, Int>,
        options: Map<String, TransitionStats>,
        weight: Double
    ) {
        if (options.isEmpty() || weight <= 0.0) return
        val total = options.values.sumOf { it.count }.coerceAtLeast(1)
        val vocabulary = options.size.coerceAtLeast(1)

        options.forEach { (tool, stats) ->
            val probability = (stats.count + 0.5) / (total + 0.5 * vocabulary)
            val successPosterior = (stats.successfulCount + 1.5) /
                (stats.count + 3.0)
            val reward = stats.rewardMean.coerceIn(0.0, 1.0)
            val calibrated = probability * (0.60 + 0.25 * successPosterior + 0.15 * reward)
            aggregate[tool] = (aggregate[tool] ?: 0.0) + calibrated * weight
            evidence[tool] = (evidence[tool] ?: 0) + stats.count
        }
    }

    private fun updateTransitions(
        recent: List<String>,
        contextualData: Map<String, Any>,
        nextTool: String,
        success: Boolean,
        reward: Double,
        now: Long
    ) {
        val sequence = recent.filter(String::isNotBlank).takeLast(3)
        val hourBand = hourBand(contextualData)

        for (order in 1..3) {
            if (sequence.size < order) continue
            val suffix = sequence.takeLast(order)
            updateTransitionKey(transitionKey(hourBand, suffix), nextTool, success, reward, now)
            updateTransitionKey(transitionKey("*", suffix), nextTool, success, reward, now)
        }
    }

    private fun updateTransitionKey(
        key: String,
        nextTool: String,
        success: Boolean,
        reward: Double,
        now: Long
    ) {
        val options = transitions.getOrPut(key) { mutableMapOf() }
        val stats = options.getOrPut(nextTool) { TransitionStats() }
        val oldCount = stats.count
        stats.count++
        if (success) stats.successfulCount++
        stats.rewardMean = if (oldCount == 0) reward else
            stats.rewardMean + (reward - stats.rewardMean) / stats.count.toDouble()
        stats.lastSeen = now

        if (options.size > MAX_TOOLS_PER_KEY) {
            options.entries
                .sortedWith(compareBy<Map.Entry<String, TransitionStats>> { it.value.count }
                    .thenBy { it.value.lastSeen })
                .take(options.size - MAX_TOOLS_PER_KEY)
                .forEach { options.remove(it.key) }
        }
    }

    private fun evaluateOnlinePrediction(
        recent: List<String>,
        contextualData: Map<String, Any>,
        actualTool: String
    ) {
        val predicted = predictInternal(recent, contextualData).first.firstOrNull()?.first ?: return
        totalPredictions++
        if (predicted == actualTool) correctPredictions++
    }

    private fun updateToolStats(
        toolName: String,
        success: Boolean,
        executionTimeMs: Long,
        resultSize: Int,
        now: Long
    ) {
        val stats = toolStats.getOrPut(toolName) { ToolStats() }
        stats.executionCount++
        if (success) stats.successCount++
        stats.successRate = stats.successCount.toDouble() / stats.executionCount.toDouble()
        stats.lastUsed = now

        val n = stats.executionCount.toDouble()
        val time = executionTimeMs.coerceAtLeast(0L).toDouble()
        val timeDelta = time - stats.avgExecutionTime
        stats.avgExecutionTime += timeDelta / n
        val timeDelta2 = time - stats.avgExecutionTime
        stats.executionTimeM2 += timeDelta * timeDelta2
        stats.stdDevExecutionTime = if (stats.executionCount > 1) {
            sqrt((stats.executionTimeM2 / (stats.executionCount - 1)).coerceAtLeast(0.0))
        } else 0.0

        val size = resultSize.coerceAtLeast(0).toDouble()
        val sizeDelta = size - stats.avgResultSize
        stats.avgResultSize += sizeDelta / n
        val sizeDelta2 = size - stats.avgResultSize
        stats.resultSizeM2 += sizeDelta * sizeDelta2
        stats.stdDevResultSize = if (stats.executionCount > 1) {
            sqrt((stats.resultSizeM2 / (stats.executionCount - 1)).coerceAtLeast(0.0))
        } else 0.0
    }

    private fun executionReward(result: ToolExecutionResult, executionTimeMs: Long): Double {
        if (result.isError) return 0.0
        var reward = 0.58
        if (!result.verification.isNullOrBlank()) reward += 0.16
        if (result.exitCode == 0) reward += 0.08
        if (result.output.isNotBlank()) reward += 0.06
        if (result.output.length in 1..4_000) reward += 0.04
        if (executionTimeMs in 1..2_000) reward += 0.05
        if (executionTimeMs > 10_000) reward -= 0.08
        return reward.coerceIn(0.0, 1.0)
    }

    private fun bayesianReliability(stats: ToolStats): Double {
        val failures = (stats.executionCount - stats.successCount).coerceAtLeast(0)
        return (stats.successCount + 2.0) / (stats.successCount + failures + 4.0)
    }

    private fun recentSequenceFromContext(contextualData: Map<String, Any>): List<String> =
        contextualData["recent_tools"]
            ?.toString()
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.takeLast(3)
            .orEmpty()

    private fun hourBand(contextualData: Map<String, Any>): String {
        val hour = (contextualData["hour"] as? Number)?.toInt()
            ?: java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour.coerceIn(0, 23)) {
            in 0..5 -> "night"
            in 6..11 -> "morning"
            in 12..17 -> "afternoon"
            else -> "evening"
        }
    }

    private fun transitionKey(contextBand: String, sequence: List<String>): String =
        "$contextBand|${sequence.joinToString(SEPARATOR)}"

    private fun pruneModel(now: Long) {
        if (transitions.size <= MAX_TRANSITION_KEYS) return
        val staleBefore = now - 60L * 24 * 3_600_000L
        transitions.entries
            .filter { (_, options) -> options.values.maxOfOrNull { it.lastSeen } ?: 0L < staleBefore }
            .sortedBy { (_, options) -> options.values.sumOf { it.count } }
            .take(transitions.size - MAX_TRANSITION_KEYS)
            .forEach { transitions.remove(it.key) }

        if (transitions.size > MAX_TRANSITION_KEYS) {
            transitions.entries
                .sortedBy { (_, options) -> options.values.sumOf { it.count } }
                .take(transitions.size - MAX_TRANSITION_KEYS)
                .forEach { transitions.remove(it.key) }
        }
    }

    private fun saveState() {
        try {
            val root = synchronized(lock) {
                JSONObject().apply {
                    put("version", MODEL_VERSION)
                    put("updateCount", updateCount)
                    put("totalPredictions", totalPredictions)
                    put("correctPredictions", correctPredictions)

                    put("tools", JSONArray().apply {
                        toolStats.forEach { (name, stats) ->
                            put(JSONObject().apply {
                                put("name", name)
                                put("executionCount", stats.executionCount)
                                put("successCount", stats.successCount)
                                put("avgExecutionTime", stats.avgExecutionTime)
                                put("executionTimeM2", stats.executionTimeM2)
                                put("avgResultSize", stats.avgResultSize)
                                put("resultSizeM2", stats.resultSizeM2)
                                put("lastUsed", stats.lastUsed)
                            })
                        }
                    })

                    put("transitions", JSONArray().apply {
                        transitions.forEach { (key, options) ->
                            options.forEach { (tool, stats) ->
                                put(JSONObject().apply {
                                    put("key", key)
                                    put("tool", tool)
                                    put("count", stats.count)
                                    put("successfulCount", stats.successfulCount)
                                    put("rewardMean", stats.rewardMean)
                                    put("lastSeen", stats.lastSeen)
                                })
                            }
                        }
                    })
                }
            }

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREFS_KEY, root.toString())
                .apply()
            updatesSinceSave = 0
        } catch (e: Exception) {
            Log.w(TAG, "saveState failed: ${e.message}")
        }
    }

    private fun loadState() {
        try {
            val payload = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREFS_KEY, null)
                ?: return
            val root = JSONObject(payload)
            if (root.optInt("version", 0) != MODEL_VERSION) return

            synchronized(lock) {
                updateCount = root.optInt("updateCount", 0).coerceAtLeast(0)
                totalPredictions = root.optInt("totalPredictions", 0).coerceAtLeast(0)
                correctPredictions = root.optInt("correctPredictions", 0)
                    .coerceIn(0, totalPredictions)

                toolStats.clear()
                val tools = root.optJSONArray("tools") ?: JSONArray()
                for (i in 0 until tools.length()) {
                    val item = tools.optJSONObject(i) ?: continue
                    val name = item.optString("name").takeIf(String::isNotBlank) ?: continue
                    val executions = item.optInt("executionCount", 0).coerceAtLeast(0)
                    val successes = item.optInt("successCount", 0).coerceIn(0, executions)
                    val timeM2 = item.optDouble("executionTimeM2", 0.0).coerceAtLeast(0.0)
                    val sizeM2 = item.optDouble("resultSizeM2", 0.0).coerceAtLeast(0.0)
                    toolStats[name] = ToolStats(
                        executionCount = executions,
                        successCount = successes,
                        successRate = if (executions == 0) 0.0 else successes.toDouble() / executions,
                        avgExecutionTime = item.optDouble("avgExecutionTime", 0.0).coerceAtLeast(0.0),
                        stdDevExecutionTime = if (executions > 1) sqrt(timeM2 / (executions - 1)) else 0.0,
                        avgResultSize = item.optDouble("avgResultSize", 0.0).coerceAtLeast(0.0),
                        stdDevResultSize = if (executions > 1) sqrt(sizeM2 / (executions - 1)) else 0.0,
                        executionTimeM2 = timeM2,
                        resultSizeM2 = sizeM2,
                        lastUsed = item.optLong("lastUsed", 0L)
                    )
                }

                transitions.clear()
                val encodedTransitions = root.optJSONArray("transitions") ?: JSONArray()
                for (i in 0 until encodedTransitions.length()) {
                    val item = encodedTransitions.optJSONObject(i) ?: continue
                    val key = item.optString("key").takeIf(String::isNotBlank) ?: continue
                    val tool = item.optString("tool").takeIf(String::isNotBlank) ?: continue
                    val count = item.optInt("count", 0).coerceAtLeast(0)
                    val successes = item.optInt("successfulCount", 0).coerceIn(0, count)
                    transitions.getOrPut(key) { mutableMapOf() }[tool] = TransitionStats(
                        count = count,
                        successfulCount = successes,
                        rewardMean = item.optDouble("rewardMean", 0.5).coerceIn(0.0, 1.0),
                        lastSeen = item.optLong("lastSeen", 0L)
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadState failed: ${e.message}")
        }
    }

    private fun format(value: Double): String = "%.2f".format(value)

    private fun <T> ArrayDeque<T>.takeLastCompat(count: Int): List<T> {
        if (count <= 0 || isEmpty()) return emptyList()
        return toList().takeLast(count)
    }
}
