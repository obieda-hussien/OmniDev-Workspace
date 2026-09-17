package com.omnidev.workspace.data.tools.orchestration

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Local contextual Bayesian bandit for tool recommendations.
 *
 * The previous implementation was labelled Q-learning but bootstrapped every action from the
 * maximum Q-value across all tools without a real next-state/action model. That can inflate values
 * and random epsilon exploration can recommend an irrelevant tool. This engine instead learns
 * calibrated evidence that is actually available on-device:
 *
 * - global Beta posterior for tool success
 * - context-specific Beta posterior (time/network/battery class)
 * - previous-tool -> next-tool transition posterior
 * - bounded quality/speed reward mean
 * - Wilson lower bound to avoid overconfidence from tiny samples
 * - a very small deterministic uncertainty bonus for under-observed tools
 *
 * Prediction itself is deterministic. Exploration changes confidence slightly; it never randomly
 * executes or recommends a tool.
 */
class ToolIntelligenceEngine(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER")
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    companion object {
        private const val TAG = "ToolIntelligence"
        private const val PREFS_NAME = "tool_intelligence_engine"
        private const val PREFS_KEY_STATE_V2 = "state_v2"
        private const val PREFS_KEY_STATE_V1 = "state_v1"
        private const val PERFORMANCE_WINDOW = 100
        private const val MAX_HISTORY = 1_000
        private const val SLOW_THRESHOLD_MS = 5_000L
        private const val MAX_CONTEXTS_PER_TOOL = 32
        private const val MIN_PATTERN_FREQUENCY = 3
        private const val PRIOR_ALPHA = 2.0
        private const val PRIOR_BETA = 2.0
        private const val CONTEXT_PRIOR_ALPHA = 1.5
        private const val CONTEXT_PRIOR_BETA = 1.5
        private const val WILSON_Z = 1.2815515655446004 // ~80% lower confidence bound
    }

    private data class ToolExecutionRecord(
        val toolName: String,
        val executionTimeMs: Long,
        val success: Boolean,
        val resultQuality: Float,
        val timestamp: Long = System.currentTimeMillis(),
        val context: ExecutionContext
    )

    internal data class ExecutionContext(
        val previousTool: String?,
        val timeOfDay: Int,
        val dayOfWeek: Int,
        val batteryLevel: Int,
        val networkType: String
    )

    private data class ContextStats(
        var attempts: Int = 0,
        var successes: Int = 0,
        var rewardMean: Double = 0.5,
        var lastUsed: Long = 0L
    )

    private data class ToolState(
        val toolName: String,
        var executionCount: Int = 0,
        var successCount: Int = 0,
        var avgExecutionTime: Long = 0L,
        var rewardMean: Double = 0.5,
        var rewardM2: Double = 0.0,
        var lastUsed: Long = 0L,
        val contexts: MutableMap<String, ContextStats> = mutableMapOf()
    )

    private data class TransitionStats(
        var attempts: Int = 0,
        var successes: Int = 0,
        var rewardMean: Double = 0.5,
        var lastSeen: Long = 0L
    )

    private data class ScoreBreakdown(
        val score: Double,
        val confidence: Double,
        val globalPosterior: Double,
        val contextPosterior: Double,
        val transitionPosterior: Double,
        val reward: Double,
        val speed: Double,
        val wilsonLower: Double,
        val evidenceStrength: Double,
        val uncertaintyBonus: Double
    )

    private val executionHistory = mutableListOf<ToolExecutionRecord>()
    private val toolStates = ConcurrentHashMap<String, ToolState>()
    private val transitions = ConcurrentHashMap<Pair<String, String>, TransitionStats>()
    private val updateLock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Deterministically recommends the best evidenced tool among currently available tools. */
    internal fun predictBestTool(
        taskDescription: String,
        availableTools: List<String>,
        currentContext: ExecutionContext
    ): PredictionResult {
        if (availableTools.isEmpty()) {
            return PredictionResult("", 0f, "No tools are available.", emptyList())
        }

        val scored = synchronized(updateLock) {
            availableTools.distinct().associateWith { toolName ->
                calculateToolScore(toolName, taskDescription, currentContext)
            }
        }

        val ordered = scored.entries.sortedWith(
            compareByDescending<Map.Entry<String, ScoreBreakdown>> { it.value.score }
                .thenByDescending { it.value.confidence }
                .thenBy { it.key }
        )
        val best = ordered.first()
        val alternatives = ordered.drop(1).take(3).map {
            AlternativeTool(it.key, it.value.score)
        }

        return PredictionResult(
            recommendedTool = best.key,
            confidence = best.value.confidence.toFloat().coerceIn(0f, 1f),
            reasoning = buildReasoningExplanation(best.key, best.value, currentContext),
            alternatives = alternatives
        )
    }

    private fun calculateToolScore(
        toolName: String,
        @Suppress("UNUSED_PARAMETER") taskDescription: String,
        context: ExecutionContext
    ): ScoreBreakdown {
        val state = toolStates[toolName] ?: ToolState(toolName)
        val failures = (state.executionCount - state.successCount).coerceAtLeast(0)
        val globalPosterior = betaMean(
            state.successCount.toDouble() + PRIOR_ALPHA,
            failures.toDouble() + PRIOR_BETA
        )
        val wilson = wilsonLowerBound(state.successCount, state.executionCount)

        val contextKey = buildContextKey(context)
        val contextStats = state.contexts[contextKey]
        val contextPosterior = if (contextStats != null) {
            betaMean(
                contextStats.successes + CONTEXT_PRIOR_ALPHA,
                (contextStats.attempts - contextStats.successes).coerceAtLeast(0) + CONTEXT_PRIOR_BETA
            )
        } else {
            globalPosterior
        }

        val transitionStats = context.previousTool?.let { transitions[it to toolName] }
        val transitionPosterior = if (transitionStats != null) {
            betaMean(
                transitionStats.successes + CONTEXT_PRIOR_ALPHA,
                (transitionStats.attempts - transitionStats.successes).coerceAtLeast(0) + CONTEXT_PRIOR_BETA
            )
        } else {
            globalPosterior
        }

        val speed = speedScore(state.avgExecutionTime)
        val reward = state.rewardMean.coerceIn(0.0, 1.0)
        val evidenceStrength = evidenceStrength(state.executionCount)
        val uncertaintyBonus = deterministicUncertaintyBonus(state.executionCount)
        val recency = recencyScore(state.lastUsed)

        // Conservative evidence score. Wilson lower bound gets meaningful influence only after
        // observations exist; priors keep unseen tools neutral instead of treating them as bad.
        val raw = (
            globalPosterior * 0.27 +
                contextPosterior * 0.22 +
                transitionPosterior * 0.17 +
                reward * 0.15 +
                speed * 0.07 +
                (if (state.executionCount > 0) wilson else 0.5) * 0.08 +
                recency * 0.02 +
                uncertaintyBonus * 0.02
            ).coerceIn(0.0, 1.0)

        // Confidence is not the same as score. Sparse evidence must remain low-confidence even if
        // priors happen to make the tool rank first.
        val marginFromNeutral = kotlin.math.abs(raw - 0.5) * 2.0
        val confidence = (
            0.18 +
                evidenceStrength * 0.57 +
                marginFromNeutral * 0.18 +
                (if (contextStats != null && contextStats.attempts >= 3) 0.07 else 0.0)
            ).coerceIn(0.0, 0.98)

        return ScoreBreakdown(
            score = raw,
            confidence = confidence,
            globalPosterior = globalPosterior,
            contextPosterior = contextPosterior,
            transitionPosterior = transitionPosterior,
            reward = reward,
            speed = speed,
            wilsonLower = wilson,
            evidenceStrength = evidenceStrength,
            uncertaintyBonus = uncertaintyBonus
        )
    }

    internal suspend fun recordExecution(
        toolName: String,
        @Suppress("UNUSED_PARAMETER") parameters: Map<String, String>,
        executionTimeMs: Long,
        success: Boolean,
        resultQuality: Float,
        context: ExecutionContext
    ) = withContext(Dispatchers.Default) {
        val boundedQuality = resultQuality.coerceIn(0f, 1f)
        val speed = speedScore(executionTimeMs)
        val reward = if (success) {
            (boundedQuality * 0.82 + speed.toFloat() * 0.18).coerceIn(0f, 1f).toDouble()
        } else {
            0.0
        }
        val now = System.currentTimeMillis()

        synchronized(updateLock) {
            val state = toolStates.getOrPut(toolName) { ToolState(toolName) }
            state.executionCount++
            if (success) state.successCount++
            state.lastUsed = now
            state.avgExecutionTime = exponentialTimeAverage(state.avgExecutionTime, executionTimeMs)
            updateRewardMoments(state, reward)

            val contextKey = buildContextKey(context)
            val contextStats = state.contexts.getOrPut(contextKey) { ContextStats() }
            contextStats.attempts++
            if (success) contextStats.successes++
            contextStats.rewardMean = onlineMean(
                oldMean = contextStats.rewardMean,
                oldCount = contextStats.attempts - 1,
                value = reward
            )
            contextStats.lastUsed = now
            pruneContexts(state.contexts)

            context.previousTool?.let { previous ->
                val transition = transitions.getOrPut(previous to toolName) { TransitionStats() }
                transition.attempts++
                if (success) transition.successes++
                transition.rewardMean = onlineMean(
                    oldMean = transition.rewardMean,
                    oldCount = transition.attempts - 1,
                    value = reward
                )
                transition.lastSeen = now
            }
            pruneTransitions(now)

            executionHistory += ToolExecutionRecord(
                toolName = toolName,
                executionTimeMs = executionTimeMs,
                success = success,
                resultQuality = boundedQuality,
                context = context
            )
            if (executionHistory.size > MAX_HISTORY) {
                executionHistory.subList(0, executionHistory.size - MAX_HISTORY).clear()
            }
        }
    }

    private fun updateRewardMoments(state: ToolState, reward: Double) {
        // Welford online mean/variance; executionCount already includes current observation.
        val n = state.executionCount
        if (n <= 1) {
            state.rewardMean = reward
            state.rewardM2 = 0.0
            return
        }
        val delta = reward - state.rewardMean
        state.rewardMean += delta / n.toDouble()
        val delta2 = reward - state.rewardMean
        state.rewardM2 += delta * delta2
    }

    private fun onlineMean(oldMean: Double, oldCount: Int, value: Double): Double {
        val count = oldCount.coerceAtLeast(0)
        return if (count == 0) value else oldMean + (value - oldMean) / (count + 1).toDouble()
    }

    private fun exponentialTimeAverage(oldMs: Long, newMs: Long): Long {
        val bounded = newMs.coerceAtLeast(0L)
        if (oldMs <= 0L) return bounded
        return (oldMs * 0.82 + bounded * 0.18).toLong()
    }

    private fun betaMean(alpha: Double, beta: Double): Double =
        if (alpha + beta <= 0.0) 0.5 else (alpha / (alpha + beta)).coerceIn(0.0, 1.0)

    private fun wilsonLowerBound(successes: Int, total: Int): Double {
        if (total <= 0) return 0.5
        val n = total.toDouble()
        val p = successes.coerceIn(0, total).toDouble() / n
        val z2 = WILSON_Z * WILSON_Z
        val center = p + z2 / (2.0 * n)
        val spread = WILSON_Z * sqrt((p * (1.0 - p) + z2 / (4.0 * n)) / n)
        val denominator = 1.0 + z2 / n
        return ((center - spread) / denominator).coerceIn(0.0, 1.0)
    }

    private fun evidenceStrength(observations: Int): Double =
        (observations.toDouble() / (observations + 8.0)).coerceIn(0.0, 1.0)

    /** Deterministic optimism bonus, deliberately too small to override strong negative evidence. */
    private fun deterministicUncertaintyBonus(observations: Int): Double {
        val n = observations.coerceAtLeast(0).toDouble()
        return (sqrt(ln(n + 3.0) / (n + 1.0)) * 0.35).coerceIn(0.0, 0.35)
    }

    private fun speedScore(executionTimeMs: Long): Double {
        if (executionTimeMs <= 0L) return 0.5
        return (1.0 - executionTimeMs.toDouble() / SLOW_THRESHOLD_MS.toDouble())
            .coerceIn(0.0, 1.0)
    }

    private fun recencyScore(lastUsed: Long): Double {
        if (lastUsed <= 0L) return 0.0
        val ageHours = (System.currentTimeMillis() - lastUsed).coerceAtLeast(0L) / 3_600_000.0
        return (1.0 / (1.0 + ageHours / 48.0)).coerceIn(0.0, 1.0)
    }

    private fun buildContextKey(context: ExecutionContext): String {
        val dayBand = if (context.dayOfWeek == 1 || context.dayOfWeek == 7) "weekend" else "weekday"
        val timeBand = when (context.timeOfDay.coerceIn(0, 23)) {
            in 0..5 -> "night"
            in 6..11 -> "morning"
            in 12..17 -> "afternoon"
            else -> "evening"
        }
        val batteryBand = when (context.batteryLevel) {
            in 0..14 -> "battery_critical"
            in 15..39 -> "battery_low"
            in 40..100 -> "battery_ok"
            else -> "battery_unknown"
        }
        val network = context.networkType.ifBlank { "unknown" }.lowercase().take(24)
        return "$dayBand|$timeBand|$batteryBand|$network"
    }

    private fun pruneContexts(contexts: MutableMap<String, ContextStats>) {
        if (contexts.size <= MAX_CONTEXTS_PER_TOOL) return
        val removeCount = contexts.size - MAX_CONTEXTS_PER_TOOL
        contexts.entries
            .sortedWith(compareBy<Map.Entry<String, ContextStats>> { it.value.attempts }
                .thenBy { it.value.lastUsed })
            .take(removeCount)
            .forEach { contexts.remove(it.key) }
    }

    private fun pruneTransitions(now: Long) {
        if (transitions.size <= 512) return
        val staleBefore = now - 30L * 24 * 3_600_000L
        transitions.entries.removeIf {
            it.value.attempts < MIN_PATTERN_FREQUENCY && it.value.lastSeen < staleBefore
        }
        if (transitions.size > 512) {
            transitions.entries
                .sortedWith(compareBy<Map.Entry<Pair<String, String>, TransitionStats>> { it.value.attempts }
                    .thenBy { it.value.lastSeen })
                .take(transitions.size - 512)
                .forEach { transitions.remove(it.key) }
        }
    }

    fun analyzePerformance(): PerformanceReport {
        val snapshot = synchronized(updateLock) {
            val recentExecutions = executionHistory.takeLast(PERFORMANCE_WINDOW)
            val topPerformers = toolStates.values
                .filter { it.executionCount >= 3 }
                .sortedByDescending {
                    betaMean(
                        it.successCount + PRIOR_ALPHA,
                        (it.executionCount - it.successCount).coerceAtLeast(0) + PRIOR_BETA
                    )
                }
                .take(5)
                .map {
                    ToolPerformanceMetrics(
                        toolName = it.toolName,
                        successRate = if (it.executionCount == 0) 0.0
                        else it.successCount.toDouble() / it.executionCount.toDouble(),
                        avgExecutionTime = it.avgExecutionTime,
                        usageCount = it.executionCount
                    )
                }
            val slowTools = toolStates.values
                .filter { it.avgExecutionTime > SLOW_THRESHOLD_MS }
                .sortedByDescending { it.avgExecutionTime }
                .map { it.toolName to it.avgExecutionTime }
            val underutilized = toolStates.values
                .filter { it.executionCount < 3 }
                .map { it.toolName }
            val reliableTransitions = transitions.values.count { it.attempts >= MIN_PATTERN_FREQUENCY }
            val overall = if (recentExecutions.isEmpty()) 0f else
                recentExecutions.count { it.success }.toFloat() / recentExecutions.size.toFloat()

            PerformanceReport(
                totalExecutions = executionHistory.size,
                overallSuccessRate = overall,
                topPerformers = topPerformers,
                slowTools = slowTools,
                underutilizedTools = underutilized,
                detectedPatternsCount = reliableTransitions,
                explorationRate = 0f
            )
        }
        return snapshot
    }

    private fun buildReasoningExplanation(
        tool: String,
        score: ScoreBreakdown,
        context: ExecutionContext
    ): String = buildString {
        append("Tool $tool: score=${percent(score.score)}, confidence=${percent(score.confidence)}")
        append(", global=${percent(score.globalPosterior)}")
        append(", context=${percent(score.contextPosterior)}")
        if (context.previousTool != null) {
            append(", transition=${percent(score.transitionPosterior)}")
        }
        append(", reward=${percent(score.reward)}")
        append(", speed=${percent(score.speed)}")
        append(", conservative=${percent(score.wilsonLower)}")
    }

    private fun percent(value: Double): String = "${(value.coerceIn(0.0, 1.0) * 100).toInt()}%"

    data class PredictionResult(
        val recommendedTool: String,
        val confidence: Float,
        val reasoning: String,
        val alternatives: List<AlternativeTool>
    )

    data class AlternativeTool(
        val name: String,
        val score: Double
    )

    data class PerformanceReport(
        val totalExecutions: Int,
        val overallSuccessRate: Float,
        val topPerformers: List<ToolPerformanceMetrics>,
        val slowTools: List<Pair<String, Long>>,
        val underutilizedTools: List<String>,
        val detectedPatternsCount: Int,
        /** Kept for UI/source compatibility. Predictions are now deterministic. */
        val explorationRate: Float
    )

    data class ToolPerformanceMetrics(
        val toolName: String,
        val successRate: Double,
        val avgExecutionTime: Long,
        val usageCount: Int
    )

    @Serializable
    private data class EngineSnapshotV2(
        val version: Int = 2,
        val toolStates: List<ToolStateSnapshotV2>,
        val transitions: List<TransitionSnapshotV2>
    )

    @Serializable
    private data class ToolStateSnapshotV2(
        val toolName: String,
        val executionCount: Int,
        val successCount: Int,
        val avgExecutionTime: Long,
        val rewardMean: Double,
        val rewardM2: Double,
        val lastUsed: Long,
        val contexts: Map<String, ContextStatsSnapshotV2>
    )

    @Serializable
    private data class ContextStatsSnapshotV2(
        val attempts: Int,
        val successes: Int,
        val rewardMean: Double,
        val lastUsed: Long
    )

    @Serializable
    private data class TransitionSnapshotV2(
        val fromTool: String,
        val toTool: String,
        val attempts: Int,
        val successes: Int,
        val rewardMean: Double,
        val lastSeen: Long
    )

    /** Legacy V1 schema for one-way migration from the old pseudo-Q-learning state. */
    @Serializable
    private data class LegacyEngineSnapshotV1(
        val explorationRate: Double = 0.0,
        val toolStates: List<LegacyToolStateSnapshot> = emptyList(),
        val detectedPatterns: List<LegacyToolPatternSnapshot> = emptyList(),
        val transitions: List<LegacyTransitionSnapshot> = emptyList()
    )

    @Serializable
    private data class LegacyToolStateSnapshot(
        val toolName: String,
        val qValue: Double = 0.0,
        val executionCount: Int = 0,
        val successCount: Int = 0,
        val avgExecutionTime: Long = 0L,
        val lastUsed: Long = 0L,
        val contextualPreferences: Map<String, Double> = emptyMap()
    )

    @Serializable
    private data class LegacyToolPatternSnapshot(
        val key: String = "",
        val sequence: List<String> = emptyList(),
        val frequency: Int = 0,
        val avgSuccessRate: Float = 0f,
        val lastSeen: Long = 0L
    )

    @Serializable
    private data class LegacyTransitionSnapshot(
        val fromTool: String,
        val toTool: String,
        val count: Int
    )

    suspend fun persist() = withContext(Dispatchers.IO) {
        try {
            val snapshot = synchronized(updateLock) {
                EngineSnapshotV2(
                    toolStates = toolStates.values.map { state ->
                        ToolStateSnapshotV2(
                            toolName = state.toolName,
                            executionCount = state.executionCount,
                            successCount = state.successCount,
                            avgExecutionTime = state.avgExecutionTime,
                            rewardMean = state.rewardMean,
                            rewardM2 = state.rewardM2,
                            lastUsed = state.lastUsed,
                            contexts = state.contexts.mapValues { (_, stats) ->
                                ContextStatsSnapshotV2(
                                    attempts = stats.attempts,
                                    successes = stats.successes,
                                    rewardMean = stats.rewardMean,
                                    lastUsed = stats.lastUsed
                                )
                            }
                        )
                    },
                    transitions = transitions.map { (pair, stats) ->
                        TransitionSnapshotV2(
                            fromTool = pair.first,
                            toTool = pair.second,
                            attempts = stats.attempts,
                            successes = stats.successes,
                            rewardMean = stats.rewardMean,
                            lastSeen = stats.lastSeen
                        )
                    }
                )
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREFS_KEY_STATE_V2, json.encodeToString(snapshot))
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "persist() failed: ${e.message}")
        }
    }

    suspend fun restore() = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        try {
            val v2 = prefs.getString(PREFS_KEY_STATE_V2, null)
            if (!v2.isNullOrBlank()) {
                restoreV2(json.decodeFromString<EngineSnapshotV2>(v2))
                return@withContext
            }

            val legacy = prefs.getString(PREFS_KEY_STATE_V1, null)
            if (!legacy.isNullOrBlank()) {
                migrateV1(json.decodeFromString<LegacyEngineSnapshotV1>(legacy))
                persist()
            }
        } catch (e: Exception) {
            Log.w(TAG, "restore() failed: ${e.message}")
        }
    }

    private fun restoreV2(snapshot: EngineSnapshotV2) {
        synchronized(updateLock) {
            toolStates.clear()
            snapshot.toolStates.forEach { saved ->
                toolStates[saved.toolName] = ToolState(
                    toolName = saved.toolName,
                    executionCount = saved.executionCount.coerceAtLeast(0),
                    successCount = saved.successCount.coerceIn(0, saved.executionCount.coerceAtLeast(0)),
                    avgExecutionTime = saved.avgExecutionTime.coerceAtLeast(0L),
                    rewardMean = saved.rewardMean.coerceIn(0.0, 1.0),
                    rewardM2 = saved.rewardM2.coerceAtLeast(0.0),
                    lastUsed = saved.lastUsed,
                    contexts = saved.contexts.mapValuesTo(mutableMapOf()) { (_, stats) ->
                        ContextStats(
                            attempts = stats.attempts.coerceAtLeast(0),
                            successes = stats.successes.coerceIn(0, stats.attempts.coerceAtLeast(0)),
                            rewardMean = stats.rewardMean.coerceIn(0.0, 1.0),
                            lastUsed = stats.lastUsed
                        )
                    }
                )
            }

            transitions.clear()
            snapshot.transitions.forEach { saved ->
                transitions[saved.fromTool to saved.toTool] = TransitionStats(
                    attempts = saved.attempts.coerceAtLeast(0),
                    successes = saved.successes.coerceIn(0, saved.attempts.coerceAtLeast(0)),
                    rewardMean = saved.rewardMean.coerceIn(0.0, 1.0),
                    lastSeen = saved.lastSeen
                )
            }
        }
    }

    private fun migrateV1(snapshot: LegacyEngineSnapshotV1) {
        synchronized(updateLock) {
            toolStates.clear()
            snapshot.toolStates.forEach { old ->
                val executions = old.executionCount.coerceAtLeast(0)
                val successes = old.successCount.coerceIn(0, executions)
                // Ignore old qValue because it was not a calibrated probability. Preserve only
                // factual counts/timing; derive a neutral reward prior from empirical success.
                val empirical = if (executions == 0) 0.5 else successes.toDouble() / executions
                toolStates[old.toolName] = ToolState(
                    toolName = old.toolName,
                    executionCount = executions,
                    successCount = successes,
                    avgExecutionTime = old.avgExecutionTime.coerceAtLeast(0L),
                    rewardMean = empirical.coerceIn(0.0, 1.0),
                    rewardM2 = 0.0,
                    lastUsed = old.lastUsed
                )
            }

            transitions.clear()
            snapshot.transitions.forEach { old ->
                // V1 only knew transition frequency, not outcome. Import as neutral evidence so
                // frequency is preserved without pretending those transitions were successful.
                transitions[old.fromTool to old.toTool] = TransitionStats(
                    attempts = old.count.coerceAtLeast(0),
                    successes = (old.count.coerceAtLeast(0) / 2),
                    rewardMean = 0.5,
                    lastSeen = 0L
                )
            }
        }
    }
}
