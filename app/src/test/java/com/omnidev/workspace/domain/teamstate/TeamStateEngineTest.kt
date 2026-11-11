package com.omnidev.workspace.domain.teamstate

import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.domain.engine.*
import com.omnidev.workspace.domain.engine.completion.*
import com.omnidev.workspace.domain.engine.teamcompletion.*
import com.omnidev.workspace.domain.taskstate.TaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TeamStateEngineTest {
    /** JSON on every read/write models recreation without sharing mutable record identity. Same domain engine as Room. */
    private class Backend : TeamStateBackend {
        val mutex = Mutex()
        val data = mutableMapOf<String, String>()
        override suspend fun read(id: String) = mutex.withLock { data[id]?.let { Json.decodeFromString<TeamRecord>(it) } }
        override suspend fun change(id: String, transform: (TeamRecord?) -> TeamRecord) = mutex.withLock {
            transform(data[id]?.let { Json.decodeFromString<TeamRecord>(it) }).also { data[id] = Json.encodeToString(it) }
        }
        override suspend fun forSession(sessionId: Long, limit: Int) = mutex.withLock {
            data.values.map { Json.decodeFromString<TeamRecord>(it) }.filter { it.sessionId == sessionId }.take(limit)
        }
    }
    private class Rig {
        val backend = Backend()
        var clock = 1_000L
        var store = TeamStateEngine(backend) { clock }
        val a = SwarmTask("inspect", "Read source")
        val b = SwarmTask("verify", "Read test result", dependencies = listOf(a.id))
        val tasks = listOf(a, b)
        suspend fun start() { store.create("team", 7, "Original objective", "/tmp", "owner"); store.savePlan("team", "owner", 0, "Original objective", tasks) }
        fun recreate() { clock += TeamStateEngine.LEASE_MS + 1; store = TeamStateEngine(backend) { clock } }
        suspend fun begin(t: SwarmTask = a, attempt: Int = 1, owner: String = "owner"): WorkerAssignment =
            WorkerAssignment("team", 0, t, attempt).also { store.begin("team", owner, it, 12_000) }
        suspend fun complete(a: WorkerAssignment, owner: String = "owner", tool: String = "read_file", args: Map<String, String> = mapOf("path" to "/tmp/Source.kt"), summary: String = "Read verified source") {
            val observer = WorkerCompletionObserver(a)
            val ledger = RunEvidenceLedger(a.identity.runId, a.task.id)
            val call = ToolCall("read", tool, args)
            observer.started(AgentEvent.ToolExecution(tool, args, 1, call.id))
            store.beforeTool("team", owner, a, call)
            ledger.record(call, ToolCallResult(call.id, tool, "class Source; test passed"))
            val event = AgentEvent.ToolResult(tool, "class Source; test passed", false, 1, ledger.snapshot().last())
            observer.observed(event); store.observed("team", owner, a, event)
            val policy = FinalizationPolicy(true, true, requireAction = a.kind == TaskKind.IMPLEMENTATION)
            val gate = FinishGate(ledger, RunState(a.task.description, ledger, emptyList(), true), policy, a.identity)
            val report = (gate.implicit(summary) as GateResult.Publish).report
            val receipt = observer.review(report, "Read verified source")
            assertTrue(receipt.reason, receipt.accepted)
            store.finishWorker("team", owner, a, observer, report, "Read verified source", receipt, null)
        }
    }

    @Test fun `plan assignments receipts and original objective survive serialization and recreation`() = runTest {
        val r = Rig(); r.start(); val a = r.begin(); r.complete(a); r.recreate()
        val saved = r.store.load("team")!!
        assertEquals("Original objective", saved.originalObjective)
        assertEquals(r.tasks, saved.plan!!.tasks)
        assertEquals(a, saved.attempts.single().assignment)
        assertTrue(r.store.accepted(saved, "inspect"))
        assertFalse(r.store.accepted(saved, "verify"))
        val resumed = r.store.claim("team", 7, "next")
        assertEquals("team", resumed.id)
        assertEquals(1, resumed.generation)
        assertTrue(r.store.summaries(7).single().workers.any { it.id == "inspect" && it.status == "COMPLETED" })
    }
    @Test fun `operational report prose is redacted without changing the evidence identity`() = runTest {
        val r = Rig(); r.start(); val a = r.begin()
        r.complete(a, summary = "Read source. password=never-persist-this")
        assertFalse(r.backend.data.getValue("team").contains("never-persist-this"))
        assertTrue(r.store.accepted(r.store.load("team")!!, "inspect"))
        assertEquals(a.identity.runId, r.store.checkpoints(r.store.load("team")!!).single().report!!.runId)
    }
    @Test fun `unverified dependencies never unlock workers`() = runTest {
        val r = Rig(); r.start()
        assertTrue(runCatching { r.begin(r.b) }.isFailure)
        val a = r.begin()
        r.store.beforeTool("team", "owner", a, ToolCall("read", "read_file", emptyMap()))
        r.recreate(); r.store.claim("team", 7, "next")
        assertTrue(runCatching { r.begin(r.b, owner = "next") }.isFailure)
    }
    @Test fun `verified predecessor survives while successor starts with a distinct attempt identity`() = runTest {
        val r = Rig(); r.start(); r.complete(r.begin()); val b = r.begin(r.b); r.recreate()
        r.store.claim("team", 7, "next")
        val newer = r.begin(r.b, 2, "next")
        assertNotEquals(b.identity.runId, newer.identity.runId)
        assertTrue(runCatching { r.store.beforeTool("team", "next", b, ToolCall("stale", "write_file", emptyMap())) }.isFailure)
        assertTrue(r.store.accepted(r.store.load("team")!!, r.a.id))
    }
    @Test fun `concurrent resume requests have one winner and stale owners cannot publish`() = runTest {
        val r = Rig(); r.start(); r.recreate()
        val outcomes = (1..20).map { i -> async(Dispatchers.Default) { runCatching { r.store.claim("team", 7, "owner-$i") }.isSuccess } }.awaitAll()
        assertEquals(1, outcomes.count { it })
        assertTrue(runCatching { r.store.savePlan("team", "owner", 1, "stale", r.tasks) }.isFailure)
    }
    @Test fun `unexpired lease and foreign session reject resume without changing ownership`() = runTest {
        val r = Rig(); r.start()
        assertTrue(runCatching { r.store.claim("team", 7, "new") }.isFailure)
        r.recreate()
        assertTrue(runCatching { r.store.claim("team", 8, "new") }.isFailure)
        assertEquals("owner", r.store.load("team")!!.owner)
    }
    @Test fun `repeated restarts and lease release are idempotent`() = runTest {
        val r = Rig(); r.start(); r.store.release("team", "owner"); r.store.release("team", "owner")
        val first = r.store.load("team")!!
        r.recreate(); assertEquals(first, r.store.load("team"))
        assertEquals(1, r.store.load("team")!!.plans.size)
        assertEquals(TaskStatus.INTERRUPTED, first.status)
    }
    @Test fun `UNKNOWN mutation prevents blind retry even without a provider result`() = runTest {
        val r = Rig(); r.start(); val a = r.begin()
        r.store.beforeTool("team", "owner", a, ToolCall("write", "write_file", mapOf("path" to "/tmp/Source.kt")))
        r.recreate()
        assertFalse(r.store.summaries(7).single().canResume)
        assertTrue(runCatching { r.store.claim("team", 7, "next") }.isFailure)
        assertEquals(listOf("write"), r.store.summaries(7).single().workers.first().unresolvedCalls)
    }
    @Test fun `read-only result cannot reconcile an earlier mutation`() = runTest {
        val r = Rig(); r.start(); val a = r.begin()
        r.store.beforeTool("team", "owner", a, ToolCall("write", "write_file", emptyMap()))
        r.store.beforeTool("team", "owner", a, ToolCall("read", "read_file", emptyMap()))
        val e = EvidenceRef("read", 1, "read_file", "", RunEvidenceLedger.Outcome.OK, EvidenceKinds.Kind.READ, a.identity.runId, a.task.id)
        r.store.observed("team", "owner", a, AgentEvent.ToolResult("read_file", "file exists", false, 1, e))
        r.recreate(); assertFalse(r.store.summaries(7).single().canResume)
    }
    @Test fun `exact not-executed reconciliation enables new identity but never claims completion`() = runTest {
        val r = Rig(); r.start(); val a = r.begin()
        r.store.beforeTool("team", "owner", a, ToolCall("write", "write_file", emptyMap()))
        r.recreate()
        assertTrue(runCatching { r.store.reconcileNotExecuted("team", 7, a.identity.runId, "foreign", "Inspected exact operation: did not execute") }.isFailure)
        r.store.reconcileNotExecuted("team", 7, a.identity.runId, "write", "Inspected exact operation: did not execute")
        assertTrue(r.store.summaries(7).single().canResume)
        r.store.claim("team", 7, "next"); val newer = r.begin(attempt = 2, owner = "next")
        assertNotEquals(a.identity.runId, newer.identity.runId)
        assertFalse(r.store.accepted(r.store.load("team")!!, "inspect"))
    }
    @Test fun `successful unverified mutation stays non-replayable and cannot be declared not executed`() = runTest {
        val r = Rig(); r.start(); val a = r.begin()
        r.store.beforeTool("team", "owner", a, ToolCall("write", "write_file", emptyMap()))
        val e = EvidenceRef("write", 1, "write_file", "", RunEvidenceLedger.Outcome.OK, EvidenceKinds.Kind.MUTATION, a.identity.runId, a.task.id)
        r.store.observed("team", "owner", a, AgentEvent.ToolResult("write_file", "written", false, 1, e))
        r.recreate()
        assertTrue(runCatching { r.store.reconcileNotExecuted("team", 7, a.identity.runId, "write", "Inspected exact operation: did not execute") }.isFailure)
        assertFalse(r.store.summaries(7).single().canResume)
    }
    @Test fun `approvals remain barriers on recreation`() = runTest {
        val r = Rig(); r.start(); val a = r.begin()
        r.store.observed("team", "owner", a, AgentEvent.ToolResult("read_file", "permission", true, 1,
            EvidenceRef("denied", 1, "read_file", "", RunEvidenceLedger.Outcome.ERROR, EvidenceKinds.Kind.READ, a.identity.runId, a.task.id), "CONFIRMATION_REQUIRED"))
        r.recreate(); assertFalse(r.store.summaries(7).single().canResume)
    }
    @Test fun `explicit intervention review does not grant permissions or mark a worker complete`() = runTest {
        val r = Rig(); r.start(); val a = r.begin()
        r.store.observed("team", "owner", a, AgentEvent.ToolResult("read_file", "permission", true, 1,
            EvidenceRef("denied", 1, "read_file", "", RunEvidenceLedger.Outcome.ERROR, EvidenceKinds.Kind.READ, a.identity.runId, a.task.id), "CONFIRMATION_REQUIRED"))
        r.recreate(); r.store.reviewIntervention("team", 7, a.identity.runId)
        assertTrue(r.store.summaries(7).single().canResume)
        assertFalse(r.store.accepted(r.store.load("team")!!, "inspect"))
        r.store.claim("team", 7, "next")
        assertEquals(2, r.begin(attempt = 2, owner = "next").attempt)
    }
    @Test fun `completed workers do not rerun and synthesis cannot complete missing successors`() = runTest {
        val r = Rig(); r.start(); r.complete(r.begin())
        assertTrue(runCatching { r.begin(attempt = 2) }.isFailure)
        val protocol = TeamCompletionProtocol("team", 0, r.tasks)
        assertTrue(protocol.restore(r.store.load("team")!!.attempts.single().checkpoint!!))
        val report = protocol.finish(emptySet(), setOf("verify"))
        assertFalse(report.verified)
        r.store.finish("team", "owner", report)
        assertNotEquals(TaskStatus.COMPLETED, r.store.load("team")!!.status)
    }
    @Test fun `foreign and mismatched attempt receipts fail durable restoration`() = runTest {
        val r = Rig(); r.start(); r.complete(r.begin())
        val cp = r.store.load("team")!!.attempts.single().checkpoint!!
        assertFalse(TeamCompletionProtocol("foreign", 0, r.tasks).restore(cp))
        assertFalse(TeamCompletionProtocol("team", 1, r.tasks).restore(cp))
        assertFalse(TeamCompletionProtocol("team", 0, r.tasks).restore(cp.copy(assignment = cp.assignment.copy(attempt = 2))))
        assertFalse(TeamCompletionProtocol("team", 0, r.tasks).restore(cp.copy(output = "Invented result")))
        assertFalse(TeamCompletionProtocol("team", 0, r.tasks).restore(cp.copy(report = null)))
    }
    @Test fun `corrupt stored receipt cannot unlock dependencies or produce a green recovery summary`() = runTest {
        val r = Rig(); r.start(); r.complete(r.begin())
        val saved = r.store.load("team")!!
        val broken = saved.copy(status = TaskStatus.COMPLETED, attempts = saved.attempts.map {
            it.copy(checkpoint = it.checkpoint!!.copy(report = null))
        })
        r.backend.data["team"] = Json.encodeToString(broken)
        assertEquals("BLOCKED", r.store.summaries(7).single().status)
        assertFalse(r.store.summaries(7).single().canResume)
        r.recreate()
        assertTrue(runCatching { r.store.claim("team", 7, "next") }.isFailure)
    }
    @Test fun `plan revisions preserve immutable history and cannot conceal unresolved effects`() = runTest {
        val r = Rig(); r.start(); r.complete(r.begin())
        r.store.savePlan("team", "owner", 1, "Updated objective", listOf(r.a))
        r.store.savePlan("team", "owner", 1, "Updated objective", listOf(r.a))
        val saved = r.store.load("team")!!
        assertEquals(2, saved.plans.size); assertEquals("Original objective", saved.originalObjective)
        assertEquals("Updated objective", saved.objective)
        assertEquals(0L, saved.attempts.single().assignment.revision)
        assertFalse(r.store.accepted(saved, "inspect")) // historical proof cannot impersonate a new revision
    }
    @Test fun `compatible revision retains verified proof under its original attempt identity`() = runTest {
        val r = Rig(); r.start(); r.complete(r.begin())
        r.store.savePlan("team", "owner", 1, "Original objective", r.tasks + SwarmTask("extra", "Read extra source"))
        val saved = r.store.load("team")!!
        assertEquals(mapOf("inspect" to 0L), saved.plan!!.carriedReceipts)
        assertTrue(r.store.accepted(saved, "inspect"))
        assertEquals(0L, r.store.checkpoints(saved).single().assignment.revision)
        val changed = r.tasks.map { if (it.id == "inspect") it.copy(acceptance = listOf("new exact requirement")) else it }
        r.store.savePlan("team", "owner", 2, "Original objective", changed)
        assertFalse(r.store.accepted(r.store.load("team")!!, "inspect"))
    }
    @Test fun `plan redirect cannot erase uncertain side effects`() = runTest {
        val r = Rig(); r.start(); val a = r.begin()
        r.store.beforeTool("team", "owner", a, ToolCall("write", "write_file", emptyMap()))
        assertTrue(runCatching { r.store.savePlan("team", "owner", 1, "Updated", listOf(r.a)) }.isFailure)
        assertEquals(1, r.store.load("team")!!.plans.size)
    }
    @Test fun `token reservations persist across restart and are bounded before execution`() = runTest {
        val r = Rig(); r.start(); r.store.reserve("team", "owner", TeamStateEngine.TOKEN_LIMIT - 1)
        assertTrue(runCatching { r.store.reserve("team", "owner", 2) }.isFailure)
        r.recreate(); assertFalse(r.store.summaries(7).single().canResume)
        assertEquals(TeamStateEngine.TOKEN_LIMIT - 1, r.store.load("team")!!.reservedTokens)
    }
    @Test fun `attempt and operation lists are bounded`() = runTest {
        val r = Rig(); r.start()
        repeat(TeamStateEngine.MAX_ATTEMPTS) { r.begin(attempt = it + 1) }
        assertTrue(runCatching { r.begin(attempt = TeamStateEngine.MAX_ATTEMPTS + 1) }.isFailure)
        val a = r.store.load("team")!!.attempts.last().assignment
        repeat(200) { r.store.beforeTool("team", "owner", a, ToolCall("read-$it", "read_file", emptyMap())) }
        assertTrue(runCatching { r.store.beforeTool("team", "owner", a, ToolCall("read-201", "read_file", emptyMap())) }.isFailure)
        assertEquals(200, r.store.load("team")!!.attempts.last().operations.size)
    }
    @Test fun `failure categories do not spend model tokens to rediscover known backend outages`() {
        val cases = mapOf("Unable to resolve host: no address associated" to TeamFailureCategory.DNS,
            "HTTP 429 rate limit" to TeamFailureCategory.RATE_LIMIT, "401 unauthorized" to TeamFailureCategory.AUTHENTICATION,
            "context length exceeded" to TeamFailureCategory.CONTEXT, "invalid_tool schema" to TeamFailureCategory.SCHEMA,
            "fatal: not a git repository" to TeamFailureCategory.CHECKOUT, "permission denied" to TeamFailureCategory.PERMISSION,
            "backend unavailable" to TeamFailureCategory.BACKEND)
        cases.forEach { (message, category) -> assertEquals(category, TeamFailurePolicy.classify(message)) }
        assertTrue(TeamFailurePolicy.pauseWithoutWorkerRetry("Unable to resolve host"))
        assertFalse(TeamFailurePolicy.pauseWithoutWorkerRetry("invalid argument"))
    }
}
