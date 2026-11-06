package com.omnidev.workspace.domain.planning

import com.omnidev.workspace.domain.engine.*
import com.omnidev.workspace.domain.taskstate.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ToolSurfacePlanningTest {
    @Test fun `focus is restored from exact running cycle in latest persisted revision`() = runBlocking {
        val store = InMemoryTaskStateStore()
        store.createTask(NewTask("task", 7, "Edit source and verify", createdAt = 1))
        store.transitionTask("task", TaskStatus.RUNNING, "start", 2, "start")
        val p = StructuredPlan("Edit source", "Read then patch and verify", emptyList(), listOf(
            PlannedStep("S1", "Inspect source", expectedOutput = "Source", completionCondition = "Read source",
                policy = StepPolicy(listOf("CODE_TERMINAL"), checkTool = "read_file"))))
        store.appendPlanRevision("task", p.steps.map { it.newStep() }, "initial", 3, "plan", PlanCodec.metadata(p, "Edit source and verify"), 0)
        var calls = 0
        fun coordinator() = TaskPlanningCoordinator(store, "task", StructuredPlanGenerator { _, _ ->
            calls++; error("A restored focus must not call a model")
        }, emptyList(), emptyList(), emptyList())
        val first = coordinator()
        first.refresh()
        assertNull(first.toolSurfaceFocus())
        val step = store.snapshot("task")!!.currentSteps.single()
        val cycle = store.beginCycle("task", step.stepId, 4, "cycle")
        first.refresh()
        val focus = first.toolSurfaceFocus()!!
        assertEquals(step.planId, focus.revision)
        assertEquals("S1", focus.stepKey)
        assertEquals(setOf(IntentClassifier.ToolDomain.CODE_TERMINAL), focus.domains)
        assertEquals("read_file", focus.verificationTool)
        val restored = coordinator()
        restored.refresh()
        assertEquals(focus, restored.toolSurfaceFocus())
        store.endCycle(cycle.cycleId, "closed", "", "[]", 5)
        restored.refresh()
        assertNull(restored.toolSurfaceFocus())
        assertEquals(0, calls)
    }
}
