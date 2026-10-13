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

class AgentSteeringTest {
    private val config = AgentConfig(maxIterations = 8, enableRetry = false, enableMemoryTrimming = false)
    private class Tools(val action: suspend (Map<String, String>) -> Unit = {}) : ToolManager {
        val paths = mutableListOf<String>()
        override fun getToolDefinitions() = listOf(ToolDefinition("write_file", "Write file", listOf(
            ToolParameter("path", "string", "File"), ToolParameter("content", "string", "Content"))))
        override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
            paths += arguments.getValue("path")
            action(arguments)
            return ToolExecutionResult("Written ${arguments.getValue("path")}")
        }
    }
    private fun write(id: String, path: String) = ToolCall(id, "write_file", mapOf("path" to path, "content" to "value"))

    @Test fun `correction during generation cancels old draft and keeps original task`() = runTest {
        val control = RunSteering()
        val started = CompletableDeferred<Unit>()
        var requests = 0
        val pipeline = AgentPipeline(Tools(), completionProvider = { request ->
            if (++requests == 1) { started.complete(Unit); awaitCancellation() }
            assertTrue(request.messages.any { it.content == "Write Kotlin instead" })
            assertTrue(request.messages.any { it.content == "Build the feature" })
            CompletionResponse("Corrected answer")
        }, config = config)
        val run = async { pipeline.execute("Build the feature", modelId = "gpt-4o-mini", scopePath = "/tmp", steering = control).toList() }
        started.await()
        control.submit("Write Kotlin instead")
        val events = run.await()
        assertEquals("Corrected answer", events.filterIsInstance<AgentEvent.FinalAnswer>().single().content)
        assertEquals(1L, events.filterIsInstance<AgentEvent.SteeringApplied>().single().revision)
        assertTrue(events.none { it is AgentEvent.Error })
    }

    @Test fun `in flight mutation settles but remaining old batch actions are skipped`() = runTest {
        val control = RunSteering()
        val tools = Tools { if (it["path"] == "first.kt") control.submit("Keep first.kt, do not write obsolete.kt") }
        var requests = 0
        val pipeline = AgentPipeline(tools, completionProvider = { request ->
            if (++requests == 1) CompletionResponse("", listOf(write("a", "first.kt"), write("b", "obsolete.kt")))
            else {
                assertTrue(request.messages.flatMap { it.toolResults }.any { it.output.contains("Written first.kt") })
                assertTrue(request.messages.flatMap { it.toolResults }.any { it.output.contains("Skipped:") })
                CompletionResponse("Kept first.kt")
            }
        }, config = config)
        val events = pipeline.execute("Write two files", modelId = "gpt-4o-mini", scopePath = "/tmp", steering = control).toList()
        assertEquals(listOf("first.kt"), tools.paths)
        assertEquals("Kept first.kt", events.filterIsInstance<AgentEvent.FinalAnswer>().single().content)
        val usage = events.filterIsInstance<AgentEvent.TokenUsageUpdate>().map { it.totalTokens }
        assertTrue(usage.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun `rapid additions are preserved in order in one continuation`() = runTest {
        val control = RunSteering()
        control.submit("Use Kotlin")
        control.submit("Add accessibility")
        val pipeline = AgentPipeline(Tools(), completionProvider = { request ->
            val corrections = request.messages.filter { it.content == "Use Kotlin" || it.content == "Add accessibility" }
            assertEquals(listOf("Use Kotlin", "Add accessibility"), corrections.map { it.content })
            CompletionResponse("Both applied")
        }, config = config)
        val events = pipeline.execute("Build the feature", modelId = "gpt-4o-mini", scopePath = "/tmp", steering = control).toList()
        assertEquals(2L, events.filterIsInstance<AgentEvent.SteeringApplied>().single().revision)
    }

    @Test fun `worker revision change yields control signal after publishing tool evidence`() = runTest {
        val control = RunSteering()
        val tools = Tools { control.submit("Replan the team") }
        val pipeline = AgentPipeline(tools, completionProvider = { CompletionResponse("", listOf(write("a", "first.kt"))) }, config = config)
        val events = mutableListOf<AgentEvent>()
        try {
            pipeline.execute("Write a file", modelId = "gpt-4o-mini", scopePath = "/tmp", steering = control, steeringRevision = 0).toList(events)
            fail("Expected coordinator replan signal")
        } catch (_: RunRedirected) { }
        assertEquals(1, events.filterIsInstance<AgentEvent.ToolResult>().size)
        assertTrue(events.none { it is AgentEvent.Error || it is AgentEvent.FinalAnswer })
    }
}
