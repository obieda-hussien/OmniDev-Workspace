package com.omnidev.workspace.domain.teamstate

import com.omnidev.workspace.domain.engine.SwarmTask
import com.omnidev.workspace.domain.engine.completion.CompletionReport
import com.omnidev.workspace.domain.engine.teamcompletion.*
import com.omnidev.workspace.domain.taskstate.TaskStatus
import kotlinx.serialization.Serializable

/** A bounded aggregate: one small DAG, its immutable revisions, and attempt-specific P0/P5 checkpoints. */
@Serializable
internal data class WorkerCheckpoint(val assignment: WorkerAssignment, val observations: WorkerObservationSnapshot,
    val report: CompletionReport?, val output: String, val ledgerDigest: String, val outputDigest: String)

@Serializable
internal data class TeamOperation(val callId: String, val tool: String, val readOnly: Boolean,
    val returned: Boolean = false, val succeeded: Boolean = false, val notExecuted: Boolean = false,
    val reconciliation: String = "", val classification: String = "")

@Serializable
internal data class TeamWorkerAttempt(val assignment: WorkerAssignment, val status: TaskStatus = TaskStatus.RUNNING,
    val stage: String = "STARTING", val operations: List<TeamOperation> = emptyList(),
    val checkpoint: WorkerCheckpoint? = null, val failure: String = "", val intervention: Boolean = false)

@Serializable
internal data class TeamPlan(val revision: Long, val objective: String, val tasks: List<SwarmTask>, val reason: String,
    val carriedReceipts: Map<String, Long> = emptyMap())

@Serializable
internal data class TeamRecord(val id: String, val sessionId: Long?, val originalObjective: String,
    val scopePath: String, val objective: String = originalObjective, val revision: Long = 0,
    val status: TaskStatus = TaskStatus.CREATED, val plans: List<TeamPlan> = emptyList(),
    val attempts: List<TeamWorkerAttempt> = emptyList(), val owner: String? = null, val leaseUntil: Long = 0,
    val generation: Long = 0, val reservedTokens: Int = 0, val createdAt: Long, val updatedAt: Long = createdAt,
    val finalDigest: String = "") {
    val plan: TeamPlan? get() = plans.lastOrNull()
    val currentAttempts: List<TeamWorkerAttempt> get() = attempts.filter { it.assignment.revision == revision }
}

data class TeamWorkerRecovery(val id: String, val status: String, val attempt: Int, val dependencies: List<String>,
    val lastOperation: String?, val blocker: String?, val unresolvedCalls: List<String>,
    val attemptRunId: String? = null, val reconcilableCalls: List<String> = emptyList())
data class TeamRecoverySummary(val id: String, val sessionId: Long?, val originalObjective: String,
    val objective: String, val revision: Long, val status: String, val workers: List<TeamWorkerRecovery>,
    val canResume: Boolean, val blockers: List<String>, val scopePath: String)

/** Storage owns atomicity. Non-suspending transforms cannot hold a database transaction across a tool/model call. */
internal interface TeamStateBackend {
    suspend fun read(id: String): TeamRecord?
    suspend fun change(id: String, transform: (TeamRecord?) -> TeamRecord): TeamRecord
    suspend fun forSession(sessionId: Long, limit: Int): List<TeamRecord>
}
