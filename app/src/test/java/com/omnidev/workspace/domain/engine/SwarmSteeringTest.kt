package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.data.tools.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SwarmSteeringTest {
    private class Tools : ToolManager {
        override fun getToolDefinitions(): List<ToolDefinition> = emptyList()
        override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?) = ToolExecutionResult("done")
    }

    @Test fun `correction during planning discards old plan and does not create a failed task`() = runTest {
        val control = RunSteering()
        val planning = CompletableDeferred<Unit>()
        var plans = 0
        val team = SwarmOrchestrator(Tools(), completionProvider = { request ->
            if (request.systemPrompt.orEmpty().contains("Omni Team Orchestrator")) {
                if (++plans == 1) { planning.complete(Unit); awaitCancellation() }
                assertTrue(request.messages.single().content.contains("Use Kotlin instead"))
                CompletionResponse("""[{"id":"corrected","description":"Explain Kotlin","parallelSafe":true}]""")
            } else CompletionResponse("Corrected team answer")
        })
        val run = async { team.execute("Explain Java", "gpt-4o-mini", "gpt-4o-mini", "/tmp", steering = control).toList() }
        planning.await()
        control.submit("Use Kotlin instead")
        val events = run.await()
        assertEquals(2, plans)
        assertTrue(events.none { it is SwarmEvent.Error || it is SwarmEvent.TaskFailed })
        assertEquals("Corrected team answer", events.filterIsInstance<SwarmEvent.Completed>().single().summary)
        assertEquals(1L, events.filterIsInstance<SwarmEvent.SteeringApplied>().single().revision)
    }

    @Test fun `all parallel model workers stop before corrected wave starts`() = runTest {
        val control = RunSteering()
        val bothStarted = CompletableDeferred<Unit>()
        val active = AtomicInteger(0)
        val cancelled = AtomicInteger(0)
        val plans = AtomicInteger(0)
        val team = SwarmOrchestrator(Tools(), completionProvider = { request ->
            if (request.systemPrompt.orEmpty().contains("Omni Team Orchestrator")) {
                if (plans.incrementAndGet() == 1) CompletionResponse("""[
                    {"id":"auth","description":"Research authentication documentation","parallelSafe":true},
                    {"id":"layout","description":"Research screen layout hierarchy","parallelSafe":true}
                ]""")
                else {
                    assertEquals(0, active.get())
                    assertEquals(2, cancelled.get())
                    CompletionResponse("""[{"id":"corrected","description":"Explain corrected requirements","parallelSafe":true}]""")
                }
            } else if (plans.get() == 1) {
                if (active.incrementAndGet() == 2) bothStarted.complete(Unit)
                try { awaitCancellation() } finally { active.decrementAndGet(); cancelled.incrementAndGet() }
            } else CompletionResponse("Updated team result")
        })
        val run = async { team.execute("Research authentication documentation and screen layout", "gpt-4o-mini", "gpt-4o-mini", "/tmp", steering = control).toList() }
        bothStarted.await()
        control.submit("Drop both topics; explain corrected requirements")
        val events = run.await()
        assertEquals(2, plans.get())
        assertTrue(events.none { it is SwarmEvent.TaskFailed || it is SwarmEvent.Error })
        assertEquals("Updated team result", events.filterIsInstance<SwarmEvent.Completed>().single().summary)
    }

    @Test fun `replan waits for in flight tool and receives its evidence`() = runTest {
        val control = RunSteering()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var settled = false
        var executions = 0
        var plans = 0
        val tools = object : ToolManager {
            override fun getToolDefinitions() = listOf(ToolDefinition("write_file", "Write implementation"))
            override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
                executions++
                started.complete(Unit)
                release.await()
                settled = true
                return ToolExecutionResult("Created app.kt with verified content")
            }
        }
        val team = SwarmOrchestrator(tools, completionProvider = { request ->
            if (request.systemPrompt.orEmpty().contains("Omni Team Orchestrator")) {
                if (++plans == 1) CompletionResponse("""[{"id":"write","description":"Write implementation file","parallelSafe":false}]""")
                else {
                    assertTrue(settled)
                    assertTrue(request.messages.single().content.contains("Created app.kt with verified content"))
                    CompletionResponse("""[{"id":"verify","description":"Explain remaining work","parallelSafe":true}]""")
                }
            } else if (plans == 1) CompletionResponse("", listOf(ToolCall("w", "write_file", emptyMap())))
            else CompletionResponse("Remaining work verified")
        })
        val run = async { team.execute("Write implementation file", "gpt-4o-mini", "gpt-4o-mini", "/tmp", steering = control).toList() }
        started.await()
        control.submit("Keep the created file and explain remaining work")
        assertFalse(settled)
        release.complete(Unit)
        val events = run.await()
        assertEquals(1, executions)
        assertEquals(2, plans)
        assertEquals("Remaining work verified", events.filterIsInstance<SwarmEvent.Completed>().single().summary)
        assertTrue(events.any { it is SwarmEvent.WorkerToolResult && it.output.contains("Created app.kt") })
    }

    @Test fun `redirected model worker does not cancel a sibling tool`() = runTest {
        val control = RunSteering()
        val modelStarted = CompletableDeferred<Unit>()
        val toolStarted = CompletableDeferred<Unit>()
        val releaseTool = CompletableDeferred<Unit>()
        var plans = 0
        var toolCancelled = false
        var toolSettled = false
        val tools = object : ToolManager {
            override fun getToolDefinitions() = listOf(ToolDefinition("read_file", "Read layout documentation"))
            override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
                toolStarted.complete(Unit)
                try { releaseTool.await() } catch (error: kotlinx.coroutines.CancellationException) { toolCancelled = true; throw error }
                toolSettled = true
                return ToolExecutionResult("Verified layout documentation")
            }
        }
        val team = SwarmOrchestrator(tools, completionProvider = { request ->
            when {
                request.systemPrompt.orEmpty().contains("Omni Team Orchestrator") -> {
                    if (++plans == 1) CompletionResponse("""[
                        {"id":"auth","description":"Research authentication documentation","parallelSafe":true},
                        {"id":"layout","description":"Research screen layout documentation","parallelSafe":true}
                    ]""") else {
                        assertTrue(toolSettled)
                        assertFalse(toolCancelled)
                        assertTrue(request.messages.single().content.contains("Verified layout documentation"))
                        CompletionResponse("""[{"id":"corrected","description":"Explain corrected objective","parallelSafe":true}]""")
                    }
                }
                plans > 1 -> CompletionResponse("Corrected result")
                request.messages.last().content.substringBefore("## Runtime").contains("authentication") -> {
                    modelStarted.complete(Unit)
                    awaitCancellation()
                }
                else -> CompletionResponse("", listOf(ToolCall("read", "read_file", emptyMap())))
            }
        })
        val run = async { team.execute("Research authentication and layout documentation", "gpt-4o-mini", "gpt-4o-mini", "/tmp", steering = control).toList() }
        modelStarted.await()
        toolStarted.await()
        control.submit("Change the objective")
        releaseTool.complete(Unit)
        val events = run.await()
        assertFalse(toolCancelled)
        assertEquals(2, plans)
        assertEquals("Corrected result", events.filterIsInstance<SwarmEvent.Completed>().single().summary)
    }
}
