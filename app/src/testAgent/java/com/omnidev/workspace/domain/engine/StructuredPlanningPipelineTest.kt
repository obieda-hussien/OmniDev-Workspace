package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.data.tools.*
import com.omnidev.workspace.domain.engine.completion.*
import com.omnidev.workspace.domain.planning.*
import com.omnidev.workspace.domain.taskstate.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class StructuredPlanningPipelineTest {
    private class Tools : ToolManager {
        val executed = mutableListOf<String>()
        override fun getToolDefinitions() = listOf(
            ToolDefinition("read_file", "Read source", listOf(ToolParameter("path", "string", "Path"))),
            ToolDefinition("patch_file_content", "Patch source", listOf(ToolParameter("path", "string", "Path"), ToolParameter("content", "string", "Content")))
        )
        override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
            executed += name
            return ToolExecutionResult(if (name == "read_file") "class Source { fixed }" else "write accepted")
        }
    }
    private val config = AgentConfig(maxIterations = 18, enableRetry = false, enableMemoryTrimming = false,
        enableSelfReflection = false, enableParallelToolExecution = true)

    @Test fun `real pipeline plans schedules attributes verifies and finishes through P0`() = runBlocking {
        val store = InMemoryTaskStateStore(); val tools = Tools(); var modelTurns = 0; var planningCalls = 0
        val p = StructuredPlan("Repair source", "Inspect before changing and read back the requested content", emptyList(), listOf(
            PlannedStep("S1", "Inspect the source", emptyList(), "Current source", "Read the source file", StepPolicy(listOf("CORE"))),
            PlannedStep("S2", "Repair and verify the source", listOf("S1"), "Source contains fixed", "Read back the changed source", StepPolicy(listOf("CORE"), ProofKind.READ_BACK, contains = "fixed"))
        ))
        fun call(id: String, name: String, vararg args: Pair<String, String>) = CompletionResponse("", listOf(ToolCall(id, name, args.toMap())))
        suspend fun completeStep(key: String): CompletionResponse {
            val s = store.snapshot("pipeline")!!
            val id = s.currentSteps.first { it.key == key }.stepId
            val ids = s.invocations.filter { it.stepId == id }.map { it.invocationId }
            return call("complete$key", TaskPlanningCoordinator.COMPLETE, "step_key" to key, "evidence_ids" to PlanCodec.json.encodeToString(ids))
        }
        val pipeline = AgentPipeline(tools, completionProvider = { request ->
            if (request.systemPrompt?.startsWith("OMNI_STRUCTURED_PLAN_V1") == true) {
                planningCalls++; CompletionResponse(PlanCodec.json.encodeToString(p))
            } else when (++modelTurns) {
                1 -> call("premature", FinishTool.NAME, "status" to "completed", "summary" to "Unsupported model claim")
                2 -> call("inspect", "read_file", "path" to "/tmp/source.kt")
                3 -> completeStep("S1")
                4 -> call("patch", "patch_file_content", "path" to "/tmp/source.kt", "content" to "fixed")
                5 -> call("verify", "read_file", "path" to "/tmp/source.kt")
                6 -> completeStep("S2")
                else -> call("finish", FinishTool.NAME, "status" to "completed", "summary" to "Repaired and read back", "evidence" to "inspect,patch,verify")
            }
        }, config = config, taskStateStore = store)
        val events = pipeline.execute("- Inspect the source\n- Repair the implementation\n- Verify the requested change",
            modelId = "gpt-4o-mini", scopePath = "/tmp", preferredToolNames = setOf("read_file", "patch_file_content"),
            runIdentity = RunIdentity(runId = "pipeline", sessionId = 7)).toList()
        val report = events.filterIsInstance<AgentEvent.FinalAnswer>().single().completion!!
        assertTrue(report.verified); assertEquals(1, report.stats.rejections)
        withTimeout(10_000) { while (store.task("pipeline")?.status != TaskStatus.COMPLETED) delay(10) }
        val s = store.snapshot("pipeline")!!
        assertEquals(1, planningCalls); assertEquals(1, s.plans.size)
        assertEquals(listOf("read_file", "patch_file_content", "read_file"), tools.executed)
        assertTrue(s.currentSteps.all { it.status == StepStatus.COMPLETED && it.evidenceInvocationIds.isNotEmpty() })
        assertEquals(listOf("S1", "S2", "S2"), s.invocations.map { inv -> s.currentSteps.single { it.stepId == inv.stepId }.key })
    }

    @Test fun `plain chat does not initialize planning or persistence`() = runBlocking {
        val store = InMemoryTaskStateStore(); var calls = 0
        val events = AgentPipeline(Tools(), completionProvider = { calls++; CompletionResponse("Hello") }, config = config,
            taskStateStore = store).execute("Hello, how are you?", modelId = "gpt-4o-mini", scopePath = "/tmp",
            runIdentity = RunIdentity(runId = "chat")).toList()
        assertEquals(1, calls); assertNull(store.task("chat"))
        assertNull(events.filterIsInstance<AgentEvent.FinalAnswer>().single().completion)
    }
}
