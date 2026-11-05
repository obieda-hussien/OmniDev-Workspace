package com.omnidev.workspace.domain.taskstate

/** Primitive persistence operations. All business rules live in [TaskStateEngine], never in a backend. */
interface TaskStateAccess {
    suspend fun task(id: String): TaskRecord?
    /** false when a task with this id already exists; the stored original request is never replaced. */
    suspend fun insertTask(t: TaskRecord): Boolean
    suspend fun updateTask(t: TaskRecord)

    suspend fun plans(taskId: String): List<PlanRevisionRecord>
    suspend fun savePlan(p: PlanRevisionRecord)

    suspend fun steps(taskId: String): List<StepRecord>
    suspend fun step(stepId: String): StepRecord?
    suspend fun saveStep(step: StepRecord)

    suspend fun cycles(taskId: String): List<CycleRecord>
    suspend fun cycle(cycleId: String): CycleRecord?
    suspend fun saveCycle(c: CycleRecord)
    suspend fun cycleCount(taskId: String): Int

    suspend fun invocation(id: String): InvocationRecord?
    suspend fun invocations(taskId: String): List<InvocationRecord>
    suspend fun invocationsPage(taskId: String, limit: Int, offset: Int): List<InvocationRecord>
    suspend fun invocationStatuses(taskId: String): Map<String, InvocationStatus>
    suspend fun invocationCounts(taskId: String): Map<InvocationStatus, Int>
    suspend fun nextInvocationSeq(taskId: String): Long
    suspend fun insertInvocation(i: InvocationRecord): Boolean
    suspend fun updateInvocation(i: InvocationRecord)
    suspend fun unresolvedSideEffects(taskId: String): List<InvocationRecord>

    /** Appends a transition event once per (taskId, key). false when the key was already used. */
    suspend fun appendEvent(taskId: String, entityType: String, entityId: String, from: String, to: String, reason: String, at: Long, key: String): Boolean
    suspend fun hasEvent(taskId: String, key: String): Boolean
    suspend fun events(taskId: String, limit: Int, offset: Int): List<TransitionEvent>

    suspend fun tasksByStatus(statuses: Set<TaskStatus>, limit: Int, offset: Int): List<TaskRecord>
    suspend fun tasksForSession(sessionId: Long, limit: Int, offset: Int): List<TaskRecord>
}

interface TaskStateBackend {
    /** All reads and writes of [block] succeed or none do. Never hold it open across a network or model call. */
    suspend fun <T> atomic(block: suspend TaskStateAccess.() -> T): T
}
