package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.model.ToolCallResult
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolExecutionResult
import com.omnidev.workspace.data.tools.ToolParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineRecoveryTest {
    private val registry = (1..60).map { ToolDefinition("tool_$it", "operation_$it", listOf(ToolParameter("path", "string", "Path"))) }

    @Test fun `a normal final answer is not a stall`() {
        assertNull(PipelineRecovery.stall(AgentRecoveryGovernor(), "The package name is ai.perplexity.app.android.", null))
    }

    @Test fun `a blank reply recovers then falls back to visible text`() {
        val governor = AgentRecoveryGovernor()
        repeat(3) { assertTrue(PipelineRecovery.stall(governor, "", null)!!.recovered) }
        val last = PipelineRecovery.stall(governor, "", "file listed")!!
        assertFalse(last.recovered)
        assertTrue(last.fallbackContent!!.contains("no answer"))
        assertTrue(last.fallbackContent!!.contains("file listed"))
    }

    @Test fun `an exhausted promise keeps the model's own text`() {
        val governor = AgentRecoveryGovernor()
        repeat(2) { PipelineRecovery.stall(governor, "I'll start now:", null) }
        val last = PipelineRecovery.stall(governor, "I'll start now:", null)!!
        assertFalse(last.recovered)
        assertNull(last.fallbackContent)
    }

    @Test fun `duplicate recovery answers every call without executing any`() {
        val calls = listOf(ToolCall("a", "read_file", emptyMap()), ToolCall("b", "list_files", emptyMap()))
        val step = PipelineRecovery.step(AgentRecoveryGovernor(), AgentRecoveryGovernor.Kind.DUPLICATE_CALLS, "read_file", toolCalls = calls)
        assertTrue(step.recovered)
        val results = step.messages.single().toolResults!!
        assertEquals(listOf("a", "b"), results.map { it.toolCallId })
        assertTrue(results.all { it.isError && it.output.contains("DUPLICATE_CALL_BLOCKED") })
    }

    @Test fun `guidance only kinds add no messages`() {
        val step = PipelineRecovery.step(AgentRecoveryGovernor(), AgentRecoveryGovernor.Kind.INVALID_BATCHES, digest = "read_file(path*)")
        assertTrue(step.recovered)
        assertTrue(step.messages.isEmpty())
        assertTrue(step.guidance!!.contains("read_file(path*)"))
    }

    @Test fun `stagnation guidance runs out`() {
        val governor = AgentRecoveryGovernor()
        repeat(2) { assertNotNull(PipelineRecovery.stagnationGuidance(governor, listOf("same probe"))) }
        assertNull(PipelineRecovery.stagnationGuidance(governor, listOf("same probe")))
    }

    @Test fun `loadOnDemand returns the same list when nothing needs loading`() {
        val catalog = RunToolCatalog(registry, "operation_1")
        val current = catalog.definitions()
        val calls = listOf(ToolCall("c", current.first { it.name != RunToolCatalog.DISCOVER.name }.name, emptyMap()))
        assertSame(current, PipelineRecovery.loadOnDemand(calls, current, catalog))
    }

    @Test fun `loadOnDemand exposes a permitted tool and ignores unknown names`() {
        val catalog = RunToolCatalog(registry, "operation_1")
        val current = catalog.definitions()
        val missing = registry.first { tool -> current.none { it.name == tool.name } }
        val after = PipelineRecovery.loadOnDemand(
            listOf(ToolCall("c", missing.name, emptyMap()), ToolCall("d", "unregistered", emptyMap())), current, catalog
        )
        assertTrue(after.any { it.name == missing.name })
        assertTrue(after.none { it.name == "unregistered" })
    }

    @Test fun `successful non discovery results reset recovery budgets`() {
        val governor = AgentRecoveryGovernor()
        val router = AutomaticToolRouter(RunToolCatalog(registry.take(1), "operation_1"))
        repeat(3) { governor.decide(AgentRecoveryGovernor.Kind.EMPTY_RESPONSE) }
        PipelineRecovery.noteProgress(
            listOf(ToolCall("c", "tool_1", emptyMap())), listOf(ToolExecutionResult("ok")), governor, router
        )
        assertTrue(governor.decide(AgentRecoveryGovernor.Kind.EMPTY_RESPONSE) is AgentRecoveryGovernor.Decision.Recover)
    }

    @Test fun `failed results and discovery are not progress`() {
        val governor = AgentRecoveryGovernor()
        val router = AutomaticToolRouter(RunToolCatalog(registry.take(1), "operation_1"))
        repeat(3) { governor.decide(AgentRecoveryGovernor.Kind.EMPTY_RESPONSE) }
        PipelineRecovery.noteProgress(
            listOf(ToolCall("c", "tool_1", emptyMap()), ToolCall("d", RunToolCatalog.DISCOVER.name, emptyMap())),
            listOf(ToolExecutionResult("boom", true), ToolExecutionResult("ok")), governor, router
        )
        assertTrue(governor.decide(AgentRecoveryGovernor.Kind.EMPTY_RESPONSE) is AgentRecoveryGovernor.Decision.GiveUp)
    }

    @Test fun `tool turn content keeps its pivot warning and appends extra guidance last`() {
        val results = listOf(ToolCallResult("1", "read_file", "boom", true), ToolCallResult("2", "list_files", "ok", false))
        val text = ToolTurnContent.build(results, "runtime blocked", "retry with tool_x", false, false, "EXTRA")
        assertTrue(text.startsWith("runtime blocked"))
        assertTrue(text.contains("[read_file] ERROR: boom"))
        assertTrue(text.contains("[list_files] ok"))
        assertTrue(text.contains("One or more tools failed"))
        assertTrue(text.contains("retry with tool_x"))
        assertTrue(text.endsWith("EXTRA"))
    }

    @Test fun `successful turns only get the optional guidance`() {
        val results = listOf(ToolCallResult("1", "list_files", "ok", false))
        assertEquals("[list_files] ok", ToolTurnContent.build(results, null, null, false, false, null))
        assertTrue(ToolTurnContent.build(results, null, null, false, true, null).contains("Prefer answering"))
        assertTrue(ToolTurnContent.build(results, null, null, true, true, null).contains("Launcher changes"))
    }
}
