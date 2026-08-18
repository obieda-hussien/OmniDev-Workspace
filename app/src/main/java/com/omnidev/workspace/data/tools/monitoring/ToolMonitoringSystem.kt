package com.omnidev.workspace.data.tools.monitoring

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * ToolMonitoringSystem — [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]
 * 
 * [Localized]:
 * - [Localized] [Localized] [Localized] [Localized] [Localized]
 * - [Localized] [Localized] [Localized]
 * - [Localized] [Localized] [Localized] [Localized] [Localized]
 * - [Localized] [Localized] [Localized] [Localized]
 * - [Localized] [Localized] [Localized] [Localized] [Localized]
 */
object ToolMonitoringSystem {
    private const val TAG = "ToolMonitor"

    // Shared scope for fire-and-forget event emissions
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // ═══════════════════════════════════════════════════════════════
    // [Localized] [Localized]
    // ═══════════════════════════════════════════════════════════════
    
    private val executionMetrics = ConcurrentHashMap<String, ToolMetrics>()
    private val activeExecutions = ConcurrentHashMap<String, ExecutionTrace>()
    private val realtimeEvents = MutableSharedFlow<MonitoringEvent>(replay = 100)
    
    // [Localized] [Localized]
    private val totalExecutions = AtomicLong(0)
    private val totalFailures = AtomicLong(0)
    private val totalRetries = AtomicLong(0)
    
    // ═══════════════════════════════════════════════════════════════
    // [Localized] [Localized]
    // ═══════════════════════════════════════════════════════════════
    
    data class ToolMetrics(
        val toolName: String,
        var executionCount: Long = 0,
        var successCount: Long = 0,
        var failureCount: Long = 0,
        var totalDurationMs: Long = 0,
        var minDurationMs: Long = Long.MAX_VALUE,
        var maxDurationMs: Long = 0,
        var lastExecutionTime: Long = 0,
        var avgDurationMs: Double = 0.0,
        val errorFrequency: MutableMap<String, Int> = mutableMapOf(),
        val performanceHistory: MutableList<PerformanceSnapshot> = mutableListOf()
    )
    
    data class PerformanceSnapshot(
        val timestamp: Long,
        val durationMs: Long,
        val success: Boolean,
        val errorType: String? = null
    )
    
    data class ExecutionTrace(
        val traceId: String,
        val toolName: String,
        val startTime: Long,
        val parameters: Map<String, String>,
        val stackDepth: Int,
        var dependencies: List<String> = emptyList()
    )
    
    sealed class MonitoringEvent {
        data class ToolStarted(val toolName: String, val traceId: String, val timestamp: Long) : MonitoringEvent()
        data class ToolCompleted(val toolName: String, val traceId: String, val durationMs: Long, val success: Boolean) : MonitoringEvent()
        data class ToolFailed(val toolName: String, val traceId: String, val error: String) : MonitoringEvent()
        data class SlowExecution(val toolName: String, val durationMs: Long, val threshold: Long) : MonitoringEvent()
        data class HighFailureRate(val toolName: String, val failureRate: Double) : MonitoringEvent()
        data class AnomalyDetected(val toolName: String, val anomalyType: String, val details: String) : MonitoringEvent()
    }
    
    // ═══════════════════════════════════════════════════════════════
    // API [Localized]
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * [Localized] [Localized] [Localized] [Localized]
     */
    fun startExecution(
        toolName: String,
        parameters: Map<String, String> = emptyMap(),
        stackDepth: Int = 0
    ): String {
        val traceId = generateTraceId()
        val trace = ExecutionTrace(
            traceId = traceId,
            toolName = toolName,
            startTime = System.currentTimeMillis(),
            parameters = parameters,
            stackDepth = stackDepth
        )
        
        activeExecutions[traceId] = trace
        totalExecutions.incrementAndGet()
        
        // [Localized] [Localized] [Localized]
        scope.launch {
            realtimeEvents.emit(
                MonitoringEvent.ToolStarted(toolName, traceId, trace.startTime)
            )
        }
        
        return traceId
    }
    
    /**
     * [Localized] [Localized] [Localized] [Localized]
     */
    fun endExecution(
        traceId: String,
        success: Boolean,
        errorMessage: String? = null
    ) {
        val trace = activeExecutions.remove(traceId) ?: return
        val durationMs = System.currentTimeMillis() - trace.startTime
        
        // [Localized] [Localized]
        val metrics = executionMetrics.getOrPut(trace.toolName) {
            ToolMetrics(trace.toolName)
        }
        
        synchronized(metrics) {
            metrics.executionCount++
            if (success) {
                metrics.successCount++
            } else {
                metrics.failureCount++
                totalFailures.incrementAndGet()
                errorMessage?.let { msg ->
                    val errorType = extractErrorType(msg)
                    metrics.errorFrequency[errorType] = 
                        metrics.errorFrequency.getOrDefault(errorType, 0) + 1
                }
            }
            
            metrics.totalDurationMs += durationMs
            metrics.minDurationMs = minOf(metrics.minDurationMs, durationMs)
            metrics.maxDurationMs = maxOf(metrics.maxDurationMs, durationMs)
            metrics.lastExecutionTime = System.currentTimeMillis()
            metrics.avgDurationMs = metrics.totalDurationMs.toDouble() / metrics.executionCount
            
            // [Localized] [Localized] [Localized]
            metrics.performanceHistory.add(
                PerformanceSnapshot(
                    timestamp = System.currentTimeMillis(),
                    durationMs = durationMs,
                    success = success,
                    errorType = errorMessage?.let { extractErrorType(it) }
                )
            )
            
            // [Localized] [Localized] [Localized]
            if (metrics.performanceHistory.size > 500) {
                metrics.performanceHistory.removeAt(0)
            }
        }
        
        // [Localized] [Localized]
        scope.launch {
            realtimeEvents.emit(
                MonitoringEvent.ToolCompleted(trace.toolName, traceId, durationMs, success)
            )
            
            if (!success && errorMessage != null) {
                realtimeEvents.emit(
                    MonitoringEvent.ToolFailed(trace.toolName, traceId, errorMessage)
                )
            }
            
            // [Localized] [Localized] [Localized]
            if (durationMs > 5000) {
                realtimeEvents.emit(
                    MonitoringEvent.SlowExecution(trace.toolName, durationMs, 5000)
                )
            }
            
            // [Localized] [Localized] [Localized] [Localized]
            val failureRate = metrics.failureCount.toDouble() / metrics.executionCount
            if (metrics.executionCount >= 10 && failureRate > 0.3) {
                realtimeEvents.emit(
                    MonitoringEvent.HighFailureRate(trace.toolName, failureRate)
                )
            }
            
            // [Localized] [Localized]
            detectAnomalies(trace.toolName, metrics)
        }
    }
    
    /**
     * [Localized] [Localized] [Localized] [Localized] [Localized]
     */
    fun getToolMetrics(toolName: String): ToolMetrics? {
        return executionMetrics[toolName]
    }
    
    /**
     * [Localized] [Localized] [Localized] [Localized]
     */
    fun getAllMetrics(): Map<String, ToolMetrics> {
        return executionMetrics.toMap()
    }
    
    /**
     * [Localized] [Localized] [Localized] [Localized] [Localized]
     */
    fun getMostUsedTools(limit: Int = 10): List<Pair<String, Long>> {
        return executionMetrics.entries
            .sortedByDescending { it.value.executionCount }
            .take(limit)
            .map { it.key to it.value.executionCount }
    }
    
    /**
     * [Localized] [Localized] [Localized] [Localized]
     */
    fun getSlowestTools(limit: Int = 10): List<Pair<String, Double>> {
        return executionMetrics.entries
            .sortedByDescending { it.value.avgDurationMs }
            .take(limit)
            .map { it.key to it.value.avgDurationMs }
    }
    
    /**
     * [Localized] [Localized] [Localized] [Localized] [Localized]
     */
    fun getMostFailedTools(limit: Int = 10): List<Pair<String, Long>> {
        return executionMetrics.entries
            .sortedByDescending { it.value.failureCount }
            .take(limit)
            .map { it.key to it.value.failureCount }
    }
    
    /**
     * [Localized] [Localized] [Localized] [Localized]
     */
    fun getEventStream(): SharedFlow<MonitoringEvent> = realtimeEvents.asSharedFlow()
    
    /**
     * [Localized] [Localized] [Localized] [Localized]
     */
    fun resetAllMetrics() {
        executionMetrics.clear()
        activeExecutions.clear()
        totalExecutions.set(0)
        totalFailures.set(0)
        totalRetries.set(0)
    }
    
    /**
     * [Localized] [Localized] [Localized] [Localized] [Localized]
     */
    fun generateSystemReport(): SystemHealthReport {
        val now = System.currentTimeMillis()
        val allMetrics = getAllMetrics()
        
        val totalExecs = totalExecutions.get()
        val totalFails = totalFailures.get()
        val globalFailureRate = if (totalExecs > 0) totalFails.toDouble() / totalExecs else 0.0
        
        // [Localized] [Localized] [Localized]
        val activeTools = activeExecutions.values.groupBy { it.toolName }
            .mapValues { it.value.size }
        
        // [Localized] [Localized] ([Localized] [Localized] > 50%)
        val brokenTools = allMetrics.filter { (_, metrics) ->
            metrics.executionCount >= 5 && 
            metrics.failureCount.toDouble() / metrics.executionCount > 0.5
        }.keys.toList()
        
        // [Localized] [Localized] ([Localized] > 3 [Localized])
        val slowTools = allMetrics.filter { (_, metrics) ->
            metrics.avgDurationMs > 3000
        }.keys.toList()
        
        // [Localized] [Localized] [Localized]
        val topErrors = allMetrics.values
            .flatMap { it.errorFrequency.entries }
            .groupBy { it.key }
            .mapValues { it.value.sumOf { entry -> entry.value } }
            .entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key to it.value }
        
        return SystemHealthReport(
            timestamp = now,
            totalExecutions = totalExecs,
            totalFailures = totalFails,
            globalFailureRate = globalFailureRate,
            totalRetries = totalRetries.get(),
            activeExecutionsCount = activeExecutions.size,
            activeToolsBreakdown = activeTools,
            toolsMonitored = allMetrics.size,
            brokenTools = brokenTools,
            slowTools = slowTools,
            topErrors = topErrors,
            mostUsedTools = getMostUsedTools(5),
            slowestTools = getSlowestTools(5),
            mostFailedTools = getMostFailedTools(5)
        )
    }
    
    // ═══════════════════════════════════════════════════════════════
    // [Localized] [Localized] [Localized]
    // ═══════════════════════════════════════════════════════════════
    
    private suspend fun detectAnomalies(toolName: String, metrics: ToolMetrics) {
        // 1. [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]
        if (metrics.performanceHistory.size >= 20) {
            val recentAvg = metrics.performanceHistory.takeLast(5)
                .filter { it.success }
                .map { it.durationMs }
                .average()
            
            val historicalAvg = metrics.performanceHistory
                .dropLast(5)
                .filter { it.success }
                .map { it.durationMs }
                .average()
            
            if (recentAvg > historicalAvg * 2) {
                realtimeEvents.emit(
                    MonitoringEvent.AnomalyDetected(
                        toolName,
                        "Performance Degradation",
                        "Recent executions are 2x slower than historical average"
                    )
                )
            }
        }
        
        // 2. [Localized] [Localized] [Localized]
        val recentFailures = metrics.performanceHistory.takeLast(5).count { !it.success }
        if (recentFailures >= 3) {
            realtimeEvents.emit(
                MonitoringEvent.AnomalyDetected(
                    toolName,
                    "Consecutive Failures",
                    "$recentFailures failures in last 5 executions"
                )
            )
        }
        
        // 3. [Localized] [Localized] [Localized]
        val recentErrors = metrics.performanceHistory.takeLast(10)
            .mapNotNull { it.errorType }
            .toSet()
        
        val oldErrors = metrics.performanceHistory
            .dropLast(10)
            .mapNotNull { it.errorType }
            .toSet()
        
        val newErrors = recentErrors - oldErrors
        if (newErrors.isNotEmpty()) {
            realtimeEvents.emit(
                MonitoringEvent.AnomalyDetected(
                    toolName,
                    "New Error Types",
                    "New errors: ${newErrors.joinToString()}"
                )
            )
        }
    }
    
    // ═══════════════════════════════════════════════════════════════
    // [Localized] [Localized]
    // ═══════════════════════════════════════════════════════════════
    
    private fun generateTraceId(): String {
        return "${System.currentTimeMillis()}-${(Math.random() * 10000).toInt()}"
    }
    
    private fun extractErrorType(errorMessage: String): String {
        return when {
            errorMessage.contains("timeout", ignoreCase = true) -> "Timeout"
            errorMessage.contains("permission", ignoreCase = true) -> "Permission"
            errorMessage.contains("network", ignoreCase = true) -> "Network"
            errorMessage.contains("not found", ignoreCase = true) -> "NotFound"
            errorMessage.contains("invalid", ignoreCase = true) -> "InvalidInput"
            errorMessage.contains("null", ignoreCase = true) -> "NullPointer"
            errorMessage.contains("security", ignoreCase = true) -> "Security"
            else -> "Unknown"
        }
    }
    
    // ═══════════════════════════════════════════════════════════════
    // [Localized] [Localized] [Localized]
    // ═══════════════════════════════════════════════════════════════
    
    data class SystemHealthReport(
        val timestamp: Long,
        val totalExecutions: Long,
        val totalFailures: Long,
        val globalFailureRate: Double,
        val totalRetries: Long,
        val activeExecutionsCount: Int,
        val activeToolsBreakdown: Map<String, Int>,
        val toolsMonitored: Int,
        val brokenTools: List<String>,
        val slowTools: List<String>,
        val topErrors: List<Pair<String, Int>>,
        val mostUsedTools: List<Pair<String, Long>>,
        val slowestTools: List<Pair<String, Double>>,
        val mostFailedTools: List<Pair<String, Long>>
    ) {
        fun toFormattedString(): String = buildString {
            appendLine("╔════════════════════════════════════════════════════════════╗")
            appendLine("║         OMNIDEV TOOL MONITORING - SYSTEM REPORT            ║")
            appendLine("╚════════════════════════════════════════════════════════════╝")
            appendLine()
            appendLine("📊 Global Statistics:")
            appendLine("   • Total Executions: $totalExecutions")
            appendLine("   • Total Failures: $totalFailures")
            appendLine("   • Global Failure Rate: ${(globalFailureRate * 100).toInt()}%")
            appendLine("   • Total Retries: $totalRetries")
            appendLine("   • Active Executions: $activeExecutionsCount")
            appendLine("   • Tools Monitored: $toolsMonitored")
            appendLine()
            
            if (activeToolsBreakdown.isNotEmpty()) {
                appendLine("🔄 Active Tools:")
                activeToolsBreakdown.forEach { (tool, count) ->
                    appendLine("   • $tool: $count running")
                }
                appendLine()
            }
            
            if (brokenTools.isNotEmpty()) {
                appendLine("⚠️  Broken Tools (>50% failure rate):")
                brokenTools.forEach { appendLine("   • $it") }
                appendLine()
            }
            
            if (slowTools.isNotEmpty()) {
                appendLine("🐌 Slow Tools (avg >3s):")
                slowTools.forEach { appendLine("   • $it") }
                appendLine()
            }
            
            if (topErrors.isNotEmpty()) {
                appendLine("❌ Top Errors:")
                topErrors.forEach { (error, count) ->
                    appendLine("   • $error: $count occurrences")
                }
                appendLine()
            }
            
            if (mostUsedTools.isNotEmpty()) {
                appendLine("🏆 Most Used Tools:")
                mostUsedTools.forEachIndexed { idx, (tool, count) ->
                    appendLine("   ${idx + 1}. $tool: $count executions")
                }
                appendLine()
            }
            
            if (slowestTools.isNotEmpty()) {
                appendLine("⏱️  Slowest Tools:")
                slowestTools.forEachIndexed { idx, (tool, avgMs) ->
                    appendLine("   ${idx + 1}. $tool: ${avgMs.toInt()}ms avg")
                }
            }
        }
    }
}

/**
 * Extension function [Localized] [Localized] [Localized] [Localized]
 */
suspend inline fun <T> monitoredExecution(
    toolName: String,
    parameters: Map<String, String> = emptyMap(),
    crossinline block: suspend () -> T
): T {
    val traceId = ToolMonitoringSystem.startExecution(toolName, parameters)
    return try {
        val result = block()
        ToolMonitoringSystem.endExecution(traceId, success = true)
        result
    } catch (e: Exception) {
        ToolMonitoringSystem.endExecution(traceId, success = false, errorMessage = e.message)
        throw e
    }
}
