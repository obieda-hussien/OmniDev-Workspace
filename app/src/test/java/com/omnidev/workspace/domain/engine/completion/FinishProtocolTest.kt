package com.omnidev.workspace.domain.engine.completion

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.model.ToolCallResult
import com.omnidev.workspace.domain.engine.IntentClassifier
import com.omnidev.workspace.domain.engine.RunEvidenceLedger
import org.junit.Assert.*
import org.junit.Test

class FinishProtocolTest {
    private val identity = RunIdentity(runId = "run-test", workerId = null)
    private val action = FinalizationPolicy(enforce = true, requireExecution = true)

    private class Rig(val identity: RunIdentity, val policy: FinalizationPolicy, acceptance: List<String> = emptyList()) {
        val ledger = RunEvidenceLedger(identity.runId, identity.workerId)
        val state = RunState("fix the bug in Foo.kt", ledger, acceptance, policy.requireExecution)
        val gate = FinishGate(ledger, state, policy, identity)
        fun run(id: String, tool: String, args: Map<String, String>, out: String, error: Boolean = false) =
            ledger.record(ToolCall(id, tool, args), ToolCallResult(id, tool, out, error))
        fun finish(vararg kv: Pair<String, String>) = gate.explicit(ToolCall("fin", FinishTool.NAME, kv.toMap()), "")
    }

    private fun rig(acceptance: List<String> = emptyList(), policy: FinalizationPolicy = action) = Rig(identity, policy, acceptance)

    private fun signals(
        wordCount: Int = 1,
        executionIntent: Float = 0f,
        conversationalIntent: Float = 0f,
        mutationIntent: Float = 0f,
        codeIntent: Float = 0f,
        deviceIntent: Float = 0f,
        researchIntent: Float = 0f,
        verificationIntent: Float = 0f,
        parallelism: Float = 0f,
        breadth: Float = 0f,
        structuralComplexity: Float = 0f,
        domainCount: Int = 0
    ) = IntentClassifier.TaskSignals(
        wordCount, executionIntent, conversationalIntent, mutationIntent, codeIntent, deviceIntent,
        researchIntent, verificationIntent, parallelism, breadth, structuralComplexity, domainCount
    )

    // 1. Model says it executed without any tool call.
    @Test fun `text claiming execution with no tool call is rejected then downgraded to incomplete`() {
        val r = rig()
        val claim = "I fixed Foo.kt and all tests pass."
        repeat(3) { assertTrue(r.gate.implicit(claim) is GateResult.Reject) }
        val publish = r.gate.implicit(claim) as GateResult.Publish
        assertEquals(FinishStatus.INCOMPLETE, publish.status)
        assertFalse(publish.report.verified)
        assertTrue(publish.text.startsWith("[Incomplete]"))
    }

    // 2. Tool fails, runtime recovery: open failure blocks, later success of same tool unblocks.
    @Test fun `unresolved tool failure blocks completion until recovered`() {
        val r = rig()
        r.run("c1", "run_terminal", mapOf("action" to "exec"), "exit 1", error = true)
        assertTrue(r.gate.explicit(ToolCall("f", FinishTool.NAME, mapOf("status" to "completed", "summary" to "done")), "") is GateResult.Reject)
        assertTrue(r.state.nextAction().contains("c1"))
        r.run("c2", "run_terminal", mapOf("action" to "exec"), "BUILD OK")
        val ok = r.finish("status" to "completed", "summary" to "built", "evidence" to "c2")
        assertTrue(ok is GateResult.Publish && ok.report.verified)
    }

    // 3. Tool succeeds but the task is not complete.
    @Test fun `successful tool is not enough when acceptance items are unproven`() {
        val r = rig(listOf("Foo.kt compiles", "unit test added"))
        r.run("c1", "read_file", mapOf("path" to "/p/Foo.kt"), "class Foo")
        val partial = r.finish("status" to "completed", "summary" to "done", "criteria" to "A1 => c1")
        assertTrue(partial is GateResult.Reject)
        assertTrue((partial as GateResult.Reject).feedback.contains("A2"))
        r.run("c2", "run_terminal", mapOf("action" to "exec"), "tests passed")
        val full = r.finish("status" to "completed", "summary" to "done", "criteria" to "A1 => c1\nA2 => c2")
        assertTrue(full is GateResult.Publish && full.report.verified)
    }

    // 4. Stale / unlinked evidence.
    @Test fun `evidence from another run unknown ids and failed calls cannot prove completion`() {
        val r = rig()
        r.run("c1", "read_file", mapOf("path" to "/p/A.kt"), "x")
        r.run("c2", "grep_search", emptyMap(), "boom", error = true)
        r.run("c3", "grep_search", emptyMap(), "ok")
        assertTrue(r.finish("status" to "completed", "summary" to "d", "evidence" to "ghost-9") is GateResult.Reject)
        assertTrue(r.finish("status" to "completed", "summary" to "d", "evidence" to "c2") is GateResult.Reject)
        val foreign = RunEvidenceLedger("run-other", null)
        foreign.record(ToolCall("x1", "read_file", mapOf("path" to "/p/A.kt")), ToolCallResult("x1", "read_file", "x"))
        val ctx = VerificationContext("run-test", null, foreign.snapshot(), true)
        val eval = FinishVerifier.evaluate(ctx, FinishRequest(FinishStatus.COMPLETED, "d", listOf("x1")))
        assertFalse(eval.accepted)
        assertTrue(eval.problems.joinToString().contains("another run"))
        assertTrue(r.finish("status" to "completed", "summary" to "d", "evidence" to "c1") is GateResult.Publish)
    }

    // 5. Long chat with compaction: state is derived from the ledger, not from messages.
    @Test fun `run state survives fifty turns and dropping every message`() {
        val r = rig(listOf("report written"))
        var messages = mutableListOf<String>()
        repeat(50) { i ->
            messages += "turn $i"
            r.run("c$i", "read_file", mapOf("path" to "/p/F$i.kt"), "content $i")
            if (i % 10 == 9) messages = mutableListOf("[compacted summary]") // compaction drops history
        }
        messages.clear()
        val text = r.state.render()
        assertTrue(text.contains("GOAL: fix the bug in Foo.kt"))
        assertTrue(text.contains("A1 [UNVERIFIED] report written"))
        assertTrue(text.contains("c49"))
        assertTrue(text.contains("50 calls"))
        assertTrue(text.length < 4_000)
        r.state.goal = "new goal after steering"
        assertTrue(r.state.render().contains("new goal after steering"))
    }

    // 6. Worker claims success without evidence / forged report.
    @Test fun `coordinator rejects worker success without evidence and tampered digest`() {
        val none = WorkerEvidenceReview.review(null, "w1", emptyList(), requireExecution = true, legacyToolEvidencePresent = false)
        assertFalse(none.accepted)
        val wid = RunIdentity("run-w", "w1")
        val r = Rig(wid, action)
        r.run("c1", "read_file", mapOf("path" to "/p/A.kt"), "x")
        val published = r.finish("status" to "completed", "summary" to "ok", "evidence" to "c1") as GateResult.Publish
        assertTrue(WorkerEvidenceReview.review(published.report, "w1", emptyList(), true, true).accepted)
        assertFalse(WorkerEvidenceReview.review(published.report, "w2", emptyList(), true, true).accepted)
        val forged = published.report.copy(evidence = published.report.evidence + published.report.evidence.first().copy(seq = 99, callId = "fake"))
        assertFalse(WorkerEvidenceReview.review(forged, "w1", emptyList(), true, true).accepted)
        val changedTarget = published.report.copy(evidence = published.report.evidence.map { it.copy(target = "path=/p/Other.kt") })
        assertFalse(WorkerEvidenceReview.review(changedTarget, "w1", emptyList(), true, true).accepted)
        val changedKind = published.report.copy(evidence = published.report.evidence.map { it.copy(kind = EvidenceKinds.Kind.MUTATION) })
        assertFalse(WorkerEvidenceReview.review(changedKind, "w1", emptyList(), true, true).accepted)
        val changedWorker = published.report.copy(evidence = published.report.evidence.map { it.copy(workerId = "other-worker") })
        assertFalse(WorkerEvidenceReview.review(changedWorker, "w1", emptyList(), true, true).accepted)
        val blocked = r.finish("status" to "blocked", "summary" to "no permission") as GateResult.Publish
        assertFalse(WorkerEvidenceReview.review(blocked.report, "w1", emptyList(), true, true).accepted)
    }

    // 7. Awaiting approval.
    @Test fun `awaiting approval is an honest terminal that is never reported as verified success`() {
        val r = rig()
        val res = r.finish("status" to "awaiting_approval", "summary" to "Need your OK to delete 3 files") as GateResult.Publish
        assertEquals(FinishStatus.AWAITING_APPROVAL, res.status)
        assertFalse(res.report.verified)
        assertTrue(res.text.startsWith("[Awaiting approval]"))
        assertTrue(r.finish("status" to "blocked", "summary" to "") is GateResult.Reject)
    }

    // 8. Pure chat never enters forced finalization.
    @Test fun `pure chat and toolless runs are not bound by the protocol`() {
        val chat = signals(conversationalIntent = 0.9f)
        assertFalse(FinalizationPolicy.decide(chat, isWorker = false, toolsAvailable = true).enforce)
        val act = signals(executionIntent = 0.7f, mutationIntent = 0.5f)
        assertFalse(FinalizationPolicy.decide(act, isWorker = false, toolsAvailable = false).enforce)
        val bound = FinalizationPolicy.decide(act, isWorker = false, toolsAvailable = true)
        assertTrue(bound.enforce && bound.requireExecution)
        val soft = FinalizationPolicy.decide(signals(executionIntent = 0.4f), false, true)
        assertTrue(soft.enforce && !soft.requireExecution)
        assertTrue(FinalizationPolicy.decide(chat, isWorker = true, toolsAvailable = true).enforce)
    }

    @Test fun `ordinary agent infers implementation requirements from mutation intent`() {
        val mutation = signals(executionIntent = 0.72f, mutationIntent = 0.48f)
        val runtime = FinishRuntime.create(null, "fix Foo.kt", mutation, false, true, "test-model", false)
        assertEquals(TaskKind.IMPLEMENTATION, runtime.identity.taskKind)
        assertTrue(FinalizationPolicy.decide(mutation, false, true, runtime.identity).requireAction)

        val inspection = signals(executionIntent = 0.72f)
        val inspectRuntime = FinishRuntime.create(null, "inspect Foo.kt", inspection, false, true, "test-model", false)
        assertEquals(TaskKind.GENERAL, inspectRuntime.identity.taskKind)
        assertFalse(FinalizationPolicy.decide(inspection, false, true, inspectRuntime.identity).requireAction)
    }

    @Test fun `shell file writes are tracked and require same path read back`() {
        val r = rig(policy = FinalizationPolicy(true, true, requireAction = true))
        r.run("c1", "run_terminal", mapOf("command" to "printf 'fixed' > /p/Foo.kt"), "written")
        assertTrue(r.state.nextAction().contains("/p/Foo.kt"))
        assertTrue(r.finish("status" to "completed", "summary" to "fixed", "evidence" to "c1") is GateResult.Reject)
        r.run("c2", "read_file", mapOf("path" to "/p/Other.kt"), "unrelated")
        assertTrue(r.finish("status" to "completed", "summary" to "fixed", "evidence" to "c1,c2") is GateResult.Reject)
        r.run("c3", "read_file", mapOf("path" to "/p/Foo.kt"), "fixed")
        assertTrue(r.finish("status" to "completed", "summary" to "fixed", "evidence" to "c1,c3") is GateResult.Publish)
    }

    @Test fun `edits must be read back`() {
        val r = rig()
        r.run("c1", "patch_file_content", mapOf("path" to "/p/Foo.kt"), "patched")
        assertTrue(r.finish("status" to "completed", "summary" to "d", "evidence" to "c1") is GateResult.Reject)
        assertTrue(r.state.nextAction().contains("/p/Foo.kt"))
        r.run("c2", "read_file", mapOf("path" to "/p/Other.kt"), "unrelated")
        assertTrue(r.finish("status" to "completed", "summary" to "d", "evidence" to "c1") is GateResult.Reject)
        r.run("c3", "read_file", mapOf("path" to "/p/Foo.kt"), "patched content")
        assertTrue(r.finish("status" to "completed", "summary" to "d", "evidence" to "c1,c3") is GateResult.Publish)
    }

    @Test fun `tool call ids are stabilized without breaking provider ids`() {
        val ids = ToolCallIdentity("run-abcdef12")
        val out = ids.stabilize(listOf(
            ToolCall("provider-1", "a", emptyMap()), ToolCall("", "b", emptyMap()),
            ToolCall("provider-1", "c", emptyMap()), ToolCall("bad id/x", "d", emptyMap())
        ))
        assertEquals("provider-1", out[0].id)
        assertEquals(4, out.map { it.id }.distinct().size)
        assertTrue(out.drop(1).all { it.id != "" && it.id != "bad id/x" })
        assertEquals("provider-2", ids.stabilize(listOf(ToolCall("provider-2", "a", emptyMap())))[0].id)
    }

    @Test fun `parser accepts flat finish arguments`() {
        val req = FinishTool.parse(mapOf("status" to "Awaiting-Approval", "summary" to "s", "evidence" to "[c1], #3;c2", "criteria" to "A1 => c1, c2\n- extra thing | c3"))!!
        assertEquals(FinishStatus.AWAITING_APPROVAL, req.status)
        assertEquals(listOf("c1", "#3", "c2"), req.evidenceIds)
        assertEquals("A1", req.claims[0].key)
        assertEquals(listOf("c1", "c2"), req.claims[0].evidenceIds)
        assertEquals("", req.claims[1].key)
        assertNull(FinishTool.parse(mapOf("status" to "done!")))
    }

    @Test fun `legacy ledger usage is unchanged and tool choice is never forced on openai compatible`() {
        val legacy = RunEvidenceLedger()
        legacy.record(ToolCall("1", "read_file", mapOf("path" to "/a")), ToolCallResult("1", "read_file", "x"))
        assertTrue(legacy.render().startsWith("[RUN LEDGER"))
        assertEquals(ToolChoiceMode.AUTO, ToolChoicePolicy.choose(ApiPath.OPENAI_COMPATIBLE, true, true))
        assertEquals(ToolChoiceMode.AUTO, ToolChoicePolicy.choose(ApiPath.GEMINI_NATIVE, true, false))
        assertEquals(ToolChoiceMode.ANY, ToolChoicePolicy.choose(ApiPath.GEMINI_NATIVE, true, true))
        assertSame(ModelExecutionProfile.DEFAULT, ModelExecutionProfile.resolve("gemini-2.5-flash-lite", enabled = false))
        assertEquals(false, ModelExecutionProfile.resolve("gemini-2.5-flash-lite", true).selfReflection)
        assertNull(ModelExecutionProfile.resolve("gemini-2.5-pro", true).selfReflection)
    }

    @Test fun `implementation work needs a non-read action and review cross checks observed events`() {
        val wid = RunIdentity("run-impl", "w9", taskKind = TaskKind.IMPLEMENTATION)
        val policy = FinalizationPolicy(true, true, requireAction = true)
        val r = Rig(wid, policy)
        r.run("c1", "read_file", mapOf("path" to "/p/A.kt"), "x")
        val onlyReads = r.finish("status" to "completed", "summary" to "done", "evidence" to "c1")
        assertTrue(onlyReads is GateResult.Reject)
        r.run("c2", "patch_file_content", mapOf("path" to "/p/A.kt"), "patched")
        r.run("c3", "read_file", mapOf("path" to "/p/A.kt"), "patched content")
        val ok = r.finish("status" to "completed", "summary" to "done", "evidence" to "c2,c3") as GateResult.Publish
        assertTrue(ok.report.verified)
        val seen = mapOf("read_file" to 2, "patch_file_content" to 1)
        assertTrue(WorkerEvidenceReview.review(ok.report, "w9", emptyList(), true, true, "run-impl", true, seen).accepted)
        // The coordinator saw only one successful read: the report claims two, so it is not accepted.
        val fewer = WorkerEvidenceReview.review(ok.report, "w9", emptyList(), true, true, "run-impl", true, mapOf("read_file" to 1, "patch_file_content" to 1))
        assertFalse(fewer.accepted)
        assertFalse(WorkerEvidenceReview.review(ok.report, "w9", emptyList(), true, true, "run-other", true, seen).accepted)
    }

    @Test fun `analysis worker is never forced to modify and honest terminals are not retryable`() {
        val wid = RunIdentity("run-an", "w2", taskKind = TaskKind.ANALYSIS)
        val policy = FinalizationPolicy.decide(signals(), isWorker = true, toolsAvailable = true, identity = wid)
        assertTrue(policy.requireExecution)
        assertFalse(policy.requireAction)
        val r = Rig(wid, policy)
        r.run("c1", "read_file", mapOf("path" to "/p/A.kt"), "x")
        val done = r.finish("status" to "completed", "summary" to "findings", "evidence" to "c1") as GateResult.Publish
        assertTrue(WorkerEvidenceReview.review(done.report, "w2", emptyList(), true, true, "run-an", false, mapOf("read_file" to 1)).accepted)
        val wait = r.finish("status" to "awaiting_approval", "summary" to "need approval") as GateResult.Publish
        val review = WorkerEvidenceReview.review(wait.report, "w2", emptyList(), true, true)
        assertFalse(review.accepted)
        assertEquals(FinishStatus.AWAITING_APPROVAL, review.terminal)
        val inc = r.gate.incomplete("[Incomplete] stalled")
        assertNull(WorkerEvidenceReview.review(inc.report, "w2", emptyList(), true, true).terminal)
    }

    @Test fun `missing planner criteria never means automatic acceptance`() {
        val wid = RunIdentity("run-nc", "w3", taskKind = TaskKind.IMPLEMENTATION)
        val r = Rig(wid, FinalizationPolicy(true, true, requireAction = true), acceptance = emptyList())
        assertTrue(r.gate.implicit("All done.") is GateResult.Reject)                  // nothing ran
        r.run("c1", "read_file", mapOf("path" to "/p/A.kt"), "x")
        assertTrue(r.gate.implicit("All done.") is GateResult.Reject)                  // only reads
        r.run("c2", "run_terminal", mapOf("action" to "exec"), "ok")
        val accepted = r.gate.implicit("All done.") as GateResult.Publish              // real action exists
        assertTrue(accepted.report.stats.implicit)
        assertEquals(3, accepted.report.stats.rejections + 1)
    }
}
