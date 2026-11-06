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

class SmartToolSurfacePipelineTest {
    @Test fun `pipeline specializes each verified step and attributes exact on demand calls`() = runBlocking {
        val store = InMemoryTaskStateStore()
        val executed = mutableListOf<String>()
        val definitions = (1..100).map { ToolDefinition("other_$it", "Unrelated capability $it") } + listOf(
            ToolDefinition("search_messages", "Search records", listOf(ToolParameter("query", "string", "Query"))),
            ToolDefinition("sms_reader_tool", "Subscriber inbox"),
            ToolDefinition("read_file", "Read source", listOf(ToolParameter("path", "string", "Path"))),
            ToolDefinition("patch_file_content", "Patch source", listOf(ToolParameter("path", "string", "Path"), ToolParameter("content", "string", "Content"))))
        val manager = object : ToolManager {
            override fun getToolDefinitions() = definitions
            override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
                executed += name
                return ToolExecutionResult(if (name == "read_file") "class Source { fixed }" else "Observed result")
            }
        }
        val p = StructuredPlan("Repair source", "Inspect records then patch and read back source", emptyList(), listOf(
            PlannedStep("S1", "Inspect records", expectedOutput = "Related records", completionCondition = "Read records",
                policy = StepPolicy(listOf("CORE"), checkTool = "search_messages")),
            PlannedStep("S2", "Repair source", listOf("S1"), "Fixed source", "Read back fixed source",
                StepPolicy(listOf("CODE_TERMINAL"), ProofKind.READ_BACK, checkTool = "read_file", contains = "fixed"))))
        var planningCalls = 0; var turn = 0
        fun call(id: String, name: String, vararg args: Pair<String, String>) = CompletionResponse("", listOf(ToolCall(id, name, args.toMap())))
        suspend fun accept(key: String): CompletionResponse {
            val s = store.snapshot("surface")!!
            val step = s.currentSteps.single { it.key == key }
            return call("complete-$key", TaskPlanningCoordinator.COMPLETE, "step_key" to key, "evidence_ids" to
                PlanCodec.json.encodeToString(s.invocations.filter { it.stepId == step.stepId }.map { it.invocationId }))
        }
        val pipeline = AgentPipeline(manager, completionProvider = { request ->
            if (request.systemPrompt?.startsWith("OMNI_STRUCTURED_PLAN_V1") == true) {
                planningCalls++; CompletionResponse(PlanCodec.json.encodeToString(p))
            } else {
                val tools = request.tools.orEmpty()
                assertTrue(tools.size <= 44) // 40 ordinary schemas, discovery and three P0/P3 controls.
                when (++turn) {
                    1 -> {
                        assertEquals("search_messages", tools[1].name)
                        assertFalse(tools.any { it.name == "sms_reader_tool" })
                        call("inbox", "sms_reader_tool")
                    }
                    2 -> call("records", "search_messages", "query" to "source")
                    3 -> accept("S1")
                    4 -> {
                        assertEquals("read_file", tools[1].name)
                        call("patch", "patch_file_content", "path" to "/tmp/source.kt", "content" to "fixed")
                    }
                    5 -> call("readback", "read_file", "path" to "/tmp/source.kt")
                    6 -> accept("S2")
                    else -> call("finish", FinishTool.NAME, "status" to "completed", "summary" to "Patched and verified",
                        "evidence" to "inbox,records,patch,readback")
                }
            }
        }, taskStateStore = store, config = AgentConfig(maxIterations = 14, enableRetry = false,
            enableMemoryTrimming = false, enableSelfReflection = false))
        val events = pipeline.execute("- Inspect related records\n- Edit source.kt to fix the crash\n- Read back the changed content",
            modelId = "gpt-4o-mini", scopePath = "/tmp", runIdentity = RunIdentity(runId = "surface", sessionId = 7)).toList()
        val answer = events.filterIsInstance<AgentEvent.FinalAnswer>().singleOrNull()
        assertNotNull(events.filterIsInstance<AgentEvent.Error>().joinToString { it.message }, answer)
        assertTrue(answer!!.completion!!.verified)
        withTimeout(10_000) { while (store.task("surface")?.status != TaskStatus.COMPLETED) delay(10) }
        val saved = store.snapshot("surface")!!
        assertEquals(1, planningCalls)
        assertEquals(listOf("sms_reader_tool", "search_messages", "patch_file_content", "read_file"), executed)
        assertEquals(listOf("S1", "S1", "S2", "S2"), saved.invocations.map { invocation -> saved.currentSteps.single { it.stepId == invocation.stepId }.key })
    }
}
