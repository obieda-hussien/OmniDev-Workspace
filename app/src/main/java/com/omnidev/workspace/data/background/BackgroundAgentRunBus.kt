package com.omnidev.workspace.data.background

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-process live projection of persistent background runs.
 *
 * The service owns execution; Activities/ViewModels only observe this bus. If the process dies,
 * [BackgroundAgentRunStore] remains authoritative and the service republishes recovered state.
 */
object BackgroundAgentRunBus {
    data class Snapshot(
        val runId: String,
        val sessionId: Long,
        val status: BackgroundAgentRunStore.Status,
        val progressText: String,
        val partialOutput: String = "",
        val updatedAtMs: Long = System.currentTimeMillis(),
        val error: String? = null
    ) {
        val isActive: Boolean get() = status == BackgroundAgentRunStore.Status.QUEUED ||
            status == BackgroundAgentRunStore.Status.RUNNING ||
            status == BackgroundAgentRunStore.Status.RECOVERING
    }

    private val _runs = MutableStateFlow<Map<String, Snapshot>>(emptyMap())
    val runs: StateFlow<Map<String, Snapshot>> = _runs.asStateFlow()

    fun publish(run: BackgroundAgentRunStore.Run) {
        _runs.update { current ->
            current + (run.id to Snapshot(
                runId = run.id,
                sessionId = run.sessionId,
                status = run.status,
                progressText = run.progressText,
                partialOutput = run.partialOutput,
                updatedAtMs = run.updatedAtMs,
                error = run.lastError
            ))
        }
    }

    fun publishAll(runs: Iterable<BackgroundAgentRunStore.Run>) {
        runs.forEach(::publish)
    }

    fun activeForSession(sessionId: Long): Snapshot? =
        _runs.value.values
            .filter { it.sessionId == sessionId && it.isActive }
            .maxByOrNull { it.updatedAtMs }

    fun remove(runId: String) {
        _runs.update { it - runId }
    }
}
