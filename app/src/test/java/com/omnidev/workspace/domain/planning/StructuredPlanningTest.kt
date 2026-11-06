package com.omnidev.workspace.domain.planning

import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.domain.engine.completion.*
import com.omnidev.workspace.domain.taskstate.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class StructuredPlanningTest {
    private fun step(key: String, deps: List<String> = emptyList()) = PlannedStep(key, "Inspect source $key", deps,
        "Source text for $key", "Read the requested source", StepPolicy(listOf("CORE")))
    private fun plan(steps: List<PlannedStep> = listOf(step("S1"), step("S2", listOf("S1")))) =
        StructuredPlan("Inspect the source", "Inspect dependencies before the requested follow-up", listOf("Keep existing data"), steps)
    private suspend fun started(): InMemoryTaskStateStore = InMemoryTaskStateStore().also {
        it.createTask(NewTask("task", 7, "original request", normalizedObjective = "Inspect the source", createdAt = 1))
        it.transitionTask("task", TaskStatus.RUNNING, "start", 2, "start")
    }
    private suspend fun persist(store: TaskStateStore, plan: StructuredPlan = plan(), revision: Int = 0): PlanRevisionRecord? =
        store.appendPlanRevision("task", plan.steps.map { it.newStep() }, "initial", 3, "plan-$revision-${plan.hashCode()}",
            PlanCodec.metadata(plan, "Inspect the source"), revision)
    private suspend fun read(store: TaskStateStore, step: StepRecord?, call: String, output: String = "class Source", effect: Effect = Effect.READ_ONLY): String {
        val cycle = store.beginCycle("task", step?.stepId, 4, "cycle-$call")
        val id = "task/${cycle.cycleId}/$call"
        store.recordInvocationIntent(NewInvocation(id, call, "task", cycle.cycleId, step?.stepId,
            if (effect == Effect.READ_ONLY) "read_file" else "patch_file_content", "schema", "{\"path\":\"/tmp/source.kt\"}", "hash", effect, call, requestedAt = 5))
        store.transitionInvocation(id, InvocationStatus.RUNNING, InvocationPatch(startedAt = 6), 6, "run-$call")
        store.transitionInvocation(id, InvocationStatus.SUCCEEDED, InvocationPatch(resultSummary = output, completedAt = 7), 7, "done-$call")
        return id
    }

    @Test fun `invalid graphs and omitted constraints are rejected with bounded codes`() {
        val variants = listOf(
            plan(listOf(step("S1"), step("S1"))) to "DUPLICATE_KEY",
            plan(listOf(step("S1", listOf("missing")))) to "MISSING_DEPENDENCY",
            plan(listOf(step("S1", listOf("S1")))) to "SELF_DEPENDENCY",
            plan(listOf(step("S1", listOf("S2")), step("S2", listOf("S1")))) to "DEPENDENCY_CYCLE",
            plan(listOf(step("S1", listOf("S2", "S2")), step("S2"))) to "DUPLICATE_DEPENDENCY",
            plan().copy(objective = "") to "OBJECTIVE",
            plan().copy(steps = listOf(step("S1").copy(expectedOutput = ""))) to "EXPECTED_OUTPUT",
            plan().copy(summary = "password=demo") to "UNSAFE_PLAN_TEXT",
            plan().copy(summary = "bash run.sh") to "UNSAFE_PLAN_TEXT",
            plan().copy(steps = listOf(step("S1"), step("S2").copy(description = step("S1").description))) to "DUPLICATE_STEP",
            plan().copy(steps = listOf(step("S1").copy(policy = StepPolicy(approvalKind = "AUTO_APPROVED")))) to "INVALID_APPROVAL"
        )
        variants.forEach { (p, code) ->
            val v = PlanValidator.validate(p)
            assertFalse(v.valid)
            assertTrue("$code: ${v.issues}", v.issues.any { it.code == code })
            assertTrue(v.diagnostic().length <= 240)
        }
        assertTrue(PlanValidator.validate(plan(), listOf("Never delete data")).issues.any { it.code == "MISSING_MANDATORY_CONSTRAINT" })
    }

    @Test fun `seeded DAG permutations normalize and schedule deterministically`() {
        val random = Random(9317)
        repeat(100) {
            val count = random.nextInt(2, 24)
            val nodes = (0 until count).map { n -> step("S${n.toString().padStart(2, '0')}",
                (0 until n).filter { random.nextBoolean() }.map { "S${it.toString().padStart(2, '0')}" }) }
            val first = PlanValidator.validate(plan(nodes.shuffled(random))).normalized!!
            val second = PlanValidator.validate(plan(nodes.shuffled(random))).normalized!!
            assertEquals(first.steps.map { it.key }, second.steps.map { it.key })
            val positions = first.steps.mapIndexed { n, s -> s.key to n }.toMap()
            assertTrue(first.steps.all { s -> s.dependsOnKeys.all { positions.getValue(it) < positions.getValue(s.key) } })
            val cyclic = listOf(step("A", listOf("B")), step("B", listOf("A"))) + nodes
            assertFalse(PlanValidator.validate(plan(cyclic)).valid)
        }
    }

    @Test fun `invalid plans never supersede a persisted revision`() = runBlocking {
        val store = started(); persist(store)
        val before = store.snapshot("task")!!
        try { persist(store, plan(listOf(step("S1", listOf("missing")))), 1); fail("invalid graph accepted") } catch (_: TaskStateException) { }
        assertEquals(before, store.snapshot("task"))
    }

    @Test fun `failed and skipped dependencies cannot execute and there is no failed step spin`() = runBlocking {
        val store = started(); persist(store)
        val first = store.snapshot("task")!!.currentSteps.first()
        val second = store.snapshot("task")!!.currentSteps.last()
        assertTrue(store.transitionStep(second.stepId, StepStatus.RUNNING, "early", 4, "early") is TransitionResult.Rejected)
        store.transitionStep(first.stepId, StepStatus.FAILED, "unavailable", 4, "failed")
        val schedule = TaskExecutionScheduler.schedule(store.snapshot("task")!!.currentSteps)
        assertNull(schedule.next); assertTrue(schedule.stalled)
        assertEquals(StepReadiness.BLOCKED_BY_DEPENDENCY, schedule.steps.last().state)
        store.transitionStep(first.stepId, StepStatus.SKIPPED, "skipped", 5, "skipped")
        assertNull(TaskExecutionScheduler.schedule(store.snapshot("task")!!.currentSteps).next)
    }

    @Test fun `tool success requires explicit step proof and wrong or unassigned evidence is refused`() = runBlocking {
        val store = started(); persist(store)
        val first = store.snapshot("task")!!.currentSteps.first()
        val unassigned = read(store, null, "unassigned")
        assertEquals("UNATTRIBUTED_STEP_EVIDENCE", StepEvidenceEvaluator.evaluate(store.snapshot("task")!!, first, StepCompletion(listOf(unassigned))))
        val id = read(store, first, "read")
        assertNotEquals(StepStatus.COMPLETED, store.snapshot("task")!!.currentSteps.first().status)
        assertTrue(store.transitionStep(first.stepId, StepStatus.COMPLETED, "claim", 8, "fake", StepCompletion(listOf("fabricated"))) is TransitionResult.Rejected)
        assertEquals(TransitionResult.Applied, store.transitionStep(first.stepId, StepStatus.COMPLETED, "P0 checked", 8, "proof", StepCompletion(listOf(id))))
        val ready = TaskExecutionScheduler.schedule(store.snapshot("task")!!.currentSteps)
        assertEquals("S2", ready.next!!.key)
    }

    @Test fun `mutation needs content read back and a transport acknowledgement does not prove completion`() = runBlocking {
        val store = started()
        val p = plan(listOf(step("S1").copy(policy = StepPolicy(listOf("CORE"), ProofKind.READ_BACK, contains = "fixed"))))
        persist(store, p)
        val step = store.snapshot("task")!!.currentSteps.single()
        val write = read(store, step, "write", "HTTP 200 accepted", Effect.SIDE_EFFECT)
        assertNotNull(StepEvidenceEvaluator.evaluate(store.snapshot("task")!!, step, StepCompletion(listOf(write))))
        val wrong = read(store, step, "wrong", "file exists")
        assertEquals("OUTPUT_CHECK_NOT_MET", StepEvidenceEvaluator.evaluate(store.snapshot("task")!!, step, StepCompletion(listOf(write, wrong))))
        val check = read(store, step, "check", "class Source { fixed }")
        assertNull(StepEvidenceEvaluator.evaluate(store.snapshot("task")!!, step, StepCompletion(listOf(write, check))))
    }

    @Test fun `completed contracts and original evidence survive revision while changed proof is rejected`() = runBlocking {
        val store = started(); val p = plan(); persist(store, p)
        val firstStep = store.snapshot("task")!!.currentSteps.first()
        val id = read(store, firstStep, "read")
        store.transitionStep(firstStep.stepId, StepStatus.COMPLETED, "verified", 8, "complete", StepCompletion(listOf(id)))
        val revised = p.copy(summary = "New discovery", steps = p.steps + step("S3", listOf("S2")))
        persist(store, revised, 1)
        val snapshot = store.snapshot("task")!!
        assertEquals(2, snapshot.plans.size)
        assertEquals(listOf(id), snapshot.currentSteps.first().evidenceInvocationIds)
        assertEquals(StepStatus.COMPLETED, snapshot.currentSteps.first().status)
        assertEquals("original request", snapshot.task.originalRequest)
        try { persist(store, revised.copy(steps = revised.steps.map { if (it.key == "S1") it.copy(completionCondition = "Different check") else it }), 2)
            fail("changed completed work accepted") } catch (_: TaskStateException) { }
        assertEquals(snapshot, store.snapshot("task"))
    }

    @Test fun `compare and set planning and repeated identical generations cannot duplicate revisions`() = runBlocking {
        val store = started(); val p = plan()
        val outcomes = (1..12).map { async(Dispatchers.Default) { runCatching { persist(store, p) } } }.awaitAll()
        assertEquals(1, outcomes.count { it.getOrNull() != null })
        assertEquals(1, store.snapshot("task")!!.plans.size)
        assertNull(persist(store, p, 1))
        assertEquals(1, store.snapshot("task")!!.plans.size)
    }

    @Test fun `coordinator creates complex plan and resume restores without generation`() = runBlocking {
        val store = started(); var calls = 0
        val generator = StructuredPlanGenerator { _, _ -> calls++; PlanGeneration(plan(), 23, "") }
        val first = TaskPlanningCoordinator(store, "task", generator, listOf("CORE"), emptyList(), emptyList())
        assertEquals(23, first.prepare(null))
        assertEquals(1, calls)
        TaskRecovery(store).recover("task")
        val resume = TaskContinuationCoordinator(store).resume("task") as TaskResumeResult.Ready
        assertEquals("task", resume.continuation.taskId)
        val restored = TaskPlanningCoordinator(store, "task", generator, listOf("CORE"), emptyList(), emptyList())
        assertEquals(0, restored.prepare(null))
        assertEquals(1, calls)
        assertEquals(first.nextStep(), restored.nextStep())
    }

    @Test fun `atomic task has no planning call and redirected request becomes another revision`() = runBlocking {
        val store = started()
        store.appendPlanRevision("task", listOf(NewStep("S1", "Read the source", expectedOutput = "Source", completionCondition = "Read it")), "atomic", 2, "atomic")
        var calls = 0
        val coordinator = TaskPlanningCoordinator(store, "task", StructuredPlanGenerator { input, _ ->
            calls++; PlanGeneration(plan().copy(objective = input.objective), 17, "")
        }, listOf("CORE"), emptyList(), emptyList())
        assertEquals(0, coordinator.prepare(null)); assertEquals(0, calls)
        coordinator.redirect("Inspect the source and tests")
        assertEquals(17, coordinator.prepare(null)); assertEquals(1, calls)
        assertEquals(2, store.snapshot("task")!!.plans.size)
        assertEquals("original request", store.task("task")!!.originalRequest)
        assertFalse(PlanningTrigger.complex("Read source.kt"))
        assertTrue(PlanningTrigger.complex("- Inspect source\n- Update implementation\n- Run acceptance checks"))
    }

    @Test fun `unknown mutation survives replanning and cannot be resolved by a later read`() = runBlocking {
        val store = started(); persist(store)
        val step = store.snapshot("task")!!.currentSteps.first()
        val cycle = store.beginCycle("task", step.stepId, 5, "cycle")
        val id = "task/${cycle.cycleId}/unknown"
        store.recordInvocationIntent(NewInvocation(id, "unknown", "task", cycle.cycleId, step.stepId, "patch_file_content", "schema", "{}", "h", Effect.SIDE_EFFECT, "key", requestedAt = 6))
        store.transitionInvocation(id, InvocationStatus.RUNNING, InvocationPatch(), 7, "run")
        store.transitionInvocation(id, InvocationStatus.UNKNOWN, InvocationPatch(), 8, "unknown")
        read(store, step, "later-read")
        var calls = 0
        val replanner = AdaptiveReplanner(store, StructuredPlanGenerator { _, _ -> calls++; PlanGeneration(plan(), 1, "") })
        val outcome = replanner.revise(store.snapshot("task")!!, PlanningInput("goal", "original", emptyList(), emptyList(), emptyList()), null)
        assertEquals("UNRESOLVED_SIDE_EFFECT", outcome.diagnostic); assertEquals(0, calls)
        assertEquals(InvocationStatus.UNKNOWN, store.invocation(id)!!.status)
    }

    @Test fun `interrupted model request creates no revision and can safely retry`() = runBlocking {
        val store = started(); val entered = CompletableDeferred<Unit>()
        val coordinator = TaskPlanningCoordinator(store, "task", StructuredPlanGenerator { _, _ -> entered.complete(Unit); awaitCancellation() },
            emptyList(), emptyList(), emptyList())
        val job = launch { coordinator.prepare(null) }; entered.await(); job.cancelAndJoin()
        assertTrue(store.snapshot("task")!!.plans.isEmpty())
        val retry = TaskPlanningCoordinator(store, "task", StructuredPlanGenerator { _, _ -> PlanGeneration(plan(), 1, "") }, emptyList(), emptyList(), emptyList())
        retry.prepare(null); retry.prepare(null)
        assertEquals(1, store.snapshot("task")!!.plans.size)
    }

    @Test fun `bounded completion fallback rejects prose and counts schema repair tokens`() = runBlocking {
        var calls = 0
        val generator = ModelStructuredPlanGenerator(CompletionRequest("test", emptyList()), { req ->
            assertNull(req.tools); assertFalse(req.enableThinking)
            calls++
            if (calls == 1) CompletionResponse("Here is my plan: {}") else CompletionResponse(PlanCodec.json.encodeToString(plan()))
        }, 32_000)
        val generated = generator.generate(PlanningInput("objective", "original", listOf("Keep existing data"), emptyList(), listOf("CORE")), null)
        assertNotNull(generated.plan); assertEquals(2, calls); assertTrue(generated.tokens > 0)
        val skipped = generator.generate(PlanningInput("objective", "original", emptyList(), emptyList(), emptyList()), 1)
        assertEquals("PLANNING_TOKEN_BUDGET", skipped.diagnostic); assertEquals(2, calls)
    }

    @Test fun `fabricated context cannot claim another step and P0 still rejects unfinished plan`() = runBlocking {
        val store = started(); persist(store)
        val steps = store.snapshot("task")!!.currentSteps
        val cycle = store.beginCycle("task", steps.first().stepId, 4, "cycle")
        val forged = NewInvocation("bad", "bad", "task", cycle.cycleId, steps.last().stepId, "read_file", "s", "{}", "h", Effect.READ_ONLY, "k", requestedAt = 5)
        try { store.recordInvocationIntent(forged); fail("forged attribution accepted") } catch (_: TaskStateException) { }
        assertTrue(store.snapshot("task")!!.invocations.isEmpty())
        val coordinator = TaskPlanningCoordinator(store, "task", StructuredPlanGenerator { _, _ -> error("unexpected generation") }, emptyList(), emptyList(), emptyList())
        coordinator.refresh()
        val verdict = FinishVerifier.evaluate(VerificationContext("task", null, emptyList(), false, planProblems = coordinator.pending()),
            FinishRequest(FinishStatus.COMPLETED, "Model says done"))
        assertFalse(verdict.accepted)
        assertEquals("STEP_NOT_ACTIVE", coordinator.control(ToolCall("fake", TaskPlanningCoordinator.COMPLETE, mapOf("step_key" to "S2", "evidence_ids" to "[]"))))
    }
}
