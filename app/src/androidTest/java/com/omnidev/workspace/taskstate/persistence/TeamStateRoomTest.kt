package com.omnidev.workspace.taskstate.persistence

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.db.entities.ChatSessionEntity
import com.omnidev.workspace.data.repository.RoomTeamStateBackend
import com.omnidev.workspace.domain.engine.SwarmTask
import com.omnidev.workspace.domain.engine.teamcompletion.WorkerAssignment
import com.omnidev.workspace.domain.teamstate.TeamStateEngine
import com.omnidev.workspace.domain.engine.*
import com.omnidev.workspace.domain.engine.completion.*
import com.omnidev.workspace.domain.engine.teamcompletion.*
import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.model.ToolCallResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class TeamStateRoomTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val filename = "team-state-regression.db"
    @After fun cleanup() { context.deleteDatabase(filename) }

    @Test fun planAttemptsUnresolvedMutationsAndImmutableObjectiveSurviveDatabaseReopen() = runBlocking {
        var clock = 100L
        var db = Room.databaseBuilder(context, OmniDevDatabase::class.java, filename).build()
        val store = TeamStateEngine(RoomTeamStateBackend(db)) { clock }
        val task = SwarmTask("edit", "Write source", parallelSafe = false)
        store.create("team", 42, "Original user request", "/tmp", "owner")
        store.savePlan("team", "owner", 0, "Original user request", listOf(task))
        val a = WorkerAssignment("team", 0, task, 1)
        store.begin("team", "owner", a, 12_000)
        store.beforeTool("team", "owner", a, ToolCall("write", "write_file", mapOf("password" to "must-not-persist")))
        db.close()
        clock += TeamStateEngine.LEASE_MS + 1
        db = Room.databaseBuilder(context, OmniDevDatabase::class.java, filename).build()
        try {
            val restored = TeamStateEngine(RoomTeamStateBackend(db)) { clock }
            val summary = restored.summaries(42).single()
            assertEquals("Original user request", summary.originalObjective)
            assertEquals("team", summary.id)
            assertFalse(summary.canResume)
            assertEquals(listOf("write"), summary.workers.single().unresolvedCalls)
            assertFalse(db.teamStateDao().get("team")!!.payload.contains("must-not-persist"))
            assertTrue(runCatching { restored.claim("team", 42, "new-owner") }.isFailure)
            restored.reconcileNotExecuted("team", 42, a.identity.runId, "write", "Verified exact destination: action was never executed")
            assertTrue(restored.summaries(42).single().canResume)
        } finally { db.close() }
    }
    @Test fun verifiedReceiptAndFinalPresentationSurviveDatabaseReopenWithTheOriginalAttempt() = runBlocking {
        var clock = 100L
        var db = Room.databaseBuilder(context, OmniDevDatabase::class.java, filename).build()
        val store = TeamStateEngine(RoomTeamStateBackend(db)) { clock }
        val task = SwarmTask("inspect", "Read source")
        store.create("team", 42, "Read source", "/tmp", "owner")
        store.savePlan("team", "owner", 0, "Read source", listOf(task))
        val a = WorkerAssignment("team", 0, task, 1)
        store.begin("team", "owner", a, 12_000)
        val observer = WorkerCompletionObserver(a)
        val ledger = RunEvidenceLedger(a.identity.runId, task.id)
        val call = ToolCall("read", "read_file", mapOf("path" to "/tmp/Source.kt"))
        observer.started(AgentEvent.ToolExecution(call.name, call.arguments, 1, call.id))
        store.beforeTool("team", "owner", a, call)
        ledger.record(call, ToolCallResult(call.id, call.name, "class Source"))
        val event = AgentEvent.ToolResult(call.name, "class Source", false, 1, ledger.snapshot().last())
        observer.observed(event); store.observed("team", "owner", a, event)
        val gate = FinishGate(ledger, RunState("Read source", ledger, emptyList(), true), FinalizationPolicy(true, true), a.identity)
        val report = (gate.implicit("Verified source") as GateResult.Publish).report
        val receipt = observer.review(report, "Verified source")
        assertTrue(receipt.accepted)
        store.finishWorker("team", "owner", a, observer, report, "Verified source", receipt, null)
        val protocol = TeamCompletionProtocol("team", 0, listOf(task))
        assertTrue(protocol.restore(store.load("team")!!.attempts.single().checkpoint!!))
        store.finish("team", "owner", protocol.finish(emptySet(), emptySet()), "Verified source result")
        db.close() // process dies after durable completion but before publishing the answer to chat
        clock += TeamStateEngine.LEASE_MS + 1
        db = Room.databaseBuilder(context, OmniDevDatabase::class.java, filename).build()
        try {
            val restored = TeamStateEngine(RoomTeamStateBackend(db)) { clock }
            val saved = restored.load("team")!!
            assertTrue(restored.accepted(saved, task.id))
            assertEquals(a.identity.runId, saved.attempts.single().assignment.identity.runId)
            assertEquals("Verified source result", restored.summaries(42).single().verifiedSummary)
            assertFalse(restored.summaries(42).single().canResume)
            assertTrue(runCatching { restored.claim("team", 42, "another") }.isFailure)
        } finally { db.close() }
    }
    @Test fun independentRoomEnginesCannotClaimTheSameTeamTwice() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OmniDevDatabase::class.java).build()
        try {
            val store = TeamStateEngine(RoomTeamStateBackend(db)) { 100L }
            store.create("team", 42, "Read source", "/tmp", "owner")
            store.release("team", "owner")
            val results = (1..20).map { i -> async(Dispatchers.IO) {
                runCatching { TeamStateEngine(RoomTeamStateBackend(db)) { 200L }.claim("team", 42, "claim-$i") }.isSuccess
            } }.awaitAll()
            assertEquals(1, results.count { it })
        } finally { db.close() }
    }
    @Test fun migrationFrom19KeepsUserDataAndCreatesIndexedTeamStorage() = runBlocking {
        var db = Room.databaseBuilder(context, OmniDevDatabase::class.java, filename).build()
        val session = db.chatSessionDao().insert(ChatSessionEntity(title = "Existing conversation"))
        db.openHelper.writableDatabase.execSQL("DROP TABLE team_runs")
        db.openHelper.writableDatabase.execSQL("PRAGMA user_version = 19")
        db.close()
        db = Room.databaseBuilder(context, OmniDevDatabase::class.java, filename).addMigrations(*OmniDevDatabase.ALL_MIGRATIONS).build()
        try {
            assertNotNull(db.chatSessionDao().getById(session))
            val store = TeamStateEngine(RoomTeamStateBackend(db)) { 100L }
            store.create("team", session, "Saved objective", "/tmp", "owner")
            assertEquals("Saved objective", store.summaries(session).single().originalObjective)
            assertEquals(21, db.openHelper.readableDatabase.version)
            for (version in 1 until 21) {
                var next = version
                while (next < 21) {
                    val migration = OmniDevDatabase.ALL_MIGRATIONS.single { it.startVersion == next }
                    assertTrue(migration.endVersion > next)
                    next = migration.endVersion
                }
                assertEquals(21, next)
            }
        } finally { db.close() }
    }
}
