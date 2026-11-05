package com.omnidev.workspace.data.repository

import androidx.room.withTransaction
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.db.dao.TaskStateDao
import com.omnidev.workspace.data.db.entities.AgentTaskEntity
import com.omnidev.workspace.data.db.entities.TaskCycleEntity
import com.omnidev.workspace.data.db.entities.TaskEventEntity
import com.omnidev.workspace.data.db.entities.TaskInvocationEntity
import com.omnidev.workspace.data.db.entities.TaskPlanEntity
import com.omnidev.workspace.data.db.entities.TaskStepEntity
import com.omnidev.workspace.domain.taskstate.CycleRecord
import com.omnidev.workspace.domain.taskstate.Effect
import com.omnidev.workspace.domain.taskstate.InvocationRecord
import com.omnidev.workspace.domain.taskstate.InvocationStatus
import com.omnidev.workspace.domain.taskstate.PlanRevisionRecord
import com.omnidev.workspace.domain.taskstate.StepRecord
import com.omnidev.workspace.domain.taskstate.StepStatus
import com.omnidev.workspace.domain.taskstate.TaskRecord
import com.omnidev.workspace.domain.taskstate.TaskStateAccess
import com.omnidev.workspace.domain.taskstate.TaskStateBackend
import com.omnidev.workspace.domain.taskstate.TaskStateEngine
import com.omnidev.workspace.domain.taskstate.TaskStateStore
import com.omnidev.workspace.domain.taskstate.TaskStatus
import com.omnidev.workspace.domain.taskstate.TransitionEvent

/** Room-backed [TaskStateBackend]: one database transaction per store operation, never held across a model or network call. */
class RoomTaskStateBackend(private val db: OmniDevDatabase) : TaskStateBackend {
    override suspend fun <T> atomic(block: suspend TaskStateAccess.() -> T): T =
        db.withTransaction { RoomAccess(db.taskStateDao()).block() }
}

/** The task store used by the app: the shared rules engine over Room. */
fun roomTaskStateStore(db: OmniDevDatabase): TaskStateStore = TaskStateEngine(RoomTaskStateBackend(db))

private class RoomAccess(private val dao: TaskStateDao) : TaskStateAccess {
    override suspend fun task(id: String) = dao.task(id)?.toRecord()
    override suspend fun insertTask(t: TaskRecord) = dao.insertTask(t.toEntity()) != -1L
    override suspend fun updateTask(t: TaskRecord) = dao.upsertTask(t.toEntity())

    override suspend fun plans(taskId: String) = dao.plans(taskId).map { PlanRevisionRecord(it.planId, it.taskId, it.revision, it.reason, it.createdAt, it.supersededAt) }
    override suspend fun savePlan(p: PlanRevisionRecord) = dao.upsertPlan(TaskPlanEntity(p.planId, p.taskId, p.revision, p.reason, p.createdAt, p.supersededAt))

    override suspend fun steps(taskId: String) = dao.steps(taskId).map { it.toRecord() }
    override suspend fun step(stepId: String) = dao.step(stepId)?.toRecord()
    override suspend fun saveStep(step: StepRecord) = dao.upsertStep(step.toEntity())

    override suspend fun cycles(taskId: String) = dao.cycles(taskId).map { it.toRecord() }
    override suspend fun cycle(cycleId: String) = dao.cycle(cycleId)?.toRecord()
    override suspend fun saveCycle(c: CycleRecord) = dao.upsertCycle(TaskCycleEntity(c.cycleId, c.taskId, c.seq, c.planRevision, c.stepId, c.startedAt, c.endedAt, c.outcome, c.summary, c.decisionsJson))
    override suspend fun cycleCount(taskId: String) = dao.cycleCount(taskId)

    override suspend fun invocation(id: String) = dao.invocation(id)?.toRecord()
    override suspend fun invocations(taskId: String) = dao.invocations(taskId).map { it.toRecord() }
    override suspend fun invocationsPage(taskId: String, limit: Int, offset: Int) = dao.invocationsPage(taskId, limit, offset).map { it.toRecord() }
    override suspend fun invocationStatuses(taskId: String) = dao.invocationStatuses(taskId).associate { it.invocationId to invocationStatus(it.status) }
    override suspend fun invocationCounts(taskId: String) = dao.invocationCounts(taskId).groupBy({ invocationStatus(it.status) }, { it.n }).mapValues { it.value.sum() }
    override suspend fun nextInvocationSeq(taskId: String) = dao.maxInvocationSeq(taskId) + 1
    override suspend fun insertInvocation(i: InvocationRecord) = dao.insertInvocation(i.toEntity()) != -1L
    override suspend fun updateInvocation(i: InvocationRecord) = dao.upsertInvocation(i.toEntity())
    override suspend fun unresolvedInvocations(taskId: String) = dao.unresolvedInvocations(taskId).map { it.toRecord() }
    override suspend fun unresolvedSideEffects(taskId: String) = dao.unresolvedSideEffects(taskId).map { it.toRecord() }

    override suspend fun appendEvent(taskId: String, entityType: String, entityId: String, from: String, to: String, reason: String, at: Long, key: String) =
        dao.insertEvent(TaskEventEntity(0, taskId, entityType, entityId, from, to, reason.take(300), at, key)) != -1L
    override suspend fun hasEvent(taskId: String, key: String) = dao.eventCount(taskId, key) > 0
    override suspend fun events(taskId: String, limit: Int, offset: Int) =
        dao.events(taskId, limit, offset).map { TransitionEvent(it.seq, it.taskId, it.entityType, it.entityId, it.fromState, it.toState, it.reason, it.at, it.eventKey) }

    override suspend fun tasksByStatus(statuses: Set<TaskStatus>, limit: Int, offset: Int) = dao.tasksByStatus(statuses.map { it.name }, limit, offset).map { it.toRecord() }
    override suspend fun tasksForSession(sessionId: Long, limit: Int, offset: Int) = dao.tasksForSession(sessionId, limit, offset).map { it.toRecord() }
}

private fun invocationStatus(raw: String) = runCatching { InvocationStatus.valueOf(raw) }.getOrDefault(InvocationStatus.UNKNOWN)

private fun TaskRecord.toEntity() = AgentTaskEntity(taskId, sessionId, originalRequest, normalizedObjective, parentTaskId, workerId, constraintsJson,
    status.name, currentCycle, currentStepId, planRevision, createdAt, updatedAt, heartbeatAt, finishStatus, finishSummary, completionDigest)

private fun AgentTaskEntity.toRecord() = TaskRecord(taskId, sessionId, originalRequest, normalizedObjective, parentTaskId, workerId, constraintsJson,
    runCatching { TaskStatus.valueOf(status) }.getOrDefault(TaskStatus.INTERRUPTED), currentCycle, currentStepId, planRevision, createdAt, updatedAt,
    heartbeatAt, finishStatus, finishSummary, completionDigest)

private fun StepRecord.toEntity() = TaskStepEntity(stepId, planId, taskId, key, ordinal, description, dependsOnKeys.joinToString(","), expectedOutput,
    completionCondition, status.name, statusReason, evidenceInvocationIds.joinToString("\n"), nonToolJustification, createdAt, updatedAt)

private fun TaskStepEntity.toRecord() = StepRecord(stepId, planId, taskId, stepKey, ordinal, description, dependsOn.split(',').filter { it.isNotBlank() },
    expectedOutput, completionCondition, runCatching { StepStatus.valueOf(status) }.getOrDefault(StepStatus.INTERRUPTED), statusReason,
    evidenceIds.split('\n').filter { it.isNotBlank() }, nonToolJustification, createdAt, updatedAt)

private fun TaskCycleEntity.toRecord() = CycleRecord(cycleId, taskId, seq, planRevision, stepId, startedAt, endedAt, outcome, summary, decisionsJson)

private fun InvocationRecord.toEntity() = TaskInvocationEntity(invocationId, callId, taskId, cycleId, stepId, toolName, schemaId, inputSanitized, inputHash,
    effect.name, idempotencyKey, attempt, retryOfInvocationId, status.name, requestedAt, startedAt, completedAt, resultSummary, resultHash,
    resultTruncated, resultChars, isError, errorDetail, classification, verification, seq)

private fun TaskInvocationEntity.toRecord() = InvocationRecord(invocationId, callId, taskId, cycleId, stepId, toolName, schemaId, inputSanitized, inputHash,
    runCatching { Effect.valueOf(effect) }.getOrDefault(Effect.SIDE_EFFECT), idempotencyKey, attempt, retryOf, invocationStatus(status), requestedAt,
    startedAt, completedAt, resultSummary, resultHash, resultTruncated, resultChars, isError, errorDetail, classification, verification, seq)
