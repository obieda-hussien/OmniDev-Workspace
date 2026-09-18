package com.omnidev.workspace.data.tools.orchestration

import com.omnidev.workspace.data.tools.ToolExecutionResult
import com.omnidev.workspace.data.tools.ToolExecutionSemantics
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * ToolOrchestrator - Advanced Tool Coordination System.
 *
 * Transport exceptions and semantic failures deliberately use different circuit
 * behavior. A transport circuit may block an unhealthy tool backend entirely.
 * A semantic failure (wrong domain, permission denied, unsupported command, etc.)
 * must be returned to the model so it can change strategy; it must NOT disable an
 * otherwise healthy multi-purpose tool such as agent_runtime.
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
        private const val PERSISTENT_SEMANTIC_REPEAT_THRESHOLD = 2
    }

    private val executionCache = ConcurrentHashMap<String, CachedResult>()
    /** Circuit for transport/execution exceptions only. */
    private val circuitBreakers = ConcurrentHashMap<String, CircuitBreaker>()
    /** Counts repeated persistent semantic classifications without globally blocking a tool. */
    private val semanticFailureCounts = ConcurrentHashMap<String, AtomicInteger>()
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

    /** Execute a tool with caching, semantic normalization, circuit breakers, and metrics. */
    suspend fun <T> executeTool(
        toolName: String,
        cacheKey: String? = null,
        cacheTTL: Duration = 5.minutes,
        timeoutMs: Long = 30_000L,
        maxRetries: Int = 2,
        baseRetryDelayMs: Long = 500L,
        retrySafe: Boolean = false,
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
                Exception("Transport circuit breaker OPEN for tool: $toolName")
            )
        }

        val metrics = executionMetrics.getOrPut(toolName) { ToolMetrics() }
        val startTime = System.currentTimeMillis()
        val effectiveTimeoutMs = if (toolName in LONG_RUNNING_TOOLS) {
            maxOf(timeoutMs, LONG_RUNNING_TIMEOUT_MS)
        } else timeoutMs

        val effectiveMaxRetries = if (retrySafe) maxRetries.coerceAtLeast(0) else 0
        var attempt = 0
        var result: Result<T>
        var semanticFailure = false
        while (true) {
            result = try {
                val rawOutput = withTimeout(effectiveTimeoutMs) { execution() }
                @Suppress("UNCHECKED_CAST")
                var output: T = if (rawOutput is ToolExecutionResult) {
                    ToolExecutionSemantics.normalize(toolName, rawOutput) as T
                } else rawOutput

                var toolResult = output as? ToolExecutionResult
                semanticFailure = toolResult?.isError == true
                if (semanticFailure) {
                    metrics.failureCount++
                    metrics.consecutiveFailures++
                    metrics.lastError = buildString {
                        append(toolResult?.classification ?: "TOOL_ERROR")
                        toolResult?.output?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                            append(": ").append(it.take(300))
                        }
                    }

                    if (toolResult != null && ToolExecutionSemantics.isPersistentFailure(toolResult)) {
                        val key = "$toolName:${toolResult.classification ?: "PERSISTENT"}"
                        val repeated = semanticFailureCounts
                            .getOrPut(key) { AtomicInteger(0) }
                            .incrementAndGet()
                        if (repeated >= PERSISTENT_SEMANTIC_REPEAT_THRESHOLD) {
                            val warning =
                                "[semantic-circuit] repeated=$repeated class=${toolResult.classification} " +
                                    "— DO NOT retry the same backend strategy; change execution domain/capability or report the blocker."
                            val decorated = toolResult.copy(
                                output = insertAfterTelemetry(toolResult.output, warning),
                                retryable = false,
                                persistentFailure = true
                            )
                            toolResult = decorated
                            @Suppress("UNCHECKED_CAST")
                            run { output = decorated as T }
                        }
                    }
                    // Semantic failure proves the transport itself worked, so do not
                    // poison/open the broad tool transport circuit.
                    breaker.recordSuccess()
                } else {
                    breaker.recordSuccess()
                    metrics.successCount++
                    metrics.consecutiveFailures = 0
                    metrics.lastError = null
                    clearSemanticFailureCounts(toolName)
                }

                Result.success(output)
            } catch (timeout: TimeoutCancellationException) {
                // The orchestrator's bounded tool timeout is an execution failure, not a user
                // cancellation. Retry it only when this call was explicitly classified retry-safe.
                breaker.recordFailure()
                metrics.failureCount++
                metrics.consecutiveFailures++
                metrics.lastError = "Tool timeout after ${effectiveTimeoutMs}ms"
                if (attempt >= effectiveMaxRetries) {
                    Result.failure(timeout)
                } else {
                    val delayMs = baseRetryDelayMs * (1L shl attempt)
                    delay(delayMs.coerceAtMost(10_000L))
                    attempt++
                    continue
                }
            } catch (cancelled: CancellationException) {
                // User/session cancellation is control flow. Never convert it to a tool failure,
                // never consume circuit-breaker budget, and never retry it.
                throw cancelled
            } catch (e: Exception) {
                // Only real transport/execution exceptions consume the broad retry/circuit budget.
                breaker.recordFailure()
                metrics.failureCount++
                metrics.consecutiveFailures++
                metrics.lastError = e.message
                if (attempt >= effectiveMaxRetries) {
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

    private fun insertAfterTelemetry(output: String, message: String): String {
        val newline = output.indexOf('\n')
        return if (newline < 0) "$output\n$message"
        else output.substring(0, newline + 1) + message + "\n" + output.substring(newline + 1)
    }

    private fun clearSemanticFailureCounts(toolName: String) {
        val prefix = "$toolName:"
        semanticFailureCounts.keys.removeAll { it.startsWith(prefix) }
    }

    suspend fun executeParallel(
        tasks: List<ToolTask>
    ): Map<String, Result<Any>> = coroutineScope {
        require(tasks.map { it.name }.distinct().size == tasks.size) {
            "ToolTask names must be unique inside executeParallel"
        }

        val taskNames = tasks.mapTo(linkedSetOf()) { it.name }
        val results = LinkedHashMap<String, Result<Any>>()
        val pending = tasks.associateByTo(LinkedHashMap()) { it.name }

        // Missing dependencies can never become ready. Fail them deterministically instead of
        // polling forever.
        pending.values.toList().forEach { task ->
            val missing = task.dependencies.filterNot(taskNames::contains)
            if (missing.isNotEmpty()) {
                results[task.name] = Result.failure(
                    IllegalStateException(
                        "Skipped tool task '${task.name}': missing dependencies ${missing.joinToString()}"
                    )
                )
                pending.remove(task.name)
            }
        }

        fun dependencySucceeded(name: String): Boolean {
            val result = results[name] ?: return false
            if (result.isFailure) return false
            val value = result.getOrNull()
            return value !is ToolExecutionResult || !value.isError
        }

        while (pending.isNotEmpty()) {
            // A task whose completed dependency failed must be skipped, not executed.
            var skippedAny = false
            pending.values.toList().forEach { task ->
                val failed = task.dependencies.firstOrNull { dep ->
                    dep in results && !dependencySucceeded(dep)
                }
                if (failed != null) {
                    results[task.name] = Result.failure(
                        IllegalStateException(
                            "Skipped tool task '${task.name}': dependency '$failed' failed"
                        )
                    )
                    pending.remove(task.name)
                    skippedAny = true
                }
            }
            if (pending.isEmpty()) break

            val ready = pending.values.filter { task ->
                task.dependencies.all { dep -> dep in results && dependencySucceeded(dep) }
            }

            if (ready.isEmpty()) {
                // Remaining nodes depend on one another and no frontier can advance: cycle.
                val blocked = pending.keys.toList()
                blocked.forEach { name ->
                    results[name] = Result.failure(
                        IllegalStateException(
                            "Skipped tool task '$name': cyclic or unresolved dependency graph"
                        )
                    )
                    pending.remove(name)
                }
                break
            }

            val waveResults = ready.map { task ->
                async {
                    task.name to executeTool(
                        toolName = task.name,
                        cacheKey = task.cacheKey,
                        cacheTTL = task.cacheTTL,
                        retrySafe = task.retrySafe,
                        execution = task.execution
                    )
                }
            }.awaitAll()

            waveResults.forEach { (name, result) ->
                results[name] = result
                pending.remove(name)
            }

            // Keeps the loop obviously progressive to future maintainers/static analyzers.
            @Suppress("UNUSED_VARIABLE")
            val progressMade = skippedAny || waveResults.isNotEmpty()
        }

        results
    }

    data class ToolTask(
        val name: String,
        val dependencies: List<String> = emptyList(),
        val cacheKey: String? = null,
        val cacheTTL: Duration = 5.minutes,
        val retrySafe: Boolean = false,
        val execution: suspend () -> Any
    )

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
        clearSemanticFailureCounts(toolName)
    }
}
