package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.data.tools.*
import com.omnidev.workspace.domain.teamstate.TeamStateEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Scripted providers, actual SwarmOrchestrator -> AgentPipeline -> tools -> P0/P5 -> durable restore. No live-model claims. */
class DurableTeamPipelineTest {
    private class Tools : ToolManager {
        val calls = mutableListOf<String>()
        var interrupt: String? = null
        override fun getToolDefinitions() = listOf(
            ToolDefinition("read_file", "Read source", listOf(ToolParameter("path", "string", "Path"))),
            ToolDefinition("write_file", "Write source", listOf(ToolParameter("path", "string", "Path"))),
            ToolDefinition("run_terminal", "Run Gradle tests", listOf(ToolParameter("command", "string", "Command"))))
        override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
            calls += name
            if (interrupt == name) throw CancellationException("simulated process interruption at $name")
            return ToolExecutionResult(if (name == "run_terminal") "BUILD SUCCESSFUL; tests passed" else "class Source: inspected")
        }
    }
    private val plan = """[
        {"id":"inspect","description":"Read repository source file","parallelSafe":true},
        {"id":"edit","description":"Write Kotlin source implementation and read back","parallelSafe":false,"dependencies":["inspect"]},
        {"id":"tests","description":"Run Gradle tests","parallelSafe":false,"dependencies":["edit"]}]
    """
    private fun assignment(r: CompletionRequest) = r.messages.firstOrNull { it.content.contains("## Assigned Team Task") }
        ?.content.orEmpty().substringAfter("## Assigned Team Task").substringBefore("\n## ")
    private fun scripted(r: CompletionRequest): CompletionResponse {
        if (r.systemPrompt.orEmpty().contains("Omni Team Orchestrator")) return CompletionResponse(plan)
        if (r.systemPrompt.orEmpty().contains("Synthesize specialist")) return CompletionResponse("Kotlin implementation and Gradle tests verified")
        val task = assignment(r)
        val observations = r.messages.flatMap { it.toolResults }
        val calls = when {
            task.contains("Write Kotlin") && observations.none { it.toolName == "write_file" } ->
                ToolCall("edit-call", "write_file", mapOf("path" to "/tmp/Source.kt"))
            task.contains("Write Kotlin") && observations.none { it.toolName == "read_file" } ->
                ToolCall("readback-call", "read_file", mapOf("path" to "/tmp/Source.kt"))
            task.contains("Run Gradle") && observations.isEmpty() ->
                ToolCall("tests-call", "run_terminal", mapOf("command" to "./gradlew test"))
            observations.isEmpty() -> ToolCall("inspect-call", "read_file", mapOf("path" to "/tmp/Source.kt"))
            else -> null
        }
        return if (calls != null) CompletionResponse("", listOf(calls)) else CompletionResponse("Read source and verified actual tool results")
    }
    private suspend fun run(tools: Tools, store: TeamStateEngine, resume: String? = null,
        provider: suspend (CompletionRequest) -> CompletionResponse = ::scripted) = SwarmOrchestrator(tools, provider,
            teamStateStore = store).execute("Inspect a GitHub repository, modify Kotlin implementation, run Gradle tests and report verified results",
                "gpt-4o-mini", "gpt-4o-mini", "/tmp", sessionId = 7, resumeTeamId = resume).toList()

    @Test fun `completed inspection survives coordinator interruption and remaining real workers finish without reinspection`() = runTest {
        val store = TeamStateEngine.inMemory(); val tools = Tools()
        try {
            SwarmOrchestrator(tools, { r ->
                if (assignment(r).contains("Write Kotlin")) kotlinx.coroutines.awaitCancellation()
                scripted(r)
            }, teamStateStore = store).execute(
                "Inspect repository source, modify Kotlin implementation and run Gradle tests", "gpt-4o-mini", "gpt-4o-mini", "/tmp", sessionId = 7)
                .collect { if (it is SwarmEvent.TaskCompleted && it.task.id == "inspect") throw CancellationException("process stopped after inspection receipt") }
            fail("Expected interruption")
        } catch (_: CancellationException) { }
        val saved = store.summaries(7).single()
        assertTrue(saved.toString(), saved.workers.any { it.id == "inspect" && it.status == "COMPLETED" })
        val before = tools.calls.count { it == "read_file" }
        var plans = 0
        val resumed = run(tools, store, saved.id) { r ->
            if (r.systemPrompt.orEmpty().contains("Omni Team Orchestrator")) plans++
            scripted(r)
        }
        val result = resumed.filterIsInstance<SwarmEvent.Completed>().single()
        assertTrue(result.toString(), result.completion!!.verified)
        assertEquals(0, plans)
        assertEquals(1, tools.calls.count { it == "write_file" })
        assertEquals(before + 1, tools.calls.count { it == "read_file" }) // edit read-back only
        assertEquals(1, tools.calls.count { it == "run_terminal" })
    }
    @Test fun `lost mutation response blocks the whole Team and never dispatches a duplicate write`() = runTest {
        val store = TeamStateEngine.inMemory(); val tools = Tools().apply { interrupt = "write_file" }
        try { run(tools, store) } catch (_: CancellationException) { }
        val saved = store.summaries(7).single()
        assertFalse(saved.toString(), saved.canResume)
        assertTrue(saved.workers.any { it.id == "edit" && it.unresolvedCalls.isNotEmpty() })
        tools.interrupt = null
        val resumed = run(tools, store, saved.id)
        assertTrue(resumed.any { it is SwarmEvent.Error })
        assertEquals(1, tools.calls.count { it == "write_file" })
        assertTrue(resumed.none { it is SwarmEvent.Completed && it.completion?.verified == true })
    }
    @Test fun `checkpointed mutation and test receipt survive interruption during synthesis`() = runTest {
        val store = TeamStateEngine.inMemory(); val tools = Tools()
        try { run(tools, store) { r ->
            if (r.systemPrompt.orEmpty().contains("Synthesize specialist")) throw CancellationException("interrupted synthesis")
            scripted(r)
        } } catch (_: CancellationException) { }
        val saved = store.summaries(7).single()
        assertTrue(saved.toString(), saved.canResume)
        assertEquals(3, saved.workers.count { it.status == "COMPLETED" })
        val before = tools.calls.toList()
        val resumed = run(tools, store, saved.id)
        assertTrue(resumed.filterIsInstance<SwarmEvent.Completed>().single().completion!!.verified)
        assertEquals(before, tools.calls)
    }
    @Test fun `DNS outage preserves prior receipt without redundant worker or coordinator provider retries`() = runTest {
        val store = TeamStateEngine.inMemory(); val tools = Tools(); var failures = 0
        run(tools, store) { r ->
            if (assignment(r).contains("Write Kotlin")) { failures++; throw java.net.UnknownHostException("Unable to resolve host generativelanguage.googleapis.com: No address associated") }
            scripted(r)
        }
        val saved = store.summaries(7).single()
        assertTrue(saved.workers.any { it.id == "inspect" && it.status == "COMPLETED" })
        assertEquals(1, failures)
        assertTrue(saved.canResume)
        val resumed = run(tools, store, saved.id)
        assertTrue(resumed.filterIsInstance<SwarmEvent.Completed>().single().completion!!.verified)
    }
    @Test fun `interrupted known read resumes under a fresh worker identity without completing its dependency from prose`() = runTest {
        val store = TeamStateEngine.inMemory(); val tools = Tools().apply { interrupt = "read_file" }
        try { run(tools, store) } catch (_: CancellationException) { }
        val saved = store.summaries(7).single()
        assertEquals(0, saved.workers.count { it.status == "COMPLETED" })
        assertTrue(saved.canResume)
        tools.interrupt = null
        val result = run(tools, store, saved.id).filterIsInstance<SwarmEvent.Completed>().single()
        assertTrue(result.completion!!.verified)
        assertEquals(2, result.completion!!.workers.first { it.taskId == "inspect" }.attempt)
    }
}
