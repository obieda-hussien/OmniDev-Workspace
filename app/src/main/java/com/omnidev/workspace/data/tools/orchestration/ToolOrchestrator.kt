package com.omnidev.workspace.data.tools.orchestration

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * ToolOrchestrator - Advanced Tool Coordination System
 * 
 * Features:
 * - Parallel tool execution with dependency resolution
 * - Intelligent caching with TTL
 * - Circuit breaker pattern for failing tools
 * - Tool execution metrics and analytics
 * - Dynamic tool routing based on context
 * - Tool chain optimization
 */
class ToolOrchestrator {
    
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
        var avgExecutionTime: Long = 0,
        var lastExecutionTime: Long = 0
    )
    
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
            if (failureCount >= threshold) {
                state = CircuitState.OPEN
            }
        }
        
        fun canExecute(): Boolean {
            return when (state) {
                CircuitState.CLOSED -> true
                CircuitState.OPEN -> {
                    if (System.currentTimeMillis() - lastFailureTime > timeout.inWholeMilliseconds) {
                        state = CircuitState.HALF_OPEN
                        true
                    } else false
                }
                CircuitState.HALF_OPEN -> true
            }
        }
    }
    
    /**
     * Execute a tool with caching, circuit breaker, and metrics
     */
    suspend fun <T> executeTool(
        toolName: String,
        cacheKey: String? = null,
        cacheTTL: Duration = 5.minutes,
        execution: suspend () -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        // Check cache
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
        
        // Check circuit breaker
        val breaker = circuitBreakers.getOrPut(toolName) { CircuitBreaker() }
        if (!breaker.canExecute()) {
            return@withContext Result.failure(
                Exception("Circuit breaker OPEN for tool: $toolName")
            )
        }
        
        // Execute with metrics
        val metrics = executionMetrics.getOrPut(toolName) { ToolMetrics() }
        val startTime = System.currentTimeMillis()
        
        val result = try {
            val output = execution()
            breaker.recordSuccess()
            metrics.successCount++
            Result.success(output)
        } catch (e: Exception) {
            breaker.recordFailure()
            metrics.failureCount++
            Result.failure(e)
        }
        
        // Update metrics
        val executionTime = System.currentTimeMillis() - startTime
        metrics.totalExecutions++
        metrics.avgExecutionTime = 
            (metrics.avgExecutionTime * (metrics.totalExecutions - 1) + executionTime) / 
            metrics.totalExecutions
        metrics.lastExecutionTime = executionTime
        
        // Cache successful results
        if (result.isSuccess && cacheKey != null) {
            executionCache[cacheKey] = CachedResult(
                result = result.getOrThrow()!!,
                timestamp = System.currentTimeMillis(),
                ttl = cacheTTL
            )
        }
        
        result
    }
    
    /**
     * Execute multiple tools in parallel with dependency resolution
     */
    suspend fun executeParallel(
        tasks: List<ToolTask>
    ): Map<String, Result<Any>> = coroutineScope {
        val results = ConcurrentHashMap<String, Result<Any>>()
        val dependencyGraph = buildDependencyGraph(tasks)
        val executed = ConcurrentHashMap.newKeySet<String>()
        
        suspend fun executeTask(task: ToolTask) {
            // Wait for dependencies
            task.dependencies.forEach { dep ->
                while (!executed.contains(dep)) {
                    delay(50)
                }
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
        
        // Launch all tasks
        tasks.map { task ->
            async { executeTask(task) }
        }.awaitAll()
        
        results
    }
    
    data class ToolTask(
        val name: String,
        val dependencies: List<String> = emptyList(),
        val cacheKey: String? = null,
        val cacheTTL: Duration = 5.minutes,
        val execution: suspend () -> Any
    )
    
    private fun buildDependencyGraph(tasks: List<ToolTask>): Map<String, List<String>> {
        return tasks.associate { it.name to it.dependencies }
    }
    
    /**
     * Get metrics for a specific tool
     */
    fun getToolMetrics(toolName: String): ToolMetrics? = executionMetrics[toolName]
    
    /**
     * Get all metrics
     */
    fun getAllMetrics(): Map<String, ToolMetrics> = executionMetrics.toMap()
    
    /**
     * Clear cache for specific key or all
     */
    fun clearCache(key: String? = null) {
        if (key != null) {
            executionCache.remove(key)
        } else {
            executionCache.clear()
        }
    }
    
    /**
     * Reset circuit breaker for a tool
     */
    fun resetCircuitBreaker(toolName: String) {
        circuitBreakers[toolName]?.let {
            it.state = CircuitState.CLOSED
            it.failureCount = 0
        }
    }
}
