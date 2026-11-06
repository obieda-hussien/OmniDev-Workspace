package com.omnidev.workspace.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Persistent agent task (one P0 run). originalRequest is written once and never updated. */
@Entity(
    tableName = "agent_tasks",
    indices = [Index(name = "index_agent_tasks_sessionId", value = ["sessionId"]), Index(name = "index_agent_tasks_status", value = ["status"])]
)
data class AgentTaskEntity(
    @PrimaryKey val taskId: String,
    val sessionId: Long?,
    val originalRequest: String,
    val normalizedObjective: String,
    val parentTaskId: String?,
    val workerId: String?,
    val constraintsJson: String,
    val status: String,
    val currentCycle: Int,
    val currentStepId: String?,
    val planRevision: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val heartbeatAt: Long,
    val finishStatus: String?,
    val finishSummary: String,
    val completionDigest: String
)

@Entity(
    tableName = "agent_task_plans",
    indices = [Index(name = "index_agent_task_plans_taskId_revision", value = ["taskId", "revision"], unique = true)]
)
data class TaskPlanEntity(
    @PrimaryKey val planId: String,
    val taskId: String,
    val revision: Int,
    val reason: String,
    val createdAt: Long,
    val supersededAt: Long?,
    @ColumnInfo(defaultValue = "''") val metadataJson: String = ""
)

@Entity(
    tableName = "agent_task_steps",
    indices = [Index(name = "index_agent_task_steps_taskId", value = ["taskId"]), Index(name = "index_agent_task_steps_planId", value = ["planId"])]
)
data class TaskStepEntity(
    @PrimaryKey val stepId: String,
    val planId: String,
    val taskId: String,
    val stepKey: String,
    val ordinal: Int,
    val description: String,
    val dependsOn: String,
    val expectedOutput: String,
    val completionCondition: String,
    val status: String,
    val statusReason: String,
    val evidenceIds: String,
    val nonToolJustification: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "agent_task_cycles",
    indices = [Index(name = "index_agent_task_cycles_taskId", value = ["taskId"])]
)
data class TaskCycleEntity(
    @PrimaryKey val cycleId: String,
    val taskId: String,
    val seq: Int,
    val planRevision: Int,
    val stepId: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val outcome: String,
    val summary: String,
    val decisionsJson: String
)

/** One tool invocation: requested, marked running before it executes, then its bounded redacted result. */
@Entity(
    tableName = "agent_task_invocations",
    indices = [
        Index(name = "index_agent_task_invocations_taskId_seq", value = ["taskId", "seq"]),
        Index(name = "index_agent_task_invocations_taskId_status", value = ["taskId", "status"])
    ]
)
data class TaskInvocationEntity(
    @PrimaryKey val invocationId: String,
    val callId: String,
    val taskId: String,
    val cycleId: String,
    val stepId: String?,
    val toolName: String,
    val schemaId: String,
    val inputSanitized: String,
    val inputHash: String,
    val effect: String,
    val idempotencyKey: String,
    val attempt: Int,
    val retryOf: String?,
    val status: String,
    val requestedAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
    val resultSummary: String,
    val resultHash: String,
    val resultTruncated: Boolean,
    val resultChars: Int,
    val isError: Boolean,
    val errorDetail: String,
    val classification: String,
    val verification: String,
    val seq: Long
)

@Entity(
    tableName = "agent_task_events",
    indices = [Index(name = "index_agent_task_events_taskId_eventKey", value = ["taskId", "eventKey"], unique = true)]
)
data class TaskEventEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val taskId: String,
    val entityType: String,
    val entityId: String,
    val fromState: String,
    val toState: String,
    val reason: String,
    val at: Long,
    val eventKey: String
)
