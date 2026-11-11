package com.omnidev.workspace.domain.teamstate

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.domain.engine.AgentEvent
import com.omnidev.workspace.domain.engine.SwarmTask
import com.omnidev.workspace.domain.engine.completion.CompletionReport
import com.omnidev.workspace.domain.engine.teamcompletion.*
import java.util.UUID

/** Per-coordinator lease, never the durable source of truth. All writes validate the exact owner. */
internal class DurableTeamRun private constructor(private val store: TeamStateEngine, val id: String,
    private val owner: String, val startingRecord: TeamRecord) {
    companion object {
        suspend fun open(store: TeamStateEngine, id: String, sessionId: Long?, objective: String,
            scopePath: String, resumed: Boolean): DurableTeamRun {
            val owner = UUID.randomUUID().toString()
            if (resumed) require(store.load(id)?.scopePath == scopePath) { "Resume must retain the saved execution scope" }
            val record = if (resumed) store.claim(id, sessionId, owner) else store.create(id, sessionId, objective, scopePath, owner)
            return DurableTeamRun(store, id, owner, record)
        }
    }
    suspend fun heartbeat() = store.heartbeat(id, owner)
    suspend fun release() = store.release(id, owner)
    suspend fun plan(revision: Long) = store.load(id)?.plans?.singleOrNull { it.revision == revision }
    suspend fun savePlan(revision: Long, objective: String, tasks: List<SwarmTask>) = store.savePlan(id, owner, revision, objective, tasks)
    suspend fun reserve(tokens: Int) = store.reserve(id, owner, tokens)
    suspend fun previousAttempt(revision: Long, taskId: String): Int = store.load(id)?.attempts.orEmpty()
        .filter { it.assignment.revision == revision && it.assignment.task.id == taskId }.maxOfOrNull { it.assignment.attempt } ?: 0
    suspend fun begin(a: WorkerAssignment, tokens: Int) = store.begin(id, owner, a, tokens)
    suspend fun beforeTool(a: WorkerAssignment, call: ToolCall) = store.beforeTool(id, owner, a, call)
    suspend fun observed(a: WorkerAssignment, event: AgentEvent.ToolResult) = store.observed(id, owner, a, event)
    suspend fun checkpoint(a: WorkerAssignment, observer: WorkerCompletionObserver, report: CompletionReport?,
        output: String, receipt: WorkerReceipt, error: String?) = store.finishWorker(id, owner, a, observer, report, output, receipt, error)
    suspend fun restore(protocol: TeamCompletionProtocol): Map<String, String> {
        val record = requireNotNull(store.load(id))
        val remaining = record.currentAttempts.mapNotNull { it.checkpoint }.toMutableList()
        val outputs = linkedMapOf<String, String>()
        repeat(record.plan?.tasks?.size ?: 0) {
            remaining.toList().forEach { c ->
                if (protocol.restore(c)) { outputs[c.assignment.task.id] = c.output; remaining.remove(c) }
            }
        }
        check(remaining.isEmpty()) { "Saved receipt no longer matches its P0/P5 verification contract" }
        return outputs
    }
    suspend fun finish(report: TeamCompletionReport) = store.finish(id, owner, report)
}

/** Runtime-known failures pause without speculative model correction. P7/P9 may advise; they cannot override this. */
internal enum class TeamFailureCategory { DNS, RATE_LIMIT, AUTHENTICATION, CONTEXT, SCHEMA, CHECKOUT, BACKEND, PERMISSION, UNKNOWN }
internal object TeamFailurePolicy {
    fun classify(message: String): TeamFailureCategory {
        val s = message.lowercase()
        return when {
            listOf("unable to resolve host", "unknownhost", "no address associated", "name or service not known").any(s::contains) -> TeamFailureCategory.DNS
            listOf("429", "rate limit", "resource_exhausted").any(s::contains) -> TeamFailureCategory.RATE_LIMIT
            listOf("401", "authentication", "api key", "unauthorized").any(s::contains) -> TeamFailureCategory.AUTHENTICATION
            listOf("context length", "context window", "too many tokens").any(s::contains) -> TeamFailureCategory.CONTEXT
            listOf("invalid_tool", "tool schema", "invalid argument").any(s::contains) -> TeamFailureCategory.SCHEMA
            s.contains("not a git repository") -> TeamFailureCategory.CHECKOUT
            listOf("permission", "tier_denied", "user_action_required", "confirmation_required").any(s::contains) -> TeamFailureCategory.PERMISSION
            listOf("backend", "unavailable", "timeout", "connection").any(s::contains) -> TeamFailureCategory.BACKEND
            else -> TeamFailureCategory.UNKNOWN
        }
    }
    fun pauseWithoutWorkerRetry(error: String?) = error != null && classify(error) in setOf(
        TeamFailureCategory.DNS, TeamFailureCategory.RATE_LIMIT, TeamFailureCategory.AUTHENTICATION,
        TeamFailureCategory.CONTEXT, TeamFailureCategory.PERMISSION, TeamFailureCategory.BACKEND)
}
