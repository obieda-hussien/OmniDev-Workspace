package com.omnidev.workspace.taskstate.persistence

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.db.entities.ChatMessageEntity
import com.omnidev.workspace.data.db.entities.ChatSessionEntity
import com.omnidev.workspace.data.db.entities.ToolExecutionEntry
import com.omnidev.workspace.data.repository.roomTaskStateStore
import com.omnidev.workspace.domain.taskstate.Effect
import com.omnidev.workspace.domain.taskstate.FinishRecord
import com.omnidev.workspace.domain.taskstate.InvocationPatch
import com.omnidev.workspace.domain.taskstate.InvocationStatus
import com.omnidev.workspace.domain.taskstate.NewInvocation
import com.omnidev.workspace.domain.taskstate.NewStep
import com.omnidev.workspace.domain.taskstate.NewTask
import com.omnidev.workspace.domain.taskstate.StepCompletion
import com.omnidev.workspace.domain.taskstate.StepOutcome
import com.omnidev.workspace.domain.taskstate.StepStatus
import com.omnidev.workspace.domain.taskstate.TaskStatus
import com.omnidev.workspace.domain.taskstate.TransitionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** The shared rules engine over the real Room schema, plus the 17 -> 18 migration from a database that holds user data. */
class TaskStateRoomTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fileName = "task_state_room_test.db"
    @After fun cleanup() { context.deleteDatabase(fileName) }

    private fun inv(id: String, effect: Effect = Effect.SIDE_EFFECT) =
        NewInvocation("t1/t1:c1/$id", id, "t1", "t1:c1", null, "patch_file_content", "patch_file_content", "{}", "h", effect, "k$id", requestedAt = 5)

    @Test fun taskPlanStepsInvocationsAndFinishRoundTripThroughRoom() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OmniDevDatabase::class.java).build()
        try {
            val store = roomTaskStateStore(db)
            assertTrue(store.createTask(NewTask("t1", 3L, "original request", createdAt = 1)))
            assertFalse(store.createTask(NewTask("t1", 3L, "overwritten?", createdAt = 2)))
            store.transitionTask("t1", TaskStatus.RUNNING, "go", 3, "start")
            store.appendPlanRevision("t1", listOf(NewStep("S1", "do it")), "initial", 4, "plan1")
            store.appendPlanRevision("t1", listOf(NewStep("S1", "do it differently")), "replan", 5, "plan2")
            store.beginCycle("t1", null, 6, "cycle1")
            assertTrue(store.recordInvocationIntent(inv("c1")))
            assertFalse(store.recordInvocationIntent(inv("c1")))
            val id = "t1/t1:c1/c1"
            assertTrue(store.transitionInvocation(id, InvocationStatus.SUCCEEDED, InvocationPatch(), 7, "skip") is TransitionResult.Rejected)
            store.transitionInvocation(id, InvocationStatus.RUNNING, InvocationPatch(startedAt = 8), 8, "run")
            store.transitionInvocation(id, InvocationStatus.SUCCEEDED, InvocationPatch(completedAt = 9, resultSummary = "patched", resultHash = "h", resultChars = 7, isError = false), 9, "done")
            val snap = store.snapshot("t1")!!
            assertEquals("original request", snap.task.originalRequest)
            assertEquals(2, snap.plans.size)
            assertEquals(StepStatus.SKIPPED, snap.steps.first { it.planId == snap.plans[0].planId }.status)
            val step = snap.currentSteps.single()
            val rejected = store.finalizeTask("t1", TaskStatus.COMPLETED, FinishRecord("completed", "ok", "d"),
                listOf(StepOutcome(step.stepId, StepStatus.COMPLETED, "ok", StepCompletion(listOf("t1/t1:c1/nope")))), 10, "fin1")
            assertTrue(rejected is TransitionResult.Rejected)
            assertEquals(TaskStatus.RUNNING, store.task("t1")!!.status)                       // rolled back as a whole
            assertEquals(TransitionResult.Applied, store.finalizeTask("t1", TaskStatus.COMPLETED, FinishRecord("completed", "ok", "d"),
                listOf(StepOutcome(step.stepId, StepStatus.COMPLETED, "ok", StepCompletion(listOf(id)))), 11, "fin2"))
            assertEquals(TransitionResult.Duplicate, store.finalizeTask("t1", TaskStatus.COMPLETED, FinishRecord("completed", "ok", "d"), emptyList(), 12, "fin2"))
            val c = store.counts("t1")
            assertEquals(1, c.invocations); assertEquals(1, c.succeeded); assertEquals(0, c.unresolved); assertEquals(1, c.stepsCompleted)
        } finally { db.close() }
    }

    @Test fun concurrentWritersProduceExactlyOneWinnerAndNoDuplicates() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OmniDevDatabase::class.java).build()
        try {
            val store = roomTaskStateStore(db)
            store.createTask(NewTask("t1", null, "x", createdAt = 1))
            store.recordInvocationIntent(inv("c1"))
            val id = "t1/t1:c1/c1"
            val results = (1..40).map { i -> async(Dispatchers.IO) { store.transitionInvocation(id, InvocationStatus.RUNNING, InvocationPatch(startedAt = i.toLong()), i.toLong(), "run:$i") } }.awaitAll()
            assertEquals(1, results.count { it == TransitionResult.Applied })
            val dups = (1..40).map { async(Dispatchers.IO) { store.recordInvocationIntent(inv("c1")) } }.awaitAll()
            assertTrue(dups.none { it })
            val cycles = (1..30).map { i -> async(Dispatchers.IO) { store.beginCycle("t1", null, i.toLong(), "cycle:$i").seq } }.awaitAll()
            assertEquals((1..30).toList(), cycles.sorted())                                  // no lost update on the counter
            assertEquals(30, store.counts("t1").cycles)
        } finally { db.close() }
    }

    @Test fun stateSurvivesClosingAndReopeningTheDatabaseFile() = runBlocking {
        var db = Room.databaseBuilder(context, OmniDevDatabase::class.java, fileName).build()
        roomTaskStateStore(db).apply {
            createTask(NewTask("t1", 9L, "survive me", createdAt = 1)); transitionTask("t1", TaskStatus.RUNNING, "go", 2, "s")
            beginCycle("t1", null, 3, "c"); recordInvocationIntent(inv("c1"))
            transitionInvocation("t1/t1:c1/c1", InvocationStatus.RUNNING, InvocationPatch(startedAt = 4), 4, "run")
        }
        db.close()                                                                            // process death stand-in
        db = Room.databaseBuilder(context, OmniDevDatabase::class.java, fileName).build()
        try {
            val snap = roomTaskStateStore(db).snapshot("t1")!!
            assertEquals("survive me", snap.task.originalRequest)
            assertEquals(TaskStatus.RUNNING, snap.task.status)
            assertEquals(InvocationStatus.RUNNING, snap.invocations.single().status)
        } finally { db.close() }
    }

    @Test fun migrationFrom17KeepsExistingDataAndCreatesWorkingTaskTables() = runBlocking {
        // Build a real database, then turn it into a v17 one: new tables absent, user_version = 17.
        var db = Room.databaseBuilder(context, OmniDevDatabase::class.java, fileName).build()
        val session = db.chatSessionDao().insert(ChatSessionEntity(title = "Existing chat"))
        db.chatMessageDao().insert(ChatMessageEntity(sessionId = session, role = "USER", content = "hello", messageId = "m1"))
        db.toolExecutionDao().insert(ToolExecutionEntry(toolName = "read_file", executionTimeMs = 5, success = true))
        val raw: SupportSQLiteDatabase = db.openHelper.writableDatabase
        listOf("agent_task_events", "agent_task_invocations", "agent_task_cycles", "agent_task_steps", "agent_task_plans", "agent_tasks")
            .forEach { raw.execSQL("DROP TABLE IF EXISTS $it") }
        raw.execSQL("PRAGMA user_version = 17")
        db.close()

        db = Room.databaseBuilder(context, OmniDevDatabase::class.java, fileName).addMigrations(*OmniDevDatabase.ALL_MIGRATIONS).build()
        try {
            assertEquals("hello", db.chatMessageDao().getBySession(session).single().content)       // existing data preserved
            assertEquals(1, db.toolExecutionDao().getRecent(10).size)
            val store = roomTaskStateStore(db)                                                         // schema validated by Room on open
            assertTrue(store.createTask(NewTask("after-migration", session, "works", createdAt = 1)))
            assertEquals("works", store.task("after-migration")!!.originalRequest)
        } finally { db.close() }
    }
}
