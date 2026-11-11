package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.data.tools.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TeamCompletionPipelineTest {
    private class Tools(private val failWrites: Boolean = false) : ToolManager {
        val executed = mutableListOf<String>()
        val paths = mutableListOf<String>()
        override fun getToolDefinitions() = listOf(
            ToolDefinition("read_file", "Read source", listOf(ToolParameter("path", "string", "Path"))),
            ToolDefinition("write_file", "Write source", listOf(ToolParameter("path", "string", "Path"))),
            ToolDefinition("run_terminal", "Run build tests", listOf(ToolParameter("command", "string", "Command"))))
        override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
            executed += name
            paths += arguments["path"].orEmpty()
            return if (name == "write_file" && failWrites) ToolExecutionResult("GITHUB_AUTH_REQUIRED: blocked", true,
                classification = "GITHUB_AUTH_REQUIRED") else ToolExecutionResult("class Source: inspected")
        }
    }
    private fun assigned(request: CompletionRequest) = request.messages.firstOrNull { it.content.contains("## Assigned Team Task") }?.content.orEmpty()


    @Test fun `unkept inspection promise gets one alternative worker attempt with real read evidence`() = runTest {
        val tools = Tools()
        val team = SwarmOrchestrator(tools, completionProvider = { request ->
            when {
                request.systemPrompt.orEmpty().contains("Omni Team Orchestrator") ->
                    CompletionResponse("""[{"id":"inspect","description":"Inspect project configuration","parallelSafe":true}]""")
                request.systemPrompt.orEmpty().contains("Synthesize specialist") ->
                    CompletionResponse("Verified source inspection")
                request.messages.any { it.toolResults.isNotEmpty() } ->
                    CompletionResponse("Read source and verified its contents")
                request.messages.any { it.role == MessageRole.USER && it.content.contains("[Recovery attempt 2/3]") } ->
                    CompletionResponse("", listOf(ToolCall("read-on-retry", "read_file", mapOf("path" to "/tmp/config.kt"))))
                else -> CompletionResponse("I will inspect the source next.")
            }
        })
        val events = team.execute("Inspect project configuration and report verified source",
            "gpt-4o-mini", "gpt-4o-mini", "/tmp").toList()
        val result = events.filterIsInstance<SwarmEvent.Completed>().single()
        assertEquals(events.joinToString("\n"), 1, result.tasksCompleted)
        assertEquals(0, result.tasksFailed)
        assertEquals(listOf("read_file"), tools.executed)
        assertTrue(events.filterIsInstance<SwarmEvent.WorkerPhaseChanged>().any { it.phase == "RECOVER" })
    }

    @Test fun `mutation worker with no observations never blindly replays`() = runTest {
        val tools = Tools()
        var workerRequests = 0
        val team = SwarmOrchestrator(tools, completionProvider = { request ->
            when {
                request.systemPrompt.orEmpty().contains("Omni Team Orchestrator") ->
                    CompletionResponse("""[{"id":"edit","description":"Write source implementation","parallelSafe":false}]""")
                request.systemPrompt.orEmpty().contains("Synthesize specialist") ->
                    CompletionResponse("Unverified")
                else -> {
                    workerRequests++
                    CompletionResponse("I will write the source next.")
                }
            }
        })
        val events = team.execute("Write source implementation and verify the result",
            "gpt-4o-mini", "gpt-4o-mini", "/tmp").toList()
        val outcome = events.filterIsInstance<SwarmEvent.Completed>().single()
        assertEquals(0, outcome.tasksCompleted)
        assertEquals(1, outcome.tasksFailed)
        assertTrue(tools.executed.isEmpty())
        assertFalse(events.filterIsInstance<SwarmEvent.WorkerPhaseChanged>().any { it.phase == "RECOVER" })
        assertTrue(workerRequests > 0)
    }

    @Test fun `actual workers produce exact accepted receipts before dependencies execute`() = runTest {
        val tools = Tools()
        val team = SwarmOrchestrator(tools, completionProvider = { r -> when {
            r.systemPrompt.orEmpty().contains("Omni Team Orchestrator") -> CompletionResponse("""[
                {"id":"config","description":"Read project configuration","parallelSafe":true},
                {"id":"source","description":"Read source implementation using configuration","dependencies":["config"],"parallelSafe":true}]
            """)
            r.systemPrompt.orEmpty().contains("Synthesize specialist") -> CompletionResponse("Verified team result")
            r.messages.any { it.toolResults.isNotEmpty() } -> CompletionResponse("Read and verified")
            else -> CompletionResponse("", listOf(ToolCall("same-provider-id", "read_file", mapOf("path" to
                if (assigned(r).substringAfter("## Assigned Team Task").substringBefore("\n## ").contains("Read project configuration")) "/tmp/config.kt" else "/tmp/source.kt"))))
        } })
        val events = team.execute("Inspect project configuration and source", "gpt-4o-mini", "gpt-4o-mini", "/tmp").toList()
        val done = events.filterIsInstance<SwarmEvent.Completed>().single()
        assertEquals(2, done.tasksCompleted); assertEquals(0, done.tasksFailed)
        assertTrue(done.completion!!.verified)
        assertEquals(listOf(1, 1), done.completion!!.workers.map { it.evidenceCount })
        assertTrue(events.indexOfFirst { it is SwarmEvent.TaskCompleted && it.task.id == "config" } <
            events.indexOfFirst { it is SwarmEvent.TaskStarted && it.task.id == "source" })
        assertEquals(listOf("read_file", "read_file"), tools.executed)
        assertEquals(listOf("/tmp/config.kt", "/tmp/source.kt"), tools.paths)
    }

    @Test fun `a failed side effect is not replayed and cannot be hidden by synthesis`() = runTest {
        val tools = Tools(failWrites = true); var synthesized = 0
        val team = SwarmOrchestrator(tools, completionProvider = { r -> when {
            r.systemPrompt.orEmpty().contains("Omni Team Orchestrator") -> CompletionResponse("""[
                {"id":"inspect","description":"Read configuration metadata","parallelSafe":true},
                {"id":"patch","description":"Write source implementation","parallelSafe":false},
                {"id":"verify","description":"Verify source after patch","dependencies":["patch"],"parallelSafe":true}]
            """)
            r.systemPrompt.orEmpty().contains("Synthesize specialist") -> { synthesized++; CompletionResponse("Everything succeeded") }
            r.messages.any { it.toolResults.isNotEmpty() } -> CompletionResponse("Read metadata")
            else -> if (assigned(r).substringAfter("## Assigned Team Task").substringBefore("\n## ").contains("Write source"))
                CompletionResponse("", listOf(ToolCall("write", "write_file", mapOf("path" to "/tmp/source.kt"))))
            else CompletionResponse("", listOf(ToolCall("read", "read_file", mapOf("path" to "/tmp/config.kt"))))
        } })
        val events = team.execute("Read configuration, write source implementation and verify it", "gpt-4o-mini", "gpt-4o-mini", "/tmp").toList()
        val done = events.filterIsInstance<SwarmEvent.Completed>().single()
        assertFalse(done.completion!!.verified)
        assertEquals("Interrupted", done.completion!!.sessionStatus)
        assertEquals(1, tools.executed.count { it == "write_file" })
        assertEquals(0, synthesized)
        assertTrue(events.any { it is SwarmEvent.TaskSkipped && it.task.id == "verify" })
        assertTrue(done.summary.contains("Team could not complete", ignoreCase = true))
    }

    @Test fun `pure explanation with no tool backend still passes through P0`() = runTest {
        var calls = 0
        val empty = object : ToolManager {
            override fun getToolDefinitions() = emptyList<ToolDefinition>()
            override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?) = error("No tools needed")
        }
        val team = SwarmOrchestrator(empty, completionProvider = { r -> calls++
            if (r.systemPrompt.orEmpty().contains("Omni Team Orchestrator")) CompletionResponse(
                """[{"id":"explain","description":"Explain Kotlin","toolFree":true,"parallelSafe":true}]""")
            else CompletionResponse("Kotlin explanation")
        })
        val done = team.execute("Explain Kotlin", "gpt-4o-mini", "gpt-4o-mini", "/tmp").toList().filterIsInstance<SwarmEvent.Completed>().single()
        assertTrue(done.completion!!.verified); assertEquals(0, done.completion!!.workers.single().evidenceCount)
        assertEquals("Kotlin explanation", done.summary); assertEquals(2, calls)
    }

    @Test fun `execution directive runs serially and accepts real build command proof`() = runTest {
        val tools = Tools()
        val team = SwarmOrchestrator(tools, completionProvider = { r -> when {
            r.systemPrompt.orEmpty().contains("Omni Team Orchestrator") -> CompletionResponse(
                """[{"id":"tests","description":"Run the unit tests","parallelSafe":true}]""")
            r.messages.any { it.toolResults.isNotEmpty() } -> CompletionResponse("Unit test command completed")
            else -> CompletionResponse("", listOf(ToolCall("test", "run_terminal", mapOf("command" to "./gradlew test"))))
        } })
        val events = team.execute("Run the unit tests", "gpt-4o-mini", "gpt-4o-mini", "/tmp").toList()
        assertFalse(events.filterIsInstance<SwarmEvent.PlanCompleted>().single().tasks.single().parallelSafe)
        assertTrue(events.filterIsInstance<SwarmEvent.Completed>().single().completion!!.verified)
        assertEquals(listOf("run_terminal"), tools.executed)
    }

    @Test fun `serial execution task cannot complete through a successful read alone`() = runTest {
        val tools = Tools()
        val team = SwarmOrchestrator(tools, completionProvider = { r -> when {
            r.systemPrompt.orEmpty().contains("Omni Team Orchestrator") -> CompletionResponse(
                """[{"id":"tests","description":"Run the unit tests","parallelSafe":false}]""")
            r.messages.any { it.toolResults.isNotEmpty() } -> CompletionResponse("All unit tests passed")
            else -> CompletionResponse("", listOf(ToolCall("read", "read_file", mapOf("path" to "/tmp/tests.kt"))))
        } })
        val done = team.execute("Run the unit tests", "gpt-4o-mini", "gpt-4o-mini", "/tmp").toList()
            .filterIsInstance<SwarmEvent.Completed>().single()
        assertFalse(done.completion!!.verified)
        assertEquals(0, done.tasksCompleted)
        assertEquals(setOf("read_file"), tools.executed.toSet())
    }

    @Test fun `planner cannot claim an implementation completed through a tool free explanation`() = runTest {
        val tools = Tools()
        val team = SwarmOrchestrator(tools, completionProvider = { r ->
            if (r.systemPrompt.orEmpty().contains("Omni Team Orchestrator")) CompletionResponse(
                """[{"id":"explain","description":"Explain the conclusion","toolFree":true,"parallelSafe":true}]""")
            else CompletionResponse("Implemented everything")
        })
        val done = team.execute("Edit source.kt to fix the crash", "gpt-4o-mini", "gpt-4o-mini", "/tmp").toList().filterIsInstance<SwarmEvent.Completed>().single()
        assertFalse(done.completion!!.verified)
        assertTrue(done.completion!!.problems.contains("ORIGINAL_OBJECTIVE_REQUIRES_VERIFIED_ACTION"))
        assertTrue(tools.executed.isEmpty())
    }
}
