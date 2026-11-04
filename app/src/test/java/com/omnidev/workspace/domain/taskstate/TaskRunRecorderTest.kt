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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TaskRunRecorderTest {
    private val backend = MemoryTaskStateBackend()
    private val store = InMemoryTaskStateStore(backend)
    private var clock = 1_000L
    private val effect: (ToolCall) -> Effect = { if (it.name.startsWith("read")) Effect.READ_ONLY else Effect.SIDE_EFFECT }
    private fun recorder(acceptance: List<String> = emptyList()) =
        TaskRunRecorder(store, "t1", 7L, "edit Foo.kt", null, null, acceptance, false, effect, SensitiveObservationRedactor::redact, { ++clock })
    private fun read(id: String) = ToolCall(id, "read_file", mapOf("path" to "/p/Foo.kt"))
    private fun patch(id: String) = ToolCall(id, "patch_file_content", mapOf("path" to "/p/Foo.kt", "content" to "x"))

    /** Runs the P0 gate over a ledger mirroring the recorder's calls, to obtain a genuine verdict. */
    private fun verdict(vararg steps: Pair<ToolCall, String>): GateResult.Publish {
        val id = RunIdentity("t1", null)
        val ledger = RunEvidenceLedger("t1", null)
        steps.forEach { (c, out) -> ledger.record(c, ToolCallResult(c.id, c.name, out)) }
        val gate = FinishGate(ledger, RunState("edit Foo.kt", ledger, emptyList(), true), FinalizationPolicy(true, true), id)
        return gate.explicit(ToolCall("f", FinishTool.NAME, mapOf("status" to "completed", "summary" to "all done", "evidence" to steps.joinToString(",") { it.first.id })), "") as GateResult.Publish
    }

    @Test fun `a side effect whose start cannot be stored is refused but a read still runs`() = runBlocking {
        val r = recorder(); r.start("g"); r.beginTurn()
        r.requested(listOf(patch("c1"), read("c2")))
        backend.failAfterWrites = 0
        val blocked = r.gateRunning(patch("c1"))
        assertTrue(blocked is RunGate.Blocked)
        assertEquals("PERSISTENCE_UNAVAILABLE", (blocked as RunGate.Blocked).classification)
        assertTrue(r.degraded)
        assertNotNull(r.lastError)
        backend.failAfterWrites = 0
        assertEquals(RunGate.Proceed, r.gateRunning(read("c2")))              // best effort for reads
    }


    @Test fun `a confirmed successful side effect is not repeated with identical inputs`() = runBlocking {
        val r = recorder(); r.start("g"); r.beginTurn()
        r.requested(listOf(patch("c1"))); assertEquals(RunGate.Proceed, r.gateRunning(patch("c1")))
        r.completed(patch("c1"), "created successfully", false, null)
        r.requested(listOf(patch("c2")))
        val blocked = r.gateRunning(patch("c2"))
        assertTrue(blocked is RunGate.Blocked)
        assertEquals("DUPLICATE_SIDE_EFFECT_ALREADY_SUCCEEDED", (blocked as RunGate.Blocked).classification)
        assertEquals(InvocationStatus.PENDING, store.snapshot("t1")!!.invocations.last().status)
    }

    @Test fun `a side effect without a persisted task or invocation fails closed`() = runBlocking {
        val failedBarrier = TaskRecoveryBarrier()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        failedBarrier.startOnce(scope, recovery = { throw IllegalStateException("startup recovery unavailable") }, onFailure = {})
        val r = TaskRunRecorder(store, "blocked", 7L, "edit Foo.kt", null, null, emptyList(), false, effect,
            SensitiveObservationRedactor::redact, { ++clock }, awaitStartupRecovery = { failedBarrier.awaitReady() })
        r.start("g"); r.beginTurn(); r.requested(listOf(patch("c1")))
        val blocked = r.gateRunning(patch("c1"))
        assertTrue(blocked is RunGate.Blocked)
        assertEquals("PERSISTENCE_UNAVAILABLE", (blocked as RunGate.Blocked).classification)
        assertNull(store.task("blocked"))
        assertTrue(r.degraded)
        scope.cancel()
    }

    @Test fun `results are bounded and redacted and inputs never keep credentials`() = runBlocking {
        val r = recorder(); r.start("g"); r.beginTurn()
        val login = ToolCall("c1", "http_post", mapOf("url" to "https://x/y", "password" to "hunter2", "api_key" to "sk-abc"))
        r.requested(listOf(login)); r.gateRunning(login)
        r.completed(login, "token: abcdef123456 " + "A".repeat(20_000), false, null)
        val i = store.snapshot("t1")!!.invocations.single()
        assertFalse(i.inputSanitized.contains("hunter2"))
        assertFalse(i.inputSanitized.contains("sk-abc"))
        assertTrue(i.inputSanitized.contains("[REDACTED]"))
        assertFalse(i.resultSummary.contains("abcdef123456"))
        assertTrue(i.resultTruncated)
        assertTrue(i.resultChars > 20_000)
        assertTrue(i.resultSummary.length <= TaskRunRecorder.MAX_RESULT_CHARS + 40)
        assertEquals(24, i.resultHash.length)
    }

    @Test fun `a returned result is not verification and a failed call is recorded as failed`() = runBlocking {
        val r = recorder(); r.start("g"); r.beginTurn()
        r.requested(listOf(patch("c1"), patch("c2"))); r.gateRunning(patch("c1")); r.gateRunning(patch("c2"))
        r.completed(patch("c1"), "patched", false, null)
        r.completed(patch("c2"), "disk full", true, "IO_ERROR")
        val inv = store.snapshot("t1")!!.invocations
        assertEquals(InvocationStatus.SUCCEEDED, inv[0].status)
        assertEquals("", inv[0].verification)                                 // success output is not verification
        assertEquals(InvocationStatus.FAILED, inv[1].status)
        assertEquals("IO_ERROR", inv[1].classification)
        r.completed(patch("c1"), "again", true, null)                          // a duplicate completion event changes nothing
        assertEquals(InvocationStatus.SUCCEEDED, store.snapshot("t1")!!.invocations[0].status)
    }

    @Test fun `calls blocked before dispatch are recorded as cancelled never as executed`() = runBlocking {
        val r = recorder(); r.start("g"); r.beginTurn()
        r.requested(listOf(patch("c1")))
        r.completed(patch("c1"), "Tool is unavailable under the current access policy.", true, "TOOL_NOT_AVAILABLE")
        assertEquals(InvocationStatus.CANCELLED, store.snapshot("t1")!!.invocations.single().status)
        assertEquals(0, store.counts("t1").succeeded)
    }

    @Test fun `an unknown mutation outcome from the pipeline is persisted as unknown`() = runBlocking {
        val r = recorder(); r.start("g"); r.beginTurn()
        r.requested(listOf(patch("c1"))); r.gateRunning(patch("c1"))
        r.completed(patch("c1"), "timeout; outcome unknown", true, "MUTATION_OUTCOME_UNKNOWN")
        assertEquals(InvocationStatus.UNKNOWN, store.snapshot("t1")!!.invocations.single().status)
        assertEquals(1, store.unresolvedSideEffects("t1").size)
    }

    @Test fun `finalizing completed proves steps with succeeded invocations and is safe to call twice`() = runBlocking {
        val r = recorder(); r.start("edit Foo.kt"); r.beginTurn()
        r.requested(listOf(patch("c1"), read("c2")))
        r.gateRunning(patch("c1")); r.completed(patch("c1"), "patched", false, null)
        r.gateRunning(read("c2")); r.completed(read("c2"), "patched content", false, null)
        val v = verdict(patch("c1") to "patched", read("c2") to "patched content")
        r.finalize(v); r.finalize(v)
        val snap = store.snapshot("t1")!!
        assertEquals(TaskStatus.COMPLETED, snap.task.status)
        assertEquals("completed", snap.task.finishStatus)
        assertEquals(v.report.ledgerDigest, snap.task.completionDigest)
        val step = snap.currentSteps.single()
        assertEquals(StepStatus.COMPLETED, step.status)
        assertTrue(step.evidenceInvocationIds.all { id -> snap.invocations.first { it.invocationId == id }.status == InvocationStatus.SUCCEEDED })
        assertTrue(step.evidenceInvocationIds.isNotEmpty())
        assertEquals(1, store.events("t1", 1000, 0).count { it.entityType == "task" && it.to == "COMPLETED" })
        assertTrue(r.finalized)
    }

    @Test fun `non success verdicts keep their meaning in storage`() = runBlocking {
        val r = recorder(); r.start("g"); r.beginTurn(); r.requested(emptyList())
        val ledger = RunEvidenceLedger("t1", null)
        val gate = FinishGate(ledger, RunState("g", ledger, emptyList(), true), FinalizationPolicy(true, true), RunIdentity("t1"))
        val waiting = gate.explicit(ToolCall("f", FinishTool.NAME, mapOf("status" to "awaiting_approval", "summary" to "May I delete 3 files?")), "") as GateResult.Publish
        r.finalize(waiting)
        val snap = store.snapshot("t1")!!
        assertEquals(TaskStatus.AWAITING_APPROVAL, snap.task.status)
        assertEquals(StepStatus.BLOCKED, snap.currentSteps.single().status)
        assertEquals(RecoveryDecision.REQUIRES_USER_INTERVENTION, TaskRecoveryPlanner.plan(snap).decision)
    }

    @Test fun `ending without a verdict records an interrupted run`() = runBlocking {
        val r = recorder(); r.start("g"); r.beginTurn(); r.requested(listOf(patch("c1"))); r.gateRunning(patch("c1"))
        r.onFlowEnd()
        assertEquals(TaskStatus.INTERRUPTED, store.task("t1")!!.status)
        assertEquals(InvocationStatus.UNKNOWN, store.snapshot("t1")!!.invocations.single().status)
        assertEquals("interrupted", store.snapshot("t1")!!.cycles.single().outcome)
    }

    @Test fun `steps and tool calls attach to the persisted plan and cycle`() = runBlocking {
        val r = recorder(listOf("Foo.kt fixed", "tests pass")); r.start("g"); r.beginTurn()
        r.requested(listOf(read("c1"))); r.gateRunning(read("c1"))
        val snap = store.snapshot("t1")!!
        assertEquals(listOf("A1", "A2"), snap.currentSteps.map { it.key })
        assertEquals("t1:c1", snap.invocations.single().cycleId)
        assertEquals(snap.currentSteps[0].stepId, snap.invocations.single().stepId)
        assertEquals(StepStatus.RUNNING, snap.currentSteps[0].status)
        assertEquals(1, snap.task.currentCycle)
    }

    @Test fun `a verified run that needed no tool still finalizes as completed with a written justification`() = runBlocking {
        val r = recorder(); r.start("explain the design"); r.beginTurn(); r.requested(emptyList())
        val ledger = RunEvidenceLedger("t1", null)
        val gate = FinishGate(ledger, RunState("explain", ledger, emptyList(), false), FinalizationPolicy(true, false), RunIdentity("t1"))
        val v = gate.implicit("The design keeps three layers.") as GateResult.Publish
        assertTrue(v.report.verified)
        r.finalize(v)
        val snap = store.snapshot("t1")!!
        assertEquals(TaskStatus.COMPLETED, snap.task.status)
        val step = snap.currentSteps.single()
        assertEquals(StepStatus.COMPLETED, step.status)               // READY -> COMPLETED, proven by the justification
        assertTrue(step.evidenceInvocationIds.isEmpty())
        assertTrue(step.nonToolJustification.contains("did not require execution"))
        assertTrue(r.finalized)
    }

    @Test fun `a resumed run can complete a step that an earlier run left interrupted`() = runBlocking {
        val first = recorder(listOf("read the file")); first.start("g"); first.beginTurn()
        first.requested(listOf(read("c1"))); first.gateRunning(read("c1")); first.completed(read("c1"), "x", false, null)
        TaskRecovery(store, { ++clock }).recover("t1")
        assertEquals(StepStatus.INTERRUPTED, store.snapshot("t1")!!.currentSteps.single().status)
        val again = TaskRunRecorder(store, "t1", 7L, "edit Foo.kt", null, null, listOf("read the file"), true, effect, SensitiveObservationRedactor::redact, { ++clock })
        again.start("g"); again.beginTurn(); again.requested(emptyList())
        val ledger = RunEvidenceLedger("t1", null).also { it.restore(TaskRunRecorder.seedFrom(store.snapshot("t1")!!)) }
        val gate = FinishGate(ledger, RunState("g", ledger, listOf("read the file"), true), FinalizationPolicy(true, true), RunIdentity("t1", acceptance = listOf("read the file")))
        val v = gate.explicit(ToolCall("f", FinishTool.NAME, mapOf("status" to "completed", "summary" to "ok", "evidence" to "c1", "criteria" to "A1 => c1")), "") as GateResult.Publish
        again.finalize(v)
        val snap = store.snapshot("t1")!!
        assertEquals(TaskStatus.COMPLETED, snap.task.status)
        assertEquals(StepStatus.COMPLETED, snap.currentSteps.single().status)
        assertEquals(listOf("t1/t1:c1/c1"), snap.currentSteps.single().evidenceInvocationIds)
    }
}
