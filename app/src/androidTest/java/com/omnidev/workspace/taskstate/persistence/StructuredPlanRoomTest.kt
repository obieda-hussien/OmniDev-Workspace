package com.omnidev.workspace.taskstate.persistence

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.repository.roomTaskStateStore
import com.omnidev.workspace.domain.planning.*
import com.omnidev.workspace.domain.taskstate.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class StructuredPlanRoomTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun plan() = StructuredPlan("Read source", "Read the source before its dependency", emptyList(), listOf(
        PlannedStep("S1", "Read first source", emptyList(), "Source text", "Read it", StepPolicy(listOf("CORE"))),
        PlannedStep("S2", "Read second source", listOf("S1"), "Dependent source", "Read dependency", StepPolicy(listOf("CORE")))
    ))
    private suspend fun scenario(store: TaskStateStore): TaskSnapshot {
        store.createTask(NewTask("task", 7, "original request", createdAt = 1))
        store.transitionTask("task", TaskStatus.RUNNING, "start", 2, "start")
        val p = plan()
        store.appendPlanRevision("task", p.steps.map { it.newStep() }, "initial", 3, "plan", PlanCodec.metadata(p, "Read source"), 0)
        val first = store.snapshot("task")!!.currentSteps.first()
        val cycle = store.beginCycle("task", first.stepId, 4, "cycle")
        store.recordInvocationIntent(NewInvocation("i1", "c1", "task", cycle.cycleId, first.stepId, "read_file", "schema", "{\"path\":\"/tmp/source.kt\"}", "h", Effect.READ_ONLY, "k", requestedAt = 5))
        store.transitionInvocation("i1", InvocationStatus.RUNNING, InvocationPatch(), 6, "run")
        store.transitionInvocation("i1", InvocationStatus.SUCCEEDED, InvocationPatch(resultSummary = "class Source"), 7, "result")
        assertEquals(TransitionResult.Applied, store.transitionStep(first.stepId, StepStatus.COMPLETED, "checked", 8, "step", StepCompletion(listOf("i1"))))
        val changed = p.copy(summary = "New information", steps = p.steps + PlannedStep("S3", "Read final source", listOf("S2"), "Final text", "Read final", StepPolicy(listOf("CORE"))))
        store.appendPlanRevision("task", changed.steps.map { it.newStep() }, "discovery", 9, "plan2", PlanCodec.metadata(changed, "Read source"), 1)
        return store.snapshot("task")!!
    }
    @Test fun roomAndMemoryHaveIdenticalPlanningAndProofBehavior() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OmniDevDatabase::class.java).build()
        try {
            val expected = scenario(InMemoryTaskStateStore())
            val actual = scenario(roomTaskStateStore(db))
            assertEquals(expected, actual)
            assertEquals("S2", TaskExecutionScheduler.schedule(actual.currentSteps).next!!.key)
        } finally { db.close() }
    }
    @Test fun revisionAndSchedulerSurviveReopeningAndV18Upgrade() = runBlocking {
        val name = "p3-upgrade-test.db"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, OmniDevDatabase::class.java, name).build()
        try {
            val expected = scenario(roomTaskStateStore(db))
            db.close()
            db = Room.databaseBuilder(context, OmniDevDatabase::class.java, name).build()
            assertEquals(expected, roomTaskStateStore(db).snapshot("task"))
            // Reconstruct just the v18 plan table. User data and invocation evidence remain intact.
            val raw = db.openHelper.writableDatabase
            raw.execSQL("ALTER TABLE agent_task_plans RENAME TO old_plans")
            raw.execSQL("DROP INDEX index_agent_task_plans_taskId_revision")
            raw.execSQL("CREATE TABLE agent_task_plans (planId TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, revision INTEGER NOT NULL, reason TEXT NOT NULL, createdAt INTEGER NOT NULL, supersededAt INTEGER)")
            raw.execSQL("INSERT INTO agent_task_plans SELECT planId,taskId,revision,reason,createdAt,supersededAt FROM old_plans")
            raw.execSQL("DROP TABLE old_plans")
            raw.execSQL("CREATE UNIQUE INDEX index_agent_task_plans_taskId_revision ON agent_task_plans(taskId,revision)")
            raw.execSQL("PRAGMA user_version = 18")
            db.close()
            db = Room.databaseBuilder(context, OmniDevDatabase::class.java, name).addMigrations(*OmniDevDatabase.ALL_MIGRATIONS).build()
            val migrated = roomTaskStateStore(db).snapshot("task")!!
            assertEquals(expected.task.originalRequest, migrated.task.originalRequest)
            assertEquals(expected.invocations, migrated.invocations)
            assertTrue(migrated.plans.all { it.metadataJson == "" })
            assertEquals(expected.currentSteps, migrated.currentSteps)
            assertEquals("S2", TaskExecutionScheduler.schedule(migrated.currentSteps).next!!.key)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
