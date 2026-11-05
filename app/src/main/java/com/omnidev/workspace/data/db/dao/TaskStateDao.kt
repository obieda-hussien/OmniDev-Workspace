package com.omnidev.workspace.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.omnidev.workspace.data.db.entities.AgentTaskEntity
import com.omnidev.workspace.data.db.entities.TaskCycleEntity
import com.omnidev.workspace.data.db.entities.TaskEventEntity
import com.omnidev.workspace.data.db.entities.TaskInvocationEntity
import com.omnidev.workspace.data.db.entities.TaskPlanEntity
import com.omnidev.workspace.data.db.entities.TaskStepEntity

data class InvocationIdStatus(val invocationId: String, val status: String)
data class StatusCount(val status: String, val n: Int)

@Dao
interface TaskStateDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTask(t: AgentTaskEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTask(t: AgentTaskEntity)
    @Query("SELECT * FROM agent_tasks WHERE taskId = :id") suspend fun task(id: String): AgentTaskEntity?
    @Query("SELECT * FROM agent_tasks WHERE status IN (:statuses) ORDER BY updatedAt DESC, taskId ASC LIMIT :limit OFFSET :offset")
    suspend fun tasksByStatus(statuses: List<String>, limit: Int, offset: Int): List<AgentTaskEntity>
    @Query("SELECT * FROM agent_tasks WHERE sessionId = :sessionId ORDER BY createdAt DESC, taskId ASC LIMIT :limit OFFSET :offset")
    suspend fun tasksForSession(sessionId: Long, limit: Int, offset: Int): List<AgentTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPlan(p: TaskPlanEntity)
    @Query("SELECT * FROM agent_task_plans WHERE taskId = :taskId ORDER BY revision") suspend fun plans(taskId: String): List<TaskPlanEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertStep(s: TaskStepEntity)
    @Query("SELECT * FROM agent_task_steps WHERE taskId = :taskId ORDER BY createdAt, ordinal") suspend fun steps(taskId: String): List<TaskStepEntity>
    @Query("SELECT * FROM agent_task_steps WHERE stepId = :stepId") suspend fun step(stepId: String): TaskStepEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertCycle(c: TaskCycleEntity)
    @Query("SELECT * FROM agent_task_cycles WHERE taskId = :taskId ORDER BY seq") suspend fun cycles(taskId: String): List<TaskCycleEntity>
    @Query("SELECT * FROM agent_task_cycles WHERE cycleId = :cycleId") suspend fun cycle(cycleId: String): TaskCycleEntity?
    @Query("SELECT COUNT(*) FROM agent_task_cycles WHERE taskId = :taskId") suspend fun cycleCount(taskId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertInvocation(i: TaskInvocationEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertInvocation(i: TaskInvocationEntity)
    @Query("SELECT * FROM agent_task_invocations WHERE invocationId = :id") suspend fun invocation(id: String): TaskInvocationEntity?
    @Query("SELECT * FROM agent_task_invocations WHERE taskId = :taskId ORDER BY seq") suspend fun invocations(taskId: String): List<TaskInvocationEntity>
    @Query("SELECT * FROM agent_task_invocations WHERE taskId = :taskId ORDER BY seq LIMIT :limit OFFSET :offset")
    suspend fun invocationsPage(taskId: String, limit: Int, offset: Int): List<TaskInvocationEntity>
    @Query("SELECT invocationId, status FROM agent_task_invocations WHERE taskId = :taskId") suspend fun invocationStatuses(taskId: String): List<InvocationIdStatus>
    @Query("SELECT status, COUNT(*) AS n FROM agent_task_invocations WHERE taskId = :taskId GROUP BY status") suspend fun invocationCounts(taskId: String): List<StatusCount>
    @Query("SELECT COALESCE(MAX(seq), 0) FROM agent_task_invocations WHERE taskId = :taskId") suspend fun maxInvocationSeq(taskId: String): Long
    @Query("SELECT * FROM agent_task_invocations WHERE taskId = :taskId AND status IN ('PENDING','RUNNING','INTERRUPTED','UNKNOWN') ORDER BY seq")
    suspend fun unresolvedInvocations(taskId: String): List<TaskInvocationEntity>
    @Query("SELECT * FROM agent_task_invocations WHERE taskId = :taskId AND effect = 'SIDE_EFFECT' AND status IN ('PENDING','RUNNING','INTERRUPTED','UNKNOWN') ORDER BY seq")
    suspend fun unresolvedSideEffects(taskId: String): List<TaskInvocationEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEvent(e: TaskEventEntity): Long
    @Query("SELECT COUNT(*) FROM agent_task_events WHERE taskId = :taskId AND eventKey = :key") suspend fun eventCount(taskId: String, key: String): Int
    @Query("SELECT * FROM agent_task_events WHERE taskId = :taskId ORDER BY seq LIMIT :limit OFFSET :offset")
    suspend fun events(taskId: String, limit: Int, offset: Int): List<TaskEventEntity>
}
