package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.data.tools.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SwarmToolRecoveryTest {
    @Test fun `misclassified read worker resumes once serially after read wave finishes`() = runTest {
        val readsInFlight = AtomicInteger(0)
        val mutations = AtomicInteger(0)
        val unsafeOverlap = AtomicInteger(0)
        val authWrites = AtomicInteger(0)
        val authReadbacks = AtomicInteger(0)
        val layoutReads = AtomicInteger(0)
        val manager = object : ToolManager {
            override fun getToolDefinitions() = listOf(
                ToolDefinition("read_file", "Read a file", listOf(ToolParameter("path", "string", "Path"))),
                ToolDefinition("write_file", "Write a file", listOf(ToolParameter("path", "string", "Path"), ToolParameter("content", "string", "Content")))
            )
            override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
                val path = arguments["path"].orEmpty()
                if (name == "read_file") {
                    readsInFlight.incrementAndGet()
                    try { delay(20) } finally { readsInFlight.decrementAndGet() }
                    if (path == "/tmp/auth.kt") authReadbacks.incrementAndGet() else layoutReads.incrementAndGet()
                } else {
                    mutations.incrementAndGet()
                    if (readsInFlight.get() != 0) unsafeOverlap.incrementAndGet()
                    if (path == "/tmp/auth.kt") authWrites.incrementAndGet()
                }
                return ToolExecutionResult(if (name == "read_file") "Verified result from $path" else "Written $path")
            }
        }
        val team = SwarmOrchestrator(manager, completionProvider = { request ->
            when {
                request.systemPrompt.orEmpty().contains("Omni Team Orchestrator") -> CompletionResponse("""[
                    {"id":"auth","description":"Inspect authentication documentation","parallelSafe":true},
                    {"id":"layout","description":"Research screen layout and visual hierarchy","parallelSafe":true}
                ]""")
                request.systemPrompt.orEmpty().contains("Synthesize specialist") -> CompletionResponse("Complete")
                request.messages.any { it.toolResults.isNotEmpty() } &&
                    (authReadbacks.get() > 0 || (layoutReads.get() > 0 && authWrites.get() == 0)) -> CompletionResponse("Verified task")
                else -> {
                    val assigned = request.messages.joinToString("\n") { it.content }
                    when {
                        assigned.contains("authentication", ignoreCase = true) && authWrites.get() == 0 ->
                            CompletionResponse("", listOf(ToolCall("write-auth", "write_file", mapOf("path" to "/tmp/auth.kt", "content" to "verified"))))
                        assigned.contains("authentication", ignoreCase = true) && authReadbacks.get() == 0 ->
                            CompletionResponse("", listOf(ToolCall("read-auth", "read_file", mapOf("path" to "/tmp/auth.kt"))))
                        assigned.contains("authentication", ignoreCase = true) -> CompletionResponse("Verified task")
                        layoutReads.get() == 0 ->
                            CompletionResponse("", listOf(ToolCall("read-layout", "read_file", mapOf("path" to "/tmp/layout.md"))))
                        else -> CompletionResponse("Verified task")
                    }
                }
            }
        })
        val events = team.execute("Patch authentication implementation and research screen layout",
            "gpt-4o-mini", "gpt-4o-mini", "/tmp").toList()
        assertEquals(events.joinToString("\n"), 1, mutations.get())
        assertEquals(0, unsafeOverlap.get())
        assertEquals(1, authWrites.get())
        assertEquals(1, authReadbacks.get())
        assertEquals(1, layoutReads.get())
        assertEquals(1, events.filterIsInstance<SwarmEvent.WorkerPhaseChanged>().count { it.phase == "REPLAN" })
        val complete = events.filterIsInstance<SwarmEvent.Completed>().single()
        assertEquals(2, complete.tasksCompleted)
        assertEquals(0, complete.tasksFailed)
    }

    @Test fun `connected tools without effect metadata serialize despite planner label`() {
        val task = SwarmTask("cloud", "Research remote documentation", parallelSafe = true, needsConnectedTools = true)
        assertFalse(TeamExecutionPolicy.classify(task).effectiveParallelSafe)
    }
    @Test fun `read only objective never promotes a worker to mutation`() = runTest {
        var mutations = 0
        val manager = object : ToolManager {
            override fun getToolDefinitions() = listOf(ToolDefinition("write_file", "Inspect documentation"))
            override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
                mutations++
                return ToolExecutionResult("written")
            }
        }
        val team = SwarmOrchestrator(manager, completionProvider = { request ->
            if (request.systemPrompt.orEmpty().contains("Omni Team Orchestrator")) {
                CompletionResponse("""[{"id":"inspect","description":"Inspect documentation","parallelSafe":true}]""")
            } else CompletionResponse("", listOf(ToolCall("bad", "write_file", emptyMap())))
        })
        val events = team.execute("Inspect documentation without editing", "gpt-4o-mini", "gpt-4o-mini", "/tmp").toList()
        assertEquals(0, mutations)
        assertTrue(events.filterIsInstance<SwarmEvent.WorkerPhaseChanged>().none { it.phase == "REPLAN" })
        assertEquals(1, events.filterIsInstance<SwarmEvent.Completed>().single().tasksFailed)
    }
}
