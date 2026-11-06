package com.omnidev.workspace.domain.engine.teamcompletion

import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.domain.engine.*
import com.omnidev.workspace.domain.engine.completion.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class TeamCompletionProtocolTest {
    private fun task(id: String = "inspect", deps: List<String> = emptyList()) = SwarmTask(id, "Inspect source $id", dependencies = deps)
    private class Rig(val a: WorkerAssignment) {
        val observer = WorkerCompletionObserver(a)
        val ledger = RunEvidenceLedger(a.identity.runId, a.task.id)
        val policy = FinalizationPolicy(true, !a.task.toolFree, requireAction = a.kind == TaskKind.IMPLEMENTATION && !a.task.toolFree)
        val state = RunState(a.task.description, ledger, a.task.acceptance, policy.requireExecution)
        val gate = FinishGate(ledger, state, policy, a.identity)
        fun result(id: String = "read", tool: String = "read_file", output: String = "class Source", error: Boolean = false,
            args: Map<String, String> = mapOf("path" to "/p/Source.kt"), classification: String? = null): EvidenceRef {
            observer.started(AgentEvent.ToolExecution(tool, args, 1, id))
            ledger.record(ToolCall(id, tool, args), ToolCallResult(id, tool, output, error))
            return ledger.snapshot().last().also { observer.observed(AgentEvent.ToolResult(tool, output, error, 1, it, classification)) }
        }
        fun report(status: String = "completed"): CompletionReport = (gate.explicit(ToolCall("finish", FinishTool.NAME,
            mapOf("status" to status, "summary" to "Observed source", "evidence" to ledger.snapshot().joinToString(",") { it.callId })), "") as GateResult.Publish).report
        fun receipt() = observer.review(report(), "Observed source")
    }

    @Test fun `exact P0 receipt unlocks dependencies and duplicates are idempotent`() {
        val a = task("a"); val b = task("b", listOf("a")); val p = TeamCompletionProtocol("team", 0, listOf(a, b))
        assertFalse(p.ready("b"))
        assertThrows(IllegalArgumentException::class.java) { p.begin(b) }
        val r = Rig(p.begin(a)); r.result(); val receipt = r.receipt()
        assertTrue(receipt.accepted)
        assertTrue(p.accept(receipt, "Observed source")); assertTrue(p.accept(receipt, "Observed source"))
        assertTrue(p.ready("b")); assertFalse(p.finish(emptySet(), emptySet()).verified)
        val rb = Rig(p.begin(b)); rb.result(); assertTrue(p.accept(rb.receipt(), "Observed source"))
        assertTrue(p.finish(emptySet(), emptySet()).verified)
        assertEquals(2, p.finish(emptySet(), emptySet()).completed)
    }
    @Test fun `missing legacy report never passes even with actual successful tools`() {
        val r = Rig(WorkerAssignment("team", 0, task(), 1)); r.result()
        assertFalse(r.observer.review(null, "I finished").accepted)
        assertFalse(WorkerEvidenceReview.review(null, "inspect", emptyList(), true, true).accepted)
        assertFalse(WorkerEvidenceReview.review(null, "explain", emptyList(), false, false).accepted)
    }
    @Test fun `discovery is retained for audit but cannot prove execution through P0`() {
        val r = Rig(WorkerAssignment("team", 0, task(), 1))
        r.result("discover", "discover_tools", "Loaded schemas", args = mapOf("query" to "source"))
        assertEquals(1, r.ledger.snapshot().size)
        assertTrue(r.gate.implicit("Finished inspection") is GateResult.Reject)
    }
    @Test fun `same tool counts cannot launder another call target sequence or kind`() {
        val r = Rig(WorkerAssignment("team", 0, task(), 1)); r.result(); val report = r.report()
        val e = report.evidence.single()
        for (changed in listOf(e.copy(callId = "fake"), e.copy(target = "path=/p/Other.kt"), e.copy(seq = 3),
            e.copy(kind = EvidenceKinds.Kind.ACTION), e.copy(outcome = RunEvidenceLedger.Outcome.ERROR))) {
            val forged = report.copy(evidence = listOf(changed), ledgerDigest = CompletionReport.digest(listOf(changed)))
            assertFalse(r.observer.review(forged, "Observed source").accepted)
        }
        assertTrue(r.observer.review(report, "Observed source").accepted)
        val unverified = r.observer.review(report.copy(verified = false), "Observed source")
        assertFalse(unverified.accepted)
        assertEquals(FinishStatus.INCOMPLETE, unverified.status)
    }
    @Test fun `foreign run worker revision and attempt are rejected`() {
        val t = task(); val p = TeamCompletionProtocol("team", 1, listOf(t))
        val first = Rig(p.begin(t)); first.result(); val old = first.receipt()
        val latest = Rig(p.begin(t)); latest.result(); val current = latest.receipt()
        assertFalse(p.accept(old, "Observed source"))
        assertFalse(TeamCompletionProtocol("other", 1, listOf(t)).accept(current, "Observed source"))
        assertFalse(TeamCompletionProtocol("team", 2, listOf(t)).accept(current, "Observed source"))
        assertFalse(latest.observer.review(currentReport(first), "Observed source").accepted)
        assertTrue(p.accept(current, "Observed source"))
    }
    private fun currentReport(r: Rig) = r.report()

    @Test fun `duplicate or unsettled observations are not completion evidence`() {
        val r = Rig(WorkerAssignment("team", 0, task(), 1)); val e = r.result(); val report = r.report()
        r.observer.observed(AgentEvent.ToolResult("read_file", "class Source", false, 1, e))
        assertFalse(r.observer.review(report, "Observed source").accepted)
        val pending = Rig(WorkerAssignment("team", 0, task(), 1)); pending.result()
        pending.observer.started(AgentEvent.ToolExecution("patch_file_content", mapOf("path" to "/p/Source.kt"), 1, "pending"))
        assertTrue(pending.observer.unsafeToRetry)
        assertFalse(pending.observer.review(pending.report(), "Observed source").accepted)
    }
    @Test fun `successful action without independent verification cannot complete and cannot be blindly retried`() {
        val t = task().copy(parallelSafe = false); val r = Rig(WorkerAssignment("team", 0, t, 1))
        r.result("write", "patch_file_content", "accepted")
        assertTrue(r.observer.unsafeToRetry)
        assertTrue(r.gate.implicit("Finished") is GateResult.Reject)
    }
    @Test fun `failed side effect remains unsafe while exact policy denial allows serial recovery`() {
        val r = Rig(WorkerAssignment("team", 0, task(), 1))
        r.result("write", "patch_file_content", "uncertain", true)
        assertTrue(r.observer.unsafeToRetry)
        val denied = Rig(WorkerAssignment("team", 0, task(), 1))
        denied.result("write", "patch_file_content", "READ_ONLY_WORKER: denied", true, classification = "TOOL_POLICY_DENIED")
        assertFalse(denied.observer.unsafeToRetry)
    }
    @Test fun `runtime approval metadata stops retries even without a terminal P0 report`() {
        val r = Rig(WorkerAssignment("team", 0, task(), 1))
        r.result("pending", "patch_file_content", "Review required", true, classification = "USER_ACTION_REQUIRED")
        assertTrue(r.observer.unsafeToRetry)
        val receipt = r.observer.review(null, "Waiting for user")
        assertFalse(receipt.accepted); assertEquals(FinishStatus.AWAITING_APPROVAL, receipt.status)
    }
    @Test fun `changed output cannot reuse an accepted receipt`() {
        val t = task(); val p = TeamCompletionProtocol("team", 0, listOf(t)); val r = Rig(p.begin(t)); r.result()
        val receipt = r.receipt()
        assertFalse(p.accept(receipt, "Invented output")); assertFalse(p.finish(emptySet(), emptySet()).verified)
        assertTrue(p.accept(receipt, "Observed source"))
    }
    @Test fun `approval blocked failed and skipped states cannot become team success`() {
        for (status in listOf("awaiting_approval", "blocked", "failed", "incomplete")) {
            val t = task(); val p = TeamCompletionProtocol("team", 0, listOf(t)); val r = Rig(p.begin(t))
            val receipt = r.observer.review(r.report(status), "Stopped")
            assertFalse(receipt.accepted); assertTrue(p.accept(receipt, "Stopped"))
            val report = p.finish(setOf(t.id), emptySet())
            assertFalse(report.verified); assertEquals("Interrupted", report.sessionStatus)
            assertEquals(FinishStatus.parse(status), report.status)
        }
        assertFalse(TeamCompletionProtocol("team", 0, listOf(task())).finish(emptySet(), setOf("inspect")).verified)
    }
    @Test fun `tool free work still requires a P0 report and cannot hide execution objectives`() {
        val t = SwarmTask("explain", "Explain Kotlin", toolFree = true)
        val r = Rig(WorkerAssignment("team", 0, t, 1))
        assertTrue(r.receipt().accepted)
        assertTrue(TeamCompletionProtocol.toolFreeAllowed("Explain Kotlin"))
        assertFalse(TeamCompletionProtocol.toolFreeAllowed("Edit source.kt and verify the change"))
        assertFalse(TeamCompletionProtocol.toolFreeAllowed("Research current Android documentation"))
        assertFalse(TeamCompletionProtocol.toolFreeAllowed("Read file"))
        assertFalse(TeamCompletionProtocol.toolFreeAllowed("Read file?"))
        assertFalse(TeamCompletionProtocol.toolFreeAllowed("Inspect source?"))
        assertFalse(TeamCompletionProtocol.toolFreeAllowed("Inspect repository source"))
        assertFalse(TeamCompletionProtocol.toolFreeAllowed("Verify source outputs"))
        assertTrue(TeamCompletionProtocol.toolFreeAllowed("Summarize the supplied context"))
    }
    @Test fun `serialized research is analysis while runtime mutation promotion requires action`() {
        val read = SwarmTask("research", "Research Android documentation", parallelSafe = false, needsConnectedTools = true)
        val a = TeamCompletionProtocol("team", 0, listOf(read)).begin(read)
        assertEquals(TaskKind.ANALYSIS, a.kind)
        val t = task(); val p = TeamCompletionProtocol("team", 0, listOf(t))
        assertEquals(TaskKind.IMPLEMENTATION, p.begin(t.copy(parallelSafe = false)).kind)
    }
    @Test fun `planner cannot remove original implementation obligation with reasoning tasks`() {
        val t = SwarmTask("explain", "Explain Kotlin", toolFree = true)
        val p = TeamCompletionProtocol("team", 0, listOf(t), requireAction = true)
        val r = Rig(p.begin(t)); assertTrue(p.accept(r.receipt(), "Observed source"))
        val report = p.finish(emptySet(), emptySet())
        assertFalse(report.verified)
        assertTrue(report.problems.contains("ORIGINAL_OBJECTIVE_REQUIRES_VERIFIED_ACTION"))
    }
    @Test fun `reports contain operational digests without user output paths or arguments`() {
        val t = task(); val p = TeamCompletionProtocol("team", 0, listOf(t)); val r = Rig(p.begin(t)); r.result()
        p.accept(r.receipt(), "Observed source"); val report = p.finish(emptySet(), emptySet())
        assertFalse(report.toString().contains("/p/Source.kt")); assertFalse(report.toString().contains("class Source"))
        assertFalse(report.render().contains("Observed source")); assertEquals(64, report.digest.length)
    }
    @Test fun `normalization preserves every merged acceptance contract`() {
        val a = task().copy(acceptance = listOf("Inspect source text"), toolFree = true)
        val b = a.copy(id = "duplicate", acceptance = listOf("Inspect related definitions"), toolFree = false)
        val merged = TeamExecutionPolicy.normalizePlan(listOf(a, b)).tasks.single()
        assertEquals(setOf("Inspect source text", "Inspect related definitions"), merged.acceptance.toSet())
        assertFalse(merged.toolFree)
    }
    @Test fun `invalid dependency manifests are rejected before any attempt starts`() {
        for (tasks in listOf(listOf(task(), task()), listOf(task("a", listOf("missing"))),
            listOf(task("a", listOf("a"))), listOf(task("a", listOf("b")), task("b", listOf("a"))))) {
            assertThrows(IllegalArgumentException::class.java) { TeamCompletionProtocol("team", 0, tasks) }
        }
    }
    @Test fun `seeded dependency graphs accept only dependency ready receipts`() {
        val random = Random(5100)
        repeat(100) {
            val ts = (0 until 6).map { i -> task("t$i", (0 until i).filter { random.nextBoolean() }.map { "t$it" }) }
            val p = TeamCompletionProtocol("team", it.toLong(), ts)
            for (t in ts) { assertTrue(p.ready(t.id)); val r = Rig(p.begin(t)); r.result(); assertTrue(p.accept(r.receipt(), "Observed source")) }
            assertTrue(p.finish(emptySet(), emptySet()).verified)
        }
    }
}
