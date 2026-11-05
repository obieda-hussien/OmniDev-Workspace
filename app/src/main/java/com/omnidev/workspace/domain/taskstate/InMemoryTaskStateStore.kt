package com.omnidev.workspace.domain.taskstate

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [TaskStateBackend] with real rollback: a failed [atomic] block restores the previous state, which
 * is what makes interrupted multi-row writes testable on the JVM. [failAfterWrites] injects a failure after N writes.
 */
class MemoryTaskStateBackend : TaskStateBackend {
    private class State {
        val tasks = LinkedHashMap<String, TaskRecord>()
        val plans = ArrayList<PlanRevisionRecord>()
        val steps = ArrayList<StepRecord>()
        val cycles = ArrayList<CycleRecord>()
        val invocations = LinkedHashMap<String, InvocationRecord>()
        val events = ArrayList<TransitionEvent>()
        val keys = HashSet<String>()
        fun copy() = State().also {
            it.tasks.putAll(tasks); it.plans.addAll(plans); it.steps.addAll(steps); it.cycles.addAll(cycles)
            it.invocations.putAll(invocations); it.events.addAll(events); it.keys.addAll(keys)
        }
    }

    private val lock = Mutex()
    private var state = State()
    /** When >= 0, the write after this many more writes throws. Counts writes inside atomic blocks. */
    @Volatile var failAfterWrites = -1

    private fun write() {
        if (failAfterWrites == 0) { failAfterWrites = -1; throw TaskStateException("simulated write failure") }
        if (failAfterWrites > 0) failAfterWrites--
    }

    private inner class Access(val s: State) : TaskStateAccess {
        override suspend fun task(id: String) = s.tasks[id]
        override suspend fun insertTask(t: TaskRecord): Boolean { if (s.tasks.containsKey(t.taskId)) return false; write(); s.tasks[t.taskId] = t; return true }
        override suspend fun updateTask(t: TaskRecord) { write(); s.tasks[t.taskId] = t }
        override suspend fun plans(taskId: String) = s.plans.filter { it.taskId == taskId }.sortedBy { it.revision }
        override suspend fun savePlan(p: PlanRevisionRecord) { write(); val i = s.plans.indexOfFirst { it.planId == p.planId }; if (i >= 0) s.plans[i] = p else s.plans += p }
        override suspend fun steps(taskId: String) = s.steps.filter { it.taskId == taskId }
        override suspend fun step(stepId: String) = s.steps.firstOrNull { it.stepId == stepId }
        override suspend fun saveStep(step: StepRecord) { write(); val i = s.steps.indexOfFirst { it.stepId == step.stepId }; if (i >= 0) s.steps[i] = step else s.steps += step }
        override suspend fun cycles(taskId: String) = s.cycles.filter { it.taskId == taskId }.sortedBy { it.seq }
        override suspend fun cycle(cycleId: String) = s.cycles.firstOrNull { it.cycleId == cycleId }
        override suspend fun saveCycle(c: CycleRecord) { write(); val i = s.cycles.indexOfFirst { it.cycleId == c.cycleId }; if (i >= 0) s.cycles[i] = c else s.cycles += c }
        override suspend fun cycleCount(taskId: String) = s.cycles.count { it.taskId == taskId }
        override suspend fun invocation(id: String) = s.invocations[id]
        override suspend fun invocations(taskId: String) = s.invocations.values.filter { it.taskId == taskId }.sortedBy { it.seq }
        override suspend fun invocationsPage(taskId: String, limit: Int, offset: Int) = invocations(taskId).drop(offset).take(limit)
        override suspend fun invocationStatuses(taskId: String) = invocations(taskId).associate { it.invocationId to it.status }
        override suspend fun invocationCounts(taskId: String) = invocations(taskId).groupingBy { it.status }.eachCount()
        override suspend fun nextInvocationSeq(taskId: String) = (invocations(taskId).maxOfOrNull { it.seq } ?: 0L) + 1
        override suspend fun insertInvocation(i: InvocationRecord): Boolean { if (s.invocations.containsKey(i.invocationId)) return false; write(); s.invocations[i.invocationId] = i; return true }
        override suspend fun updateInvocation(i: InvocationRecord) { write(); s.invocations[i.invocationId] = i }
        override suspend fun unresolvedInvocations(taskId: String) = invocations(taskId).filter {
            it.status in setOf(InvocationStatus.PENDING, InvocationStatus.RUNNING, InvocationStatus.INTERRUPTED, InvocationStatus.UNKNOWN)
        }
        override suspend fun unresolvedSideEffects(taskId: String) = invocations(taskId).filter {
            it.effect == Effect.SIDE_EFFECT && it.status in setOf(InvocationStatus.PENDING, InvocationStatus.RUNNING, InvocationStatus.INTERRUPTED, InvocationStatus.UNKNOWN)
        }
        override suspend fun appendEvent(taskId: String, entityType: String, entityId: String, from: String, to: String, reason: String, at: Long, key: String): Boolean {
            if (!s.keys.add("$taskId|$key")) return false
            write()
            s.events += TransitionEvent(s.events.size + 1L, taskId, entityType, entityId, from, to, TaskStateRules.clamp(reason, 300), at, key)
            return true
        }
        override suspend fun hasEvent(taskId: String, key: String) = "$taskId|$key" in s.keys
        override suspend fun events(taskId: String, limit: Int, offset: Int) = s.events.filter { it.taskId == taskId }.drop(offset).take(limit)
        override suspend fun tasksByStatus(statuses: Set<TaskStatus>, limit: Int, offset: Int) = s.tasks.values.filter { it.status in statuses }.sortedWith(compareByDescending<TaskRecord> { it.updatedAt }.thenBy { it.taskId }).drop(offset).take(limit)
        override suspend fun tasksForSession(sessionId: Long, limit: Int, offset: Int) = s.tasks.values.filter { it.sessionId == sessionId }.sortedWith(compareByDescending<TaskRecord> { it.createdAt }.thenBy { it.taskId }).drop(offset).take(limit)
    }

    override suspend fun <T> atomic(block: suspend TaskStateAccess.() -> T): T = lock.withLock {
        val working = state.copy()
        val result = Access(working).block()   // an exception leaves `state` untouched: full rollback
        state = working
        result
    }
}

/** Convenience store for tests and for runs without a database. */
class InMemoryTaskStateStore(val backend: MemoryTaskStateBackend = MemoryTaskStateBackend()) : TaskStateStore by TaskStateEngine(backend)
