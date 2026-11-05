package com.omnidev.workspace.domain.taskstate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TaskStateEngineTest {
    private val backend = MemoryTaskStateBackend()
    private val store = InMemoryTaskStateStore(backend)
    private fun newTask(id: String = "t1", request: String = "fix the crash") = NewTask(id, 7L, request, createdAt = 1_000)
    private fun inv(id: String, task: String = "t1", cycle: String = "t1:c1", effect: Effect = Effect.SIDE_EFFECT, tool: String = "patch_file_content") =
        NewInvocation("$task/$cycle/$id", id, task, cycle, null, tool, tool, "{}", "h", effect, "key-$id", requestedAt = 2_000)

    @Test fun `create and reload a task keeps the original request immutable`() = runBlocking {
        assertTrue(store.createTask(newTask()))
        assertFalse(store.createTask(newTask(request = "something else entirely")))
        store.setObjective("t1", "interpreted: repair crash", 1_500)
        val t = store.task("t1")!!
        assertEquals("fix the crash", t.originalRequest)
        assertEquals("interpreted: repair crash", t.normalizedObjective)
        assertEquals(7L, t.sessionId)
        assertEquals(TaskStatus.CREATED, t.status)
    }

    @Test fun `plan revisions are kept and supersession is explicit and recorded`() = runBlocking {
        store.createTask(newTask())
        val r1 = store.appendPlanRevision("t1", listOf(NewStep("S1", "read"), NewStep("S2", "edit", dependsOnKeys = listOf("S1"))), "initial", 10, "p1")!!
        assertEquals(1, r1.revision)
        val s1 = store.snapshot("t1")!!.currentSteps
        assertEquals(StepStatus.READY, s1[0].status)
        assertEquals(StepStatus.PENDING, s1[1].status)
        store.transitionInvocation("x", InvocationStatus.RUNNING, InvocationPatch(), 1, "k") // unknown id: harmless rejection
        store.recordInvocationIntent(inv("c1", effect = Effect.READ_ONLY, tool = "read_file"))
        store.transitionInvocation("t1/t1:c1/c1", InvocationStatus.RUNNING, InvocationPatch(startedAt = 11), 11, "run1")
        store.transitionInvocation("t1/t1:c1/c1", InvocationStatus.SUCCEEDED, InvocationPatch(completedAt = 12, resultSummary = "ok"), 12, "done1")
        assertTrue(store.transitionStep(s1[0].stepId, StepStatus.RUNNING, "go", 13, "st1").ok)
        assertTrue(store.transitionStep(s1[0].stepId, StepStatus.COMPLETED, "read", 14, "st2", StepCompletion(listOf("t1/t1:c1/c1"))).ok)

        val r2 = store.appendPlanRevision("t1", listOf(NewStep("S1", "read"), NewStep("S2", "edit differently")), "replan after failure", 20, "p2")!!
        assertEquals(2, r2.revision)
        val snap = store.snapshot("t1")!!
        assertEquals(2, snap.plans.size)
        assertNotNull(snap.plans[0].supersededAt)
        val old = snap.steps.filter { it.planId == r1.planId }
        assertEquals(StepStatus.COMPLETED, old[0].status)                       // history untouched
        assertEquals(StepStatus.SKIPPED, old[1].status)
        assertTrue(old[1].statusReason.contains("superseded by revision 2"))
        val now = snap.currentSteps
        assertEquals(StepStatus.COMPLETED, now[0].status)                        // identical step carried over with its proof
        assertEquals(listOf("t1/t1:c1/c1"), now[0].evidenceInvocationIds)
        assertEquals(StepStatus.READY, now[1].status)                            // dependency now satisfied
        assertEquals(2, store.task("t1")!!.planRevision)
        assertNull(store.appendPlanRevision("t1", listOf(NewStep("S1", "x")), "dup", 21, "p2"))   // same event twice
        assertEquals(2, store.snapshot("t1")!!.plans.size)
    }

    @Test fun `a step can only complete with a succeeded invocation or a justification`() = runBlocking {
        store.createTask(newTask())
        store.appendPlanRevision("t1", listOf(NewStep("S1", "do it")), "initial", 10, "p1")
        val step = store.snapshot("t1")!!.currentSteps[0].stepId
        store.transitionStep(step, StepStatus.RUNNING, "go", 11, "a")
        assertTrue(store.transitionStep(step, StepStatus.COMPLETED, "model said so", 12, "b") is TransitionResult.Rejected)
        assertTrue(store.transitionStep(step, StepStatus.COMPLETED, "x", 12, "c", StepCompletion(listOf("t1/t1:c1/nope"))) is TransitionResult.Rejected)
        store.recordInvocationIntent(inv("c1"))                                            // requested only
        assertTrue(store.transitionStep(step, StepStatus.COMPLETED, "x", 12, "d", StepCompletion(listOf("t1/t1:c1/c1"))) is TransitionResult.Rejected)
        store.transitionInvocation("t1/t1:c1/c1", InvocationStatus.RUNNING, InvocationPatch(), 13, "e")
        assertTrue(store.transitionStep(step, StepStatus.COMPLETED, "x", 14, "f", StepCompletion(listOf("t1/t1:c1/c1"))) is TransitionResult.Rejected) // running is not proof
        store.transitionInvocation("t1/t1:c1/c1", InvocationStatus.UNKNOWN, InvocationPatch(), 15, "g")
        assertTrue(store.transitionStep(step, StepStatus.COMPLETED, "x", 16, "h", StepCompletion(listOf("t1/t1:c1/c1"))) is TransitionResult.Rejected) // unknown is not proof
        assertTrue(store.transitionStep(step, StepStatus.COMPLETED, "analysis only", 17, "i", StepCompletion(nonToolJustification = "answered from the user's own text")).ok)
        assertEquals(StepStatus.COMPLETED, store.snapshot("t1")!!.currentSteps[0].status)
    }

    @Test fun `invocation lifecycle is guarded and duplicate events are no-ops`() = runBlocking {
        store.createTask(newTask())
        assertTrue(store.recordInvocationIntent(inv("c1")))
        assertFalse(store.recordInvocationIntent(inv("c1")))                                // same event twice
        val id = "t1/t1:c1/c1"
        assertTrue(store.transitionInvocation(id, InvocationStatus.SUCCEEDED, InvocationPatch(), 3, "x") is TransitionResult.Rejected) // PENDING -> SUCCEEDED skips RUNNING
        assertEquals(TransitionResult.Applied, store.transitionInvocation(id, InvocationStatus.RUNNING, InvocationPatch(startedAt = 4), 4, "run"))
        assertEquals(TransitionResult.Duplicate, store.transitionInvocation(id, InvocationStatus.RUNNING, InvocationPatch(), 4, "run"))
        assertEquals(TransitionResult.Applied, store.transitionInvocation(id, InvocationStatus.SUCCEEDED, InvocationPatch(completedAt = 5, resultSummary = "done", isError = false), 5, "done"))
        assertTrue(store.transitionInvocation(id, InvocationStatus.FAILED, InvocationPatch(), 6, "late") is TransitionResult.Rejected)   // final stays final
        val r = store.snapshot("t1")!!.invocations.single()
        assertEquals(InvocationStatus.SUCCEEDED, r.status)
        assertEquals("done", r.resultSummary)
        assertEquals(1, store.events("t1", 100, 0).count { it.eventKey == "run" })
    }

    @Test fun `a failed multi row write rolls everything back`() = runBlocking {
        store.createTask(newTask())
        store.appendPlanRevision("t1", listOf(NewStep("S1", "a")), "initial", 10, "p1")
        val before = store.snapshot("t1")!!
        backend.failAfterWrites = 3                                  // dies in the middle of the second revision
        try { store.appendPlanRevision("t1", listOf(NewStep("S1", "b"), NewStep("S2", "c")), "replan", 20, "p2"); fail("expected failure") }
        catch (e: TaskStateException) { assertTrue(e.message!!.contains("simulated")) }
        val after = store.snapshot("t1")!!
        assertEquals(before.plans, after.plans)
        assertEquals(before.steps, after.steps)
        assertEquals(1, after.task.planRevision)
        assertNotNull(store.appendPlanRevision("t1", listOf(NewStep("S1", "b")), "replan", 20, "p2"))   // the event was not consumed
    }

    @Test fun `finalization is atomic and a rejected proof leaves it unapplied`() = runBlocking {
        store.createTask(newTask()); store.transitionTask("t1", TaskStatus.RUNNING, "go", 5, "s")
        store.appendPlanRevision("t1", listOf(NewStep("S1", "do")), "initial", 10, "p1")
        val step = store.snapshot("t1")!!.currentSteps[0].stepId
        store.transitionStep(step, StepStatus.RUNNING, "go", 11, "a")
        val bad = store.finalizeTask("t1", TaskStatus.COMPLETED, FinishRecord("completed", "done", "d1"),
            listOf(StepOutcome(step, StepStatus.COMPLETED, "ok", StepCompletion(listOf("t1/t1:c1/ghost")))), 30, "fin")
        assertTrue(bad is TransitionResult.Rejected)
        assertEquals(TaskStatus.RUNNING, store.task("t1")!!.status)
        assertEquals(StepStatus.RUNNING, store.snapshot("t1")!!.currentSteps[0].status)
        val ok = store.finalizeTask("t1", TaskStatus.COMPLETED, FinishRecord("completed", "done", "d1"),
            listOf(StepOutcome(step, StepStatus.COMPLETED, "ok", StepCompletion(nonToolJustification = "no tool needed"))), 31, "fin2")
        assertEquals(TransitionResult.Applied, ok)
        assertEquals(TransitionResult.Duplicate, store.finalizeTask("t1", TaskStatus.COMPLETED, FinishRecord("completed", "done", "d1"), emptyList(), 32, "fin2"))
        assertTrue(store.finalizeTask("t1", TaskStatus.FAILED, FinishRecord("failed", "x", "d"), emptyList(), 33, "fin3") is TransitionResult.Rejected)
        assertEquals(TaskStatus.COMPLETED, store.task("t1")!!.status)
    }

    @Test fun `cycles are numbered per task and counts follow the persisted records`() = runBlocking {
        store.createTask(newTask())
        repeat(2_500) { i -> store.beginCycle("t1", null, 100L + i, "cycle:$i") }
        assertEquals(TransitionResult.Applied, store.endCycle("t1:c1", "continued", "turn 1", "[]", 5))
        assertEquals(TransitionResult.Duplicate, store.endCycle("t1:c1", "continued", "turn 1", "[]", 6))
        assertEquals(2_500, store.counts("t1").cycles)                         // no artificial cap on the real count
        assertEquals(2_500, store.task("t1")!!.currentCycle)
        repeat(1_200) { i -> store.recordInvocationIntent(inv("c$i", cycle = "t1:c2500", effect = Effect.READ_ONLY, tool = "read_file")) }
        val c = store.counts("t1")
        assertEquals(1_200, c.invocations)
        assertEquals(1_200, c.unresolved)
        assertEquals(50, store.invocations("t1", 100, 1_150).size)             // pagination bounds the page, not the data
    }

    @Test fun `tasks are found by status and session`() = runBlocking {
        store.createTask(newTask("a")); store.createTask(newTask("b"))
        store.transitionTask("a", TaskStatus.RUNNING, "go", 5, "s")
        assertEquals(listOf("a"), store.tasksByStatus(setOf(TaskStatus.RUNNING), 10).map { it.taskId })
        assertEquals(2, store.tasksForSession(7L, 10).size)
        assertTrue(store.transitionTask("a", TaskStatus.CREATED, "back", 6, "x") is TransitionResult.Rejected)
    }
}
