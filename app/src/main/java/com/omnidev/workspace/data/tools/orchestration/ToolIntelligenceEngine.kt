package com.omnidev.workspace.data.tools.orchestration

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp

/**
 * ToolIntelligenceEngine - System awareness note System awareness note System awareness note System awareness note
 * 
 * System awareness note:
 * 1. System awareness note System awareness note System awareness note System awareness note (Reinforcement Learning)
 * 2. System awareness note System awareness note System awareness note
 * 3. System awareness note System awareness note System awareness note
 * 4. System awareness note System awareness note
 * 5. System awareness note System awareness note System awareness note System awareness note
 */
class ToolIntelligenceEngine(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    
    companion object {
        private const val TAG = "ToolIntelligence"
        private const val PREFS_NAME = "tool_intelligence_engine"
        // Versioned snapshot key. Future schema changes should write a new key (e.g. state_v2)
        // and optionally attempt best-effort migration from older keys during restore().
        private const val PREFS_KEY_STATE = "state_v1"
        
        // Hyperparameters
        private const val LEARNING_RATE = 0.1
        private const val DISCOUNT_FACTOR = 0.9
        private const val EXPLORATION_RATE_INITIAL = 0.3
        private const val EXPLORATION_DECAY = 0.995
        private const val MIN_EXPLORATION_RATE = 0.01
        
        // Pattern detection
        private const val PATTERN_WINDOW_SIZE = 10
        private const val MIN_PATTERN_FREQUENCY = 3
        
        // Performance thresholds
        private const val PERFORMANCE_WINDOW = 100
        private const val SLOW_THRESHOLD_MS = 5000L
    }
    
    // ═══════════════════════════════════════════════════════════════
    // State Management
    // ═══════════════════════════════════════════════════════════════
    
    private data class ToolExecutionRecord(
        val toolName: String,
        val parameters: Map<String, String>,
        val executionTimeMs: Long,
        val success: Boolean,
        val resultQuality: Float, // 0.0 - 1.0
        val timestamp: Long = System.currentTimeMillis(),
        val context: ExecutionContext
    )
    
    internal data class ExecutionContext(
        val previousTool: String?,
        val timeOfDay: Int, // 0-23
        val dayOfWeek: Int, // 1-7
        val batteryLevel: Int,
        val networkType: String
    )
    
    private data class ToolState(
        val toolName: String,
        var qValue: Double = 0.0,
        var executionCount: Int = 0,
        var successCount: Int = 0,
        var avgExecutionTime: Long = 0L,
        var lastUsed: Long = 0L,
        var contextualPreferences: MutableMap<String, Double> = ConcurrentHashMap()
    )
    
    private data class ToolPattern(
        val sequence: List<String>,
        val frequency: Int,
        val avgSuccessRate: Float,
        val lastSeen: Long
    )
    
    // Storage
    private val executionHistory = mutableListOf<ToolExecutionRecord>()
    private val toolStates = ConcurrentHashMap<String, ToolState>()
    private val detectedPatterns = ConcurrentHashMap<String, ToolPattern>()
    private val toolTransitions = ConcurrentHashMap<Pair<String, String>, Int>()
    
    private var explorationRate = EXPLORATION_RATE_INITIAL
    private val json = Json { ignoreUnknownKeys = true }
    
    // ═══════════════════════════════════════════════════════════════
    // Core Intelligence Functions
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * System awareness note System awareness note System awareness note System awareness note System awareness note
     */
    internal fun predictBestTool(
        taskDescription: String,
        availableTools: List<String>,
        currentContext: ExecutionContext
    ): PredictionResult {
        val scores = availableTools.associateWith { toolName ->
            calculateToolScore(toolName, taskDescription, currentContext)
        }
        
        val bestTool = if (shouldExplore()) {
            // Exploration: System awareness note System awareness note System awareness note System awareness note
            availableTools.random()
        } else {
            // Exploitation: System awareness note System awareness note System awareness note System awareness note
            scores.maxByOrNull { it.value }?.key ?: availableTools.first()
        }
        
        val confidence = scores[bestTool] ?: 0.0
        val alternatives = scores.entries
            .filter { it.key != bestTool }
            .sortedByDescending { it.value }
            .take(3)
            .map { AlternativeTool(it.key, it.value) }
        
        return PredictionResult(
            recommendedTool = bestTool,
            confidence = confidence.toFloat(),
            reasoning = buildReasoningExplanation(bestTool, scores, currentContext),
            alternatives = alternatives
        )
    }
    
    /**
     * System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note
     */
    private fun calculateToolScore(
        toolName: String,
        taskDescription: String,
        context: ExecutionContext
    ): Double {
        val state = toolStates.getOrPut(toolName) { ToolState(toolName) }
        
        // Q-Learning score
        val qScore = state.qValue
        
        // Success rate score
        val successRate = if (state.executionCount > 0) {
            state.successCount.toDouble() / state.executionCount
        } else 0.5
        
        // Recency score (System awareness note System awareness note System awareness note)
        val recencyScore = if (state.lastUsed > 0) {
            val hoursSinceUse = (System.currentTimeMillis() - state.lastUsed) / 3600000.0
            exp(-hoursSinceUse / 24.0) // System awareness note System awareness note 24 System awareness note
        } else 0.0
        
        // Performance score (System awareness note System awareness note)
        val performanceScore = if (state.avgExecutionTime > 0) {
            1.0 - (state.avgExecutionTime.toDouble() / SLOW_THRESHOLD_MS).coerceIn(0.0, 1.0)
        } else 0.5
        
        // Contextual score
        val contextKey = buildContextKey(context)
        val contextualScore = state.contextualPreferences[contextKey] ?: 0.5
        
        // Pattern matching score
        val patternScore = calculatePatternScore(toolName, context.previousTool)
        
        // Weighted combination
        return (qScore * 0.3 +
                successRate * 0.25 +
                recencyScore * 0.1 +
                performanceScore * 0.15 +
                contextualScore * 0.1 +
                patternScore * 0.1)
    }
    
    /**
     * System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note
     */
    internal suspend fun recordExecution(
        toolName: String,
        parameters: Map<String, String>,
        executionTimeMs: Long,
        success: Boolean,
        resultQuality: Float,
        context: ExecutionContext
    ) = withContext(Dispatchers.Default) {
        val record = ToolExecutionRecord(
            toolName = toolName,
            parameters = parameters,
            executionTimeMs = executionTimeMs,
            success = success,
            resultQuality = resultQuality,
            context = context
        )
        
        synchronized(executionHistory) {
            executionHistory.add(record)
            if (executionHistory.size > PERFORMANCE_WINDOW * 10) {
                executionHistory.removeAt(0)
            }
        }
        
        // Update tool state
        val state = toolStates.getOrPut(toolName) { ToolState(toolName) }
        state.executionCount++
        if (success) state.successCount++
        state.lastUsed = System.currentTimeMillis()
        
        // Update average execution time (exponential moving average)
        state.avgExecutionTime = if (state.avgExecutionTime == 0L) {
            executionTimeMs
        } else {
            ((state.avgExecutionTime * 0.8) + (executionTimeMs * 0.2)).toLong()
        }
        
        // Q-Learning update
        val reward = calculateReward(success, resultQuality, executionTimeMs)
        val oldQ = state.qValue
        val maxNextQ = getMaxQValueForNextState(context)
        state.qValue = oldQ + LEARNING_RATE * (reward + DISCOUNT_FACTOR * maxNextQ - oldQ)
        
        // Update contextual preferences
        val contextKey = buildContextKey(context)
        val oldContextQ = state.contextualPreferences[contextKey] ?: 0.5
        state.contextualPreferences[contextKey] = 
            oldContextQ + LEARNING_RATE * (reward - oldContextQ)
        
        // Update transition probabilities
        context.previousTool?.let { prevTool ->
            val transition = Pair(prevTool, toolName)
            toolTransitions[transition] = (toolTransitions[transition] ?: 0) + 1
        }
        
        // Decay exploration rate
        explorationRate = (explorationRate * EXPLORATION_DECAY)
            .coerceAtLeast(MIN_EXPLORATION_RATE)
        
        // Pattern detection
        detectPatterns()
    }
    
    /**
     * System awareness note System awareness note System awareness note System awareness note
     */
    private fun calculateReward(
        success: Boolean,
        quality: Float,
        executionTime: Long
    ): Double {
        if (!success) return -1.0
        
        val qualityReward = quality.toDouble()
        val speedReward = 1.0 - (executionTime.toDouble() / SLOW_THRESHOLD_MS).coerceIn(0.0, 1.0)
        
        return qualityReward * 0.7 + speedReward * 0.3
    }
    
    private fun getMaxQValueForNextState(context: ExecutionContext): Double {
        return toolStates.values.maxOfOrNull { state ->
            val contextKey = buildContextKey(context)
            state.contextualPreferences[contextKey] ?: state.qValue
        } ?: 0.0
    }
    
    private fun shouldExplore(): Boolean {
        return Math.random() < explorationRate
    }
    
    private fun buildContextKey(context: ExecutionContext): String {
        return "${context.timeOfDay / 6}_${context.dayOfWeek}_${context.networkType}"
    }
    
    // ═══════════════════════════════════════════════════════════════
    // Pattern Detection
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * System awareness note System awareness note System awareness note System awareness note
     */
    private fun detectPatterns() {
        if (executionHistory.size < PATTERN_WINDOW_SIZE) return
        
        val recentHistory = executionHistory.takeLast(PATTERN_WINDOW_SIZE * 3)
        val sequences = mutableMapOf<List<String>, MutableList<ToolExecutionRecord>>()
        
        // Extract sequences of length 2-5
        for (seqLen in 2..5) {
            for (i in 0..(recentHistory.size - seqLen)) {
                val seq = recentHistory.subList(i, i + seqLen).map { it.toolName }
                sequences.getOrPut(seq) { mutableListOf() }
                    .addAll(recentHistory.subList(i, i + seqLen))
            }
        }
        
        // Find frequent patterns
        sequences.forEach { (seq, records) ->
            val frequency = records.size / seq.size
            if (frequency >= MIN_PATTERN_FREQUENCY) {
                val successRate = records.count { it.success }.toFloat() / records.size
                val patternKey = seq.joinToString("->")
                
                detectedPatterns[patternKey] = ToolPattern(
                    sequence = seq,
                    frequency = frequency,
                    avgSuccessRate = successRate,
                    lastSeen = System.currentTimeMillis()
                )
            }
        }
        
        // Clean old patterns
        val now = System.currentTimeMillis()
        detectedPatterns.entries.removeIf { 
            (now - it.value.lastSeen) > 7 * 24 * 3600000L // System awareness note
        }
    }
    
    /**
     * System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note
     */
    private fun calculatePatternScore(
        toolName: String,
        previousTool: String?
    ): Double {
        if (previousTool == null) return 0.0
        
        return detectedPatterns.values
            .filter { pattern ->
                pattern.sequence.size >= 2 &&
                pattern.sequence.getOrNull(pattern.sequence.size - 2) == previousTool &&
                pattern.sequence.last() == toolName
            }
            .maxOfOrNull { it.avgSuccessRate.toDouble() }
            ?: 0.0
    }
    
    // ═══════════════════════════════════════════════════════════════
    // Performance Analysis
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * System awareness note System awareness note System awareness note System awareness note System awareness note
     */
    fun analyzePerformance(): PerformanceReport {
        val recentExecutions = executionHistory.takeLast(PERFORMANCE_WINDOW)
        
        val topPerformers = toolStates.values
            .filter { it.executionCount >= 5 }
            .sortedByDescending { it.successCount.toDouble() / it.executionCount }
            .take(5)
            .map { 
                ToolPerformanceMetrics(
                    toolName = it.toolName,
                    successRate = it.successCount.toDouble() / it.executionCount,
                    avgExecutionTime = it.avgExecutionTime,
                    usageCount = it.executionCount
                )
            }
        
        val slowTools = toolStates.values
            .filter { it.avgExecutionTime > SLOW_THRESHOLD_MS }
            .sortedByDescending { it.avgExecutionTime }
            .map { it.toolName to it.avgExecutionTime }
        
        val underutilizedTools = toolStates.values
            .filter { it.executionCount < 3 }
            .map { it.toolName }
        
        return PerformanceReport(
            totalExecutions = executionHistory.size,
            overallSuccessRate = recentExecutions.count { it.success }.toFloat() / 
                                 recentExecutions.size.coerceAtLeast(1),
            topPerformers = topPerformers,
            slowTools = slowTools,
            underutilizedTools = underutilizedTools,
            detectedPatternsCount = detectedPatterns.size,
            explorationRate = explorationRate.toFloat()
        )
    }
    
    private fun buildReasoningExplanation(
        tool: String,
        scores: Map<String, Double>,
        context: ExecutionContext
    ): String {
        val state = toolStates[tool]
        val score = scores[tool] ?: 0.0
        
        return buildString {
            appendLine("🎯 System awareness note System awareness note: $tool")
            appendLine("📊 System awareness note System awareness note: ${(score * 100).toInt()}%")
            
            state?.let {
                if (it.executionCount > 0) {
                    val successRate = (it.successCount.toDouble() / it.executionCount * 100).toInt()
                    appendLine("✅ System awareness note System awareness note: $successRate% (${it.successCount}/${it.executionCount})")
                }
                
                if (it.avgExecutionTime > 0) {
                    appendLine("⚡ System awareness note System awareness note System awareness note: ${it.avgExecutionTime}ms")
                }
            }
            
            val pattern = detectedPatterns.values.find { 
                it.sequence.last() == tool && 
                it.sequence.getOrNull(it.sequence.size - 2) == context.previousTool
            }
            pattern?.let {
                appendLine("🔗 System awareness note System awareness note System awareness note System awareness note (${it.frequency} System awareness note)")
            }
            
            if (shouldExplore() && scores[tool] != scores.maxByOrNull { it.value }?.value) {
                appendLine("🔍 System awareness note System awareness note - System awareness note System awareness note System awareness note")
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════
    // Data Classes
    // ═══════════════════════════════════════════════════════════════
    
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
        val explorationRate: Float
    )
    
    data class ToolPerformanceMetrics(
        val toolName: String,
        val successRate: Double,
        val avgExecutionTime: Long,
        val usageCount: Int
    )

    @Serializable
    private data class EngineSnapshot(
        val explorationRate: Double,
        val toolStates: List<ToolStateSnapshot>,
        val detectedPatterns: List<ToolPatternSnapshot>,
        val transitions: List<TransitionSnapshot>
    )

    @Serializable
    private data class ToolStateSnapshot(
        val toolName: String,
        val qValue: Double,
        val executionCount: Int,
        val successCount: Int,
        val avgExecutionTime: Long,
        val lastUsed: Long,
        val contextualPreferences: Map<String, Double>
    )

    @Serializable
    private data class ToolPatternSnapshot(
        val key: String,
        val sequence: List<String>,
        val frequency: Int,
        val avgSuccessRate: Float,
        val lastSeen: Long
    )

    @Serializable
    private data class TransitionSnapshot(
        val fromTool: String,
        val toTool: String,
        val count: Int
    )
    
    /**
     * System awareness note System awareness note System awareness note
     */
    suspend fun persist() = withContext(Dispatchers.IO) {
        try {
            val snapshot = EngineSnapshot(
                explorationRate = explorationRate,
                toolStates = toolStates.values.map { state ->
                    ToolStateSnapshot(
                        toolName = state.toolName,
                        qValue = state.qValue,
                        executionCount = state.executionCount,
                        successCount = state.successCount,
                        avgExecutionTime = state.avgExecutionTime,
                        lastUsed = state.lastUsed,
                        contextualPreferences = state.contextualPreferences.toMap()
                    )
                },
                detectedPatterns = detectedPatterns.map { (key, pattern) ->
                    ToolPatternSnapshot(
                        key = key,
                        sequence = pattern.sequence,
                        frequency = pattern.frequency,
                        avgSuccessRate = pattern.avgSuccessRate,
                        lastSeen = pattern.lastSeen
                    )
                },
                transitions = toolTransitions.map { (pair, count) ->
                    TransitionSnapshot(
                        fromTool = pair.first,
                        toTool = pair.second,
                        count = count
                    )
                }
            )

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREFS_KEY_STATE, json.encodeToString(snapshot))
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "persist() failed: ${e.message}")
        }
    }
    
    /**
     * System awareness note System awareness note
     */
    suspend fun restore() = withContext(Dispatchers.IO) {
        try {
            val payload = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREFS_KEY_STATE, null)
                ?: return@withContext

            val snapshot = json.decodeFromString<EngineSnapshot>(payload)

            toolStates.clear()
            snapshot.toolStates.forEach { state ->
                toolStates[state.toolName] = ToolState(
                    toolName = state.toolName,
                    qValue = state.qValue,
                    executionCount = state.executionCount,
                    successCount = state.successCount,
                    avgExecutionTime = state.avgExecutionTime,
                    lastUsed = state.lastUsed,
                    contextualPreferences = ConcurrentHashMap(state.contextualPreferences)
                )
            }

            detectedPatterns.clear()
            snapshot.detectedPatterns.forEach { pattern ->
                detectedPatterns[pattern.key] = ToolPattern(
                    sequence = pattern.sequence,
                    frequency = pattern.frequency,
                    avgSuccessRate = pattern.avgSuccessRate,
                    lastSeen = pattern.lastSeen
                )
            }

            toolTransitions.clear()
            snapshot.transitions.forEach { transition ->
                toolTransitions[Pair(transition.fromTool, transition.toTool)] = transition.count
            }

            explorationRate = snapshot.explorationRate
                .coerceIn(MIN_EXPLORATION_RATE, EXPLORATION_RATE_INITIAL)
        } catch (e: Exception) {
            Log.w(TAG, "restore() failed: ${e.message}")
        }
    }
}
