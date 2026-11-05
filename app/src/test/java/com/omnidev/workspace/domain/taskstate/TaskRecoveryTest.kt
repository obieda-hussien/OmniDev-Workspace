package com.omnidev.workspace.domain.taskstate

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.model.ToolCallResult
import com.omnidev.workspace.domain.engine.RunEvidenceLedger
import com.omnidev.workspace.domain.engine.SensitiveObservationRedactor
import com.omnidev.workspace.domain.engine.completion.FinalizationPolicy
import com.omnidev.workspace.domain.engine.completion.FinishGate
import com.omnidev.workspace.domain.engine.completion.FinishTool
import com.omnidev.workspace.domain.engine.completion.GateResult
import com.omnidev.workspace.domain.engine.completion.RunIdentity
import com.omnidev.workspace.domain.engine.completion.RunState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.yield
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Simulated process death at every boundary of a run. "Dying" = dropping the recorder without any further call. */
class TaskRecoveryTest {
    private val backend = MemoryTaskStateBackend()
    private val store = InMemoryTaskStateStore(backend)
    private var clock = 1_000L
    private fun tick() = ++clock
    private val effect: (ToolCall) -> Effect = { if (it.name.startsWith("read")) Effect.READ_ONLY else Effect.SIDE_EFFECT }

    private fun recorder(taskId: String = "t1", resumed: Boolean = false, acceptance: List<String> = emptyList()) =
        TaskRunRecorder(store, taskId, 7L, "edit Foo.kt and verify it", null, null, acceptance, resumed, effect, SensitiveObservationRedactor::redact, ::tick)

    private fun read(id: String, path: String = "/p/Foo.kt") = ToolCall(id, "read_file", mapOf("path" to path))
    private fun patch(id: String, path: String = "/p/Foo.kt") = ToolCall(id, "patch_file_content", mapOf("path" to path, "content" to "x"))

    private suspend fun started(r: TaskRunRecorder): TaskRunRecorder { r.start("edit Foo.kt"); r.beginTurn(); return r }
    private suspend fun status(call: String, cycle: String = "t1:c1") = store.snapshot("t1")!!.invocations.first { it.invocationId == "t1/$cycle/$call" }.status

    @Test fun `1 dying before anything was flushed leaves no invented task`() = runBlocking {
        val r = recorder(); r.start("edit Foo.kt"); r.beginTurn()          // queued only
        assertNull(store.task("t1"))
        assertNull(TaskRecovery(store, ::tick).recover("t1"))
    }

    @Test fun `2 dying after the request was recorded but before it ran cancels it and replays nothing`() = runBlocking {
        val r = started(recorder()); r.requested(listOf(patch("c1")))
        assertEquals(InvocationStatus.PENDING, status("c1"))
        val out = TaskRecovery(store, ::tick).recover("t1")!!
        assertEquals(InvocationStatus.CANCELLED, status("c1"))
        assertEquals(RecoveryDecision.SAFE_TO_RESUME, out.plan.decision)
        assertEquals(TaskStatus.INTERRUPTED, store.task("t1")!!.status)
    }

    @Test fun `3 and 4 dying while a side effect ran or right after it acted is UNKNOWN and needs reconciliation`() = runBlocking {
        val r = started(recorder()); r.requested(listOf(patch("c1"), read("c2")))
        assertEquals(RunGate.Proceed, r.gateRunning(patch("c1"))); assertEquals(RunGate.Proceed, r.gateRunning(read("c2")))
        // the tool may have modified the file here; the result is never stored
        val out = TaskRecovery(store, ::tick).recover("t1")!!
        assertEquals(InvocationStatus.UNKNOWN, status("c1"))             // not failed, not succeeded
        assertEquals(InvocationStatus.INTERRUPTED, status("c2"))         // a read can safely be repeated
        assertEquals(RecoveryDecision.REQUIRES_RECONCILIATION, out.plan.decision)
        assertEquals(listOf("t1/t1:c1/c1"), out.plan.needsReconciliation)
    }

    @Test fun `a later read cannot reconcile an unresolved side effect and exact reconciliation is required`() = runBlocking {
        val r = started(recorder()); r.requested(listOf(patch("c1"))); r.gateRunning(patch("c1"))
        TaskRecovery(store, ::tick).recover("t1")
        val originalId = "t1/t1:c1/c1"
        val resumed = recorder(resumed = true); resumed.start("edit Foo.kt"); resumed.beginTurn()
        resumed.requested(listOf(read("c10"))); assertEquals(RunGate.Proceed, resumed.gateRunning(read("c10")))
        resumed.completed(read("c10"), "the file currently contains the expected text", false, null)

        resumed.requested(listOf(patch("c11")))
        val blocked = resumed.gateRunning(patch("c11"))
        assertTrue(blocked is RunGate.Blocked)
        assertEquals("PRIOR_MUTATION_OUTCOME_UNKNOWN", (blocked as RunGate.Blocked).classification)
        assertEquals(InvocationStatus.UNKNOWN, store.snapshot("t1")!!.invocations.first { it.invocationId == originalId }.status)
        resumed.completed(patch("c11"), blocked.output, true, blocked.classification)
        assertEquals(InvocationStatus.CANCELLED, status("c11", "t1:c2"))

        val verification = "Read-only inspection of /p/Foo.kt confirmed the original patch did not take effect."
        val reconciled = TaskRecovery(store, ::tick).reconcile("t1", originalId, InvocationStatus.CANCELLED, verification)
        assertEquals(TransitionResult.Applied, reconciled)
        val reconciledInvocation = store.snapshot("t1")!!.invocations.first { it.invocationId == originalId }
        assertEquals(InvocationStatus.CANCELLED, reconciledInvocation.status)
        assertEquals(verification, reconciledInvocation.verification)
        val restored = TaskRunRecorder.seedFrom(store.snapshot("t1")!!).first { it.callId == "c1" }
        assertEquals(RunEvidenceLedger.Outcome.SKIPPED, restored.outcome)
        assertEquals(verification, restored.evidence)

        resumed.requested(listOf(patch("c12")))
        assertEquals(RunGate.Proceed, resumed.gateRunning(patch("c12")))
    }

    @Test fun `a reconciled successful side effect restores its verification into P0 evidence`() = runBlocking {
        val r = started(recorder())
        r.requested(listOf(patch("c1"))); assertEquals(RunGate.Proceed, r.gateRunning(patch("c1")))
        TaskRecovery(store, ::tick).recover("t1")
        val invocationId = "t1/t1:c1/c1"
        val verification = "The provider confirmed the created resource by its exact ID."
        assertEquals(TransitionResult.Applied,
            TaskRecovery(store, ::tick).reconcile("t1", invocationId, InvocationStatus.SUCCEEDED, verification))
        val restored = TaskRunRecorder.seedFrom(store.snapshot("t1")!!).single()
        assertEquals(RunEvidenceLedger.Outcome.OK, restored.outcome)
        assertEquals(verification, restored.evidence)
    }

    @Test fun `startup recovery completes before a new run can be persisted`() = runBlocking {
        val previous = started(recorder("previous"))
        previous.requested(emptyList())
        assertEquals(TaskStatus.RUNNING, store.task("previous")!!.status)

        val barrier = TaskRecoveryBarrier()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val releaseSweep = kotlinx.coroutines.CompletableDeferred<Unit>()
        assertTrue(barrier.startOnce(scope, recovery = {
            releaseSweep.await()
            TaskRecovery(store, ::tick).recoverStale(staleAfterMs = 0L, liveTaskIds = emptySet())
        }, onFailure = {}))
        var duplicateRuns = 0
        assertFalse(barrier.startOnce(scope, recovery = { duplicateRuns++ }, onFailure = {}))

        val current = TaskRunRecorder(store, "current", 7L, "new task", null, null, emptyList(), false, effect,
            SensitiveObservationRedactor::redact, ::tick, awaitStartupRecovery = { barrier.awaitReady() })
        current.start("new task"); current.beginTurn()
        val persist = async(Dispatchers.Unconfined) { current.requested(emptyList()) }
        yield()
        assertNull(store.task("current")) // waiting behind the recovery barrier
        releaseSweep.complete(Unit)
        persist.await()
        assertEquals(TaskStatus.INTERRUPTED, store.task("previous")!!.status)
        assertEquals(TaskStatus.RUNNING, store.task("current")!!.status)
        assertEquals(0, duplicateRuns)
        scope.cancel()
    }

    @Test fun `startup recovery failure remains visible and blocks new mutations`() = runBlocking {
        val barrier = TaskRecoveryBarrier()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var failure: Throwable? = null
        assertTrue(barrier.startOnce(scope, recovery = {
            throw IllegalStateException("database unavailable during recovery")
        }, onFailure = { failure = it }))
        val current = TaskRunRecorder(store, "failed-startup", 7L, "mutation", null, null, emptyList(), false, effect,
            SensitiveObservationRedactor::redact, ::tick, awaitStartupRecovery = { barrier.awaitReady() })
        current.start("mutation"); current.beginTurn(); current.requested(listOf(patch("c1")))
        val blocked = current.gateRunning(patch("c1"))
        assertTrue(blocked is RunGate.Blocked)
        assertEquals("PERSISTENCE_UNAVAILABLE", (blocked as RunGate.Blocked).classification)
        assertNotNull(failure)
        assertNull(store.task("failed-startup"))
        assertTrue(current.degraded)
        scope.cancel()
    }

    @Test fun `5 a persisted result survives death before the step was completed and no step is invented complete`() = runBlocking {
        val r = started(recorder(acceptance = listOf("Foo.kt reads cleanly")))
        r.requested(listOf(read("c1"))); r.gateRunning(read("c1")); r.completed(read("c1"), "class Foo { }", false, null)
        val out = TaskRecovery(store, ::tick).recover("t1")!!
        val snap = store.snapshot("t1")!!
        assertEquals(InvocationStatus.SUCCEEDED, snap.invocations.single().status)
        assertEquals("class Foo { }", snap.invocations.single().resultSummary)
        assertEquals(StepStatus.INTERRUPTED, snap.currentSteps.single().status)   // running -> interrupted, never completed
        assertEquals(RecoveryDecision.SAFE_TO_RESUME, out.plan.decision)
        assertEquals(snap.currentSteps.single().stepId, out.plan.resumeStepId)
    }

    @Test fun `6 dying between two cycles resumes at the earliest unfinished step`() = runBlocking {
        val r = started(recorder(acceptance = listOf("first", "second")))
        r.requested(listOf(read("c1"))); r.gateRunning(read("c1")); r.completed(read("c1"), "ok", false, null)
        r.beginTurn(); r.requested(emptyList())
        val out = TaskRecovery(store, ::tick).recover("t1")!!
        assertEquals(2, store.counts("t1").cycles)
        assertEquals("t1:r1:A1", out.plan.resumeStepId)
    }

    @Test fun `7 a failure while replanning leaves the previous revision intact`() = runBlocking {
        val r = started(recorder()); r.requested(emptyList())
        backend.failAfterWrites = 1
        r.replan("steering: changed", "new goal"); r.requested(emptyList())
        assertTrue(r.degraded)
        val snap = store.snapshot("t1")!!
        assertEquals(1, snap.plans.size)
        assertEquals(StepStatus.READY, snap.currentSteps.single().status)
        r.replan("steering: changed again", "newer goal"); r.requested(emptyList())
        assertEquals(2, store.snapshot("t1")!!.plans.size)
    }

    @Test fun `8 restored evidence feeds the P0 gate but unknown or interrupted side effects can never satisfy it`() = runBlocking {
        val r = started(recorder())
        r.requested(listOf(read("c1"), patch("c2"))); r.gateRunning(read("c1")); r.completed(read("c1"), "class Foo", false, null); r.gateRunning(patch("c2"))
        TaskRecovery(store, ::tick).recover("t1")
        val seed = TaskRunRecorder.seedFrom(store.snapshot("t1")!!)
        assertEquals(RunEvidenceLedger.Outcome.OK, seed.first { it.callId == "c1" }.outcome)
        assertEquals(RunEvidenceLedger.Outcome.ERROR, seed.first { it.callId == "c2" }.outcome)
        val id = RunIdentity("t1")
        val ledger = RunEvidenceLedger("t1", null).also { it.restore(seed) }
        val gate = FinishGate(ledger, RunState("edit Foo.kt", ledger, emptyList(), true), FinalizationPolicy(true, true), id)
        assertTrue(gate.explicit(ToolCall("f", FinishTool.NAME, mapOf("status" to "completed", "summary" to "done", "evidence" to "c1")), "") is GateResult.Reject)
        // once a later patch succeeds in the resumed run and is read back, the gate can verify
        ledger.record(patch("c3"), ToolCallResult("c3", "patch_file_content", "patched"))
        ledger.record(read("c4"), ToolCallResult("c4", "read_file", "patched content"))
        assertTrue(gate.explicit(ToolCall("f2", FinishTool.NAME, mapOf("status" to "completed", "summary" to "done", "evidence" to "c3,c4")), "") is GateResult.Publish)
    }

    @Test fun `9 a verdict lost before it was stored is never trusted and the task stays resumable`() = runBlocking {
        val r = started(recorder()); r.requested(listOf(read("c1"))); r.gateRunning(read("c1")); r.completed(read("c1"), "x", false, null)
        // the gate decided completed in memory, but the process died before finalize reached storage
        val out = TaskRecovery(store, ::tick).recover("t1")!!
        val t = store.task("t1")!!
        assertEquals(TaskStatus.INTERRUPTED, t.status)
        assertNull(t.finishStatus)
        assertEquals("", t.completionDigest)
        assertEquals(RecoveryDecision.SAFE_TO_RESUME, out.plan.decision)
    }

    @Test fun `10 recovery can itself be interrupted and re-run to the same result`() = runBlocking {
        val r = started(recorder(acceptance = listOf("a"))); r.requested(listOf(patch("c1"), read("c2")))
        r.gateRunning(patch("c1")); r.gateRunning(read("c2"))
        r.requested(emptyList())
        backend.failAfterWrites = 3
        try { TaskRecovery(store, ::tick).recover("t1"); fail("expected failure") } catch (e: TaskStateException) { }
        val again = TaskRecovery(store, ::tick).recover("t1")!!
        val again2 = TaskRecovery(store, ::tick).recover("t1")!!
        assertEquals(InvocationStatus.UNKNOWN, status("c1"))
        assertEquals(InvocationStatus.INTERRUPTED, status("c2"))
        assertEquals(TaskStatus.INTERRUPTED, store.task("t1")!!.status)
        assertEquals(RecoveryDecision.REQUIRES_RECONCILIATION, again.plan.decision)
        assertEquals(again.plan.decision, again2.plan.decision)
        assertEquals(1, store.events("t1", 1000, 0).count { it.eventKey == "recover:t1/t1:c1/c1:UNKNOWN" })   // no duplicate transition
    }

    @Test fun `reconciliation records how the outcome was established and never reuses the tool result as proof`() = runBlocking {
        val r = started(recorder()); r.requested(listOf(patch("c1"))); r.gateRunning(patch("c1"))
        val rec = TaskRecovery(store, ::tick); rec.recover("t1")
        val id = "t1/t1:c1/c1"
        assertTrue(rec.reconcile("t1", id, InvocationStatus.SUCCEEDED, "") is TransitionResult.Rejected)
        assertTrue(rec.reconcile("wrong-task", id, InvocationStatus.SUCCEEDED, "verification") is TransitionResult.Rejected)
        assertEquals(TransitionResult.Applied, rec.reconcile("t1", id, InvocationStatus.SUCCEEDED, "read back /p/Foo.kt: patch present"))
        assertEquals(TransitionResult.Duplicate, rec.reconcile("t1", id, InvocationStatus.SUCCEEDED, "read back /p/Foo.kt: patch present"))
        val i = store.snapshot("t1")!!.invocations.single()
        assertEquals(InvocationStatus.SUCCEEDED, i.status)
        assertEquals("read back /p/Foo.kt: patch present", i.verification)
        assertEquals("", i.resultSummary)                                   // there never was a returned result
    }

    @Test fun `user continuation preserves task session policy evidence and starts a new cycle`() = runBlocking {
        val originalIdentity = RunIdentity(
            runId = "t1", acceptance = listOf("Foo.kt can be read back"),
            requireExecution = true,
            taskKind = com.omnidev.workspace.domain.engine.completion.TaskKind.IMPLEMENTATION,
            sessionId = 7L
        )
        val policy = FinalizationPolicy(enforce = true, requireExecution = true, requireAction = true)
        val metadata = TaskResumeMetadata.encode(originalIdentity, policy)
        val first = TaskRunRecorder(
            store, "t1", 7L, "edit Foo.kt and verify it", null, null,
            originalIdentity.acceptance, false, effect,
            SensitiveObservationRedactor::redact, ::tick,
            constraintsJson = metadata
        )
        first.start("edit Foo.kt"); first.beginTurn()
        first.requested(listOf(read("read-before-resume")))
        assertEquals(RunGate.Proceed, first.gateRunning(read("read-before-resume")))
        first.completed(read("read-before-resume"), "verified content", false, null)
        TaskRecovery(store, ::tick).recover("t1")

        val coordinator = TaskContinuationCoordinator(store, ::tick)
        val visible = coordinator.tasksForSession(7L).single()
        assertEquals("t1", visible.taskId)
        assertEquals(TaskStatus.INTERRUPTED, visible.status)
        assertEquals(0, visible.completedSteps) // a successful invocation is not P0 step-completion proof
        assertEquals(1, visible.counts.succeeded)
        assertEquals(1, visible.openSteps.size)
        assertEquals(listOf("t1/t1:c1/read-before-resume"), visible.unresolvedInvocations.map { it.invocationId })
        assertFalse(visible.unresolvedInvocations.single().requiresReconciliation)

        val continuation = (coordinator.resume("t1") as TaskResumeResult.Ready).continuation
        assertEquals("t1", continuation.taskId)
        assertEquals(7L, continuation.sessionId)
        assertEquals("edit Foo.kt and verify it", continuation.originalRequest)
        assertEquals("t1", continuation.identity.runId)
        assertEquals(7L, continuation.identity.sessionId)
        assertTrue(continuation.identity.forceFinishProtocol)
        assertEquals(true, continuation.identity.requireExecution)
        assertEquals(com.omnidev.workspace.domain.engine.completion.TaskKind.IMPLEMENTATION, continuation.identity.taskKind)
        assertEquals(RunEvidenceLedger.Outcome.OK, continuation.identity.resume!!.single().outcome)

        val resumed = TaskRunRecorder(
            store, continuation.taskId, continuation.sessionId, continuation.originalRequest,
            continuation.identity.parentTaskId, continuation.identity.workerId,
            continuation.identity.acceptance, true, effect,
            SensitiveObservationRedactor::redact, ::tick,
            constraintsJson = metadata, resumeClaimed = true
        )
        resumed.start(continuation.prompt); resumed.beginTurn(); resumed.requested(emptyList())
        val snapshot = store.snapshot("t1")!!
        assertEquals("edit Foo.kt and verify it", snapshot.task.originalRequest)
        assertEquals(7L, snapshot.task.sessionId)
        assertEquals(TaskStatus.RUNNING, snapshot.task.status)
        assertEquals(2, snapshot.cycles.size)
        assertEquals("t1:c1", snapshot.cycles.first().cycleId)
        assertEquals("t1:c2", snapshot.cycles.last().cycleId)
        assertEquals(InvocationStatus.SUCCEEDED, snapshot.invocations.single().status)
        assertEquals(2, coordinator.summary("t1")!!.counts.cycles)
    }

    @Test fun `user follow up updates objective revision without replacing the original request`() = runBlocking {
        val r = started(recorder())
        r.requested(emptyList())
        r.replan("user follow-up", "also add a regression test")
        r.requested(emptyList())
        val task = store.task("t1")!!
        val snapshot = store.snapshot("t1")!!
        assertEquals("edit Foo.kt and verify it", task.originalRequest)
        assertEquals("also add a regression test", task.normalizedObjective)
        assertEquals(2, snapshot.plans.size)
        assertEquals("user follow-up", snapshot.latestPlan!!.reason)
    }

    @Test fun `cancelling a resumed run preserves the same task as interrupted with its new cycle`() = runBlocking {
        val first = started(recorder())
        first.requested(listOf(read("c1")))
        assertEquals(RunGate.Proceed, first.gateRunning(read("c1")))
        first.completed(read("c1"), "initial state inspected", false, null)
        TaskRecovery(store, ::tick).recover("t1")

        val coordinator = TaskContinuationCoordinator(store, ::tick)
        val continuation = (coordinator.resume("t1") as TaskResumeResult.Ready).continuation
        val resumed = TaskRunRecorder(
            store, continuation.taskId, continuation.sessionId, continuation.originalRequest,
            continuation.identity.parentTaskId, continuation.identity.workerId,
            continuation.identity.acceptance, true, effect,
            SensitiveObservationRedactor::redact, ::tick,
            constraintsJson = store.task("t1")!!.constraintsJson, resumeClaimed = true
        )
        resumed.start(continuation.prompt)
        resumed.beginTurn()
        resumed.requested(listOf(patch("c2")))
        assertEquals(RunGate.Proceed, resumed.gateRunning(patch("c2")))
        // Same finalization cleanup invoked by FinishRuntime when the AgentPipeline job is cancelled.
        resumed.onFlowEnd()

        val snapshot = store.snapshot("t1")!!
        assertEquals("t1", snapshot.task.taskId)
        assertEquals(7L, snapshot.task.sessionId)
        assertEquals("edit Foo.kt and verify it", snapshot.task.originalRequest)
        assertEquals(TaskStatus.INTERRUPTED, snapshot.task.status)
        assertEquals(2, snapshot.task.currentCycle)
        assertEquals(2, snapshot.cycles.size)
        assertEquals(InvocationStatus.SUCCEEDED, snapshot.invocations.first { it.callId == "c1" }.status)
        assertEquals(InvocationStatus.UNKNOWN, snapshot.invocations.first { it.callId == "c2" }.status)
    }

    @Test fun `terminal and paused tasks get the right decision`() = runBlocking {
        store.createTask(NewTask("done", 1, "x", createdAt = 1)); store.transitionTask("done", TaskStatus.RUNNING, "g", 2, "a")
        store.finalizeTask("done", TaskStatus.COMPLETED, FinishRecord("completed", "ok", "d"), emptyList(), 3, "f")
        assertEquals(RecoveryDecision.ALREADY_COMPLETED, TaskRecovery(store, ::tick).recover("done")!!.plan.decision)
        store.createTask(NewTask("bad", 1, "x", createdAt = 1)); store.transitionTask("bad", TaskStatus.RUNNING, "g", 2, "a")
        store.finalizeTask("bad", TaskStatus.FAILED, FinishRecord("failed", "no", "d"), emptyList(), 3, "f")
        assertEquals(RecoveryDecision.PERMANENTLY_FAILED, TaskRecovery(store, ::tick).recover("bad")!!.plan.decision)
        store.createTask(NewTask("wait", 1, "x", createdAt = 1)); store.transitionTask("wait", TaskStatus.RUNNING, "g", 2, "a")
        store.finalizeTask("wait", TaskStatus.AWAITING_APPROVAL, FinishRecord("awaiting_approval", "ok?", "d"), emptyList(), 3, "f")
        assertEquals(RecoveryDecision.REQUIRES_USER_INTERVENTION, TaskRecovery(store, ::tick).recover("wait")!!.plan.decision)
        assertEquals(TaskStatus.AWAITING_APPROVAL, store.task("wait")!!.status)   // untouched
    }

    @Test fun `startup sweep drains every stale task beyond the first recovery page`() = runBlocking {
        repeat(125) { index ->
            val taskId = "stale-$index"
            val r = started(recorder(taskId))
            r.requested(emptyList())
        }
        val swept = TaskRecovery(store, ::tick).recoverStale(staleAfterMs = 0L, liveTaskIds = emptySet(), limit = 17)
        assertEquals(125, swept.size)
        assertTrue((0 until 125).all { store.task("stale-$it")?.status == TaskStatus.INTERRUPTED })
    }

    @Test fun `startup sweep only touches stale tasks that are not live`() = runBlocking {
        val live = started(recorder("live")); live.requested(emptyList())
        val dead = started(recorder("dead")); dead.requested(emptyList())
        clock += 100_000
        val swept = TaskRecovery(store, ::tick).recoverStale(60_000, liveTaskIds = setOf("live"))
        assertEquals(listOf("dead"), swept.map { it.taskId })
        assertEquals(TaskStatus.RUNNING, store.task("live")!!.status)
        assertEquals(TaskStatus.INTERRUPTED, store.task("dead")!!.status)
    }
}
