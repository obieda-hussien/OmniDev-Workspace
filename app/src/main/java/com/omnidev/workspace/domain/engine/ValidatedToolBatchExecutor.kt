package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolExecutionResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Shared dispatch boundary: bounded reads, fail-closed batches and dependency barriers. */
object ValidatedToolBatchExecutor {
    suspend fun execute(
        calls: List<ToolCall>,
        definitions: List<ToolDefinition>,
        allowParallel: Boolean,
        authorize: (ToolCall) -> String? = { null },
        dispatch: suspend (ToolCall) -> ToolExecutionResult
    ): List<ToolExecutionResult> {
        val preflight = ToolCallPreflight(definitions).checkBatch(calls)
        if (preflight.any { it != null }) return preflight.map { it!! }
        val denied = calls.map(authorize)
        if (denied.any { it != null }) return denied.map { reason -> ToolExecutionResult(
            reason ?: "Another call was denied. No actions in this batch executed.", true,
            classification = "TOOL_POLICY_DENIED") }

        if (allowParallel && calls.none { it.name == RunToolCatalog.DISCOVER.name } &&
            ToolBatchPolicy.canRunBatchInParallel(calls)) {
            val permits = Semaphore(4)
            // Identical reads share one result only within this response. No stale cross-turn cache.
            val unique = calls.distinctBy { it.name to it.arguments }
            val results = coroutineScope {
                unique.map { call -> async { permits.withPermit { dispatch(call) } } }.awaitAll()
            }
            val byKey = unique.zip(results).associate { (call, result) -> (call.name to call.arguments) to result }
            return calls.map { byKey.getValue(it.name to it.arguments) }
        }
        var previousFailure = false
        val reads = mutableMapOf<Pair<String, Map<String, String>>, ToolExecutionResult>()
        return calls.map { call ->
            val readOnly = ToolBatchPolicy.isReadOnly(call) && call.name != RunToolCatalog.DISCOVER.name
            val key = call.name to call.arguments
            if (previousFailure && !readOnly && call.name != RunToolCatalog.DISCOVER.name) {
                ToolExecutionResult("Earlier call failed. Re-observe state and re-plan before this mutation; no action executed.",
                    true, classification = "BATCH_DEPENDENCY_BLOCKED")
            } else {
                if (!readOnly) reads.clear()
                (if (readOnly) reads[key] else null) ?: dispatch(call).also {
                    if (it.isError) previousFailure = true
                    if (readOnly) reads[key] = it
                }
            }
        }
    }
}
