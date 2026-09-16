package com.omnidev.workspace.data.tools.orchestration

import com.omnidev.workspace.data.tools.ToolExecutionResult
import com.omnidev.workspace.data.tools.ToolExecutionSemantics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * ToolOrchestrator - Advanced Tool Coordination System
 *
 * In addition to transport exceptions, ToolExecutionResult values are normalized
 * through [ToolExecutionSemantics]. This prevents a final shell `echo` or transport
 * success from turning SecurityException/permission/command failures into fake OKs.
 */
class ToolOrchestrator {

    companion object {
        private val LONG_RUNNING_TOOLS = setOf(
            "agent_runtime",
            "advanced_terminal",
            "setup_build_environment",
            "git_manager",
            "build_doctor",
            "auto_heal_build"
        )

        private const val LONG_RUNNING_TIMEOUT_MS = 12 * 60_000L
    }

    private val executionCache = ConcurrentHashMap<String, CachedResult>()
    private val circuitBreakers = ConcurrentHashMap<String, CircuitBreaker>()
    private val executionMetrics = ConcurrentHashMap<String, ToolMetrics>()

    data class CachedResult(
        val result: Any,
        val timestamp: Long,
        val ttl: Duration
    ) {
        fun isValid(): Boolean =
            System.currentTimeMillis() - timestamp < ttl.inWholeMilliseconds
    }

    data class ToolMetrics(
        var totalExecutions: Long = 0,
        var successCount: Long = 0,
        var failureCount: Long = 0,
        var consecutiveFailures: Int = 0,
        var avgExecutionTime: Long = 0,
        var lastExecutionTime: Long = 0,
        var lastError: String? = null
    ) {
        val successRate: Double
            get() = if (totalExecutions == 0L) 1.0 else successCount.toDouble() / totalExecutions.toDouble()

        val reputationScore: Double
            get() {
                val speedScore = when {
                    avgExecutionTime <= 0L -> 1.0
                    else -> (5000.0 / avgExecutionTime.toDouble()).coerceIn(0.1, 1.0)
                }
                return (successRate * 0.8 + speedScore * 0.2).coerceIn(0.0, 1.0)
            }
    }

    enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

    data class CircuitBreaker(
        var state: CircuitState = CircuitState.CLOSED,
        var failureCount: Int = 0,
        var lastFailureTime: Long = 0,
        val threshold: Int = 5,
        val timeout: Duration = 1.minutes
    ) {
        fun recordSuccess() {
            failureCount = 0
            state = CircuitState.CLOSED
        }

        fun recordFailure() {
            failureCount++
            lastFailureTime = System.currentTimeMillis()
            if (failureCount >= threshold) state = CircuitState.OPEN
        }

        fun canExecute(): Boolean {
            val now = System.currentTimeMillis()
            val dynamicTimeoutMs = currentTimeoutMs()
            return when (state) {
                CircuitState.CLOSED -> true
                CircuitState.OPEN -> {
                    if (now - lastFailureTime > dynamicTimeoutMs) {
                        state = CircuitState.HALF_OPEN
                        true
                    } else false
                }
                CircuitState.HALF_OPEN -> true
            }
        }

        private fun currentTimeoutMs(): Long {
            val multiplier = max(1, failureCount / threshold)
            return timeout.inWholeMilliseconds * multiplier
        }
    }

    /** Execute a tool with caching, semantic normalization, circuit breaker, and metrics. */
    suspend fun <T> executeTool(
        toolName: String,
        cacheKey: String? = null,
        cacheTTL: Duration = 5.minutes,
        timeoutMs: Long = 30_000L,
        maxRetries: Int = 2,
        baseRetryDelayMs: Long = 500L,
        execution: suspend () -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        if (cacheKey != null) {
            executionCache[cacheKey]?.let { cached ->
                if (cached.isValid()) {
                    @Suppress("UNCHECKED_CAST")
                    return@withContext Result.success(cached.result as T)
                } else {
                    executionCache.remove(cacheKey)
                }
            }
        }

        val breaker = circuitBreakers.getOrPut(toolName) { CircuitBreaker() }
        if (!breaker.canExecute()) {
            return@withContext Result.failure(
                Exception("Circuit breaker OPEN for tool: $toolName")
            )
        }

        val metrics = executionMetrics.getOrPut(toolName) { ToolMetrics() }
        val startTime = System.currentTimeMillis()
        val effectiveTimeoutMs = if (toolName in LONG_RUNNING_TOOLS) {
            maxOf(timeoutMs, LONG_RUNNING_TIMEOUT_MS)
        } else timeoutMs

        var attempt = 0
        var result: Result<T>
        var semanticFailure = false
        while (true) {
            result = try {
                val rawOutput = withTimeout(effectiveTimeoutMs) { execution() }
                @Suppress("UNCHECKED_CAST")
                val output: T = if (rawOutput is ToolExecutionResult) {
                    ToolExecutionSemantics.normalize(toolName, rawOutput) as T
                } else rawOutput

                val toolResult = output as? ToolExecutionResult
                semanticFailure = toolResult?.isError == true
                if (semanticFailure) {
                    breaker.recordFailure()
                    metrics.failureCount++
                    metrics.consecutiveFailures++
                    metrics.lastError = buildString {
                        append(toolResult?.classification ?: "TOOL_ERROR")
                        toolResult?.output?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                            append(": ").append(it.take(300))
                        }
                    }
                } else {
                    breaker.recordSuccess()
                    metrics.successCount++
                    metrics.consecutiveFailures = 0
                    metrics.lastError = null
                }
                // Semantic failures are observations, not transport exceptions: return
                // them to the model once so it can pivot instead of auto-retrying blindly.
                Result.success(output)
            } catch (e: Exception) {
                breaker.recordFailure()
                metrics.failureCount++
                metrics.consecutiveFailures++
                metrics.lastError = e.message
                if (attempt >= maxRetries) {
                    Result.failure(e)
                } else {
                    val delayMs = baseRetryDelayMs * (1L shl attempt)
                    delay(delayMs.coerceAtMost(10_000L))
                    attempt++
                    continue
                }
            }
            break
        }

        val executionTime = System.currentTimeMillis() - startTime
        metrics.totalExecutions++
        metrics.avgExecutionTime =
            (metrics.avgExecutionTime * (metrics.totalExecutions - 1) + executionTime) /
                metrics.totalExecutions
        metrics.lastExecutionTime = executionTime

        if (result.isSuccess && !semanticFailure && cacheKey != null) {
            executionCache[cacheKey] = CachedResult(
                result = result.getOrThrow() as Any,
                timestamp = System.currentTimeMillis(),
                ttl = cacheTTL
            )
        }

        result
    }

    suspend fun executeParallel(
        tasks: List<ToolTask>
    ): Map<String, Result<Any>> = coroutineScope {
        val results = ConcurrentHashMap<String, Result<Any>>()
        buildDependencyGraph(tasks)
        val executed = ConcurrentHashMap.newKeySet<String>()

        suspend fun executeTask(task: ToolTask) {
            task.dependencies.forEach { dep ->
                while (!executed.contains(dep)) delay(50)
            }

            val result = executeTool(
                toolName = task.name,
                cacheKey = task.cacheKey,
                cacheTTL = task.cacheTTL,
                execution = task.execution
            )

            results[task.name] = result
            executed.add(task.name)
        }

        tasks.map { task -> async { executeTask(task) } }.awaitAll()
        results
    }

    data class ToolTask(
        val name: String,
        val dependencies: List<String> = emptyList(),
        val cacheKey: String? = null,
        val cacheTTL: Duration = 5.minutes,
        val execution: suspend () -> Any
    )

    private fun buildDependencyGraph(tasks: List<ToolTask>): Map<String, List<String>> =
        tasks.associate { it.name to it.dependencies }

    fun getToolMetrics(toolName: String): ToolMetrics? = executionMetrics[toolName]

    fun getAllMetrics(): Map<String, ToolMetrics> = executionMetrics.toMap()

    fun clearCache(key: String? = null) {
        if (key != null) executionCache.remove(key) else executionCache.clear()
    }

    fun resetCircuitBreaker(toolName: String) {
        circuitBreakers[toolName]?.let {
            it.state = CircuitState.CLOSED
            it.failureCount = 0
        }
    }
}
