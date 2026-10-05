package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.tools.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ValidatedToolBatchExecutorTest {
    private val definitions = listOf("read_file", "write_file").map { ToolDefinition(it, it, listOf(
        ToolParameter("path", "string", "Path")
    )) } + RunToolCatalog.DISCOVER
    private fun call(id: String, name: String = "read_file", path: String = id) = ToolCall(id, name, mapOf("path" to path))

    @Test fun `malformed member prevents every side effect`() = runTest {
        var executions = 0
        val results = ValidatedToolBatchExecutor.execute(listOf(call("1", "write_file"), call("2", "imaginary_tool")), definitions, true) {
            executions++; ToolExecutionResult("done")
        }
        assertEquals(0, executions)
        assertTrue(results.all { it.isError })
    }
    @Test fun `policy denial stops whole batch before dispatch`() = runTest {
        var executions = 0
        val results = ValidatedToolBatchExecutor.execute(listOf(call("1"), call("2", "write_file")), definitions, true,
            authorize = { if (!ToolBatchPolicy.isReadOnly(it)) "Read-only worker" else null }) {
            executions++; ToolExecutionResult("done")
        }
        assertEquals(0, executions)
        assertTrue(results.all { it.classification == "TOOL_POLICY_DENIED" })
    }
    @Test fun `reads run concurrently with at most four inflight and preserve result pairing`() = runTest {
        var inflight = 0
        var peak = 0
        val results = ValidatedToolBatchExecutor.execute((1..8).map { call("$it") }, definitions, true) {
            inflight++; peak = maxOf(peak, inflight)
            delay(20)
            inflight--
            ToolExecutionResult(it.id)
        }
        assertEquals(4, peak)
        assertEquals((1..8).map { "$it" }, results.map { it.output })
    }
    @Test fun `equivalent reads share execution only within one batch`() = runTest {
        var executions = 0
        repeat(2) {
            val results = ValidatedToolBatchExecutor.execute(listOf(call("1", path = "a"), call("2", path = "a")), definitions, true) {
                executions++; ToolExecutionResult("value")
            }
            assertEquals(2, results.size)
        }
        assertEquals(2, executions)
    }
    @Test fun `failure blocks later mutation but permits inspection`() = runTest {
        val executed = mutableListOf<String>()
        val results = ValidatedToolBatchExecutor.execute(listOf(call("1"), call("2", "write_file"), call("3")), definitions, true) {
            executed += it.id
            ToolExecutionResult("observation", isError = it.id == "1")
        }
        assertEquals(listOf("1", "3"), executed)
        assertEquals("BATCH_DEPENDENCY_BLOCKED", results[1].classification)
    }
    @Test fun `verification reads are fresh after a mutation`() = runTest {
        var state = "before"
        val results = ValidatedToolBatchExecutor.execute(listOf(call("1", path = "a"), call("2", "write_file", "a"), call("3", path = "a")), definitions, true) {
            if (it.name == "write_file") state = "after"
            ToolExecutionResult(state)
        }
        assertEquals(listOf("before", "after", "after"), results.map { it.output })
    }
    @Test fun `discovery cannot execute a newly found tool in its own batch`() = runTest {
        var executions = 0
        val results = ValidatedToolBatchExecutor.execute(listOf(ToolCall("1", "discover_tools", mapOf("query" to "missing")), call("2", "missing")), definitions, false) {
            executions++; ToolExecutionResult("found")
        }
        assertEquals(0, executions)
        assertTrue(results.all { it.isError })
    }
    @Test fun `cancellation propagates without becoming success or retry`() = runTest {
        try {
            ValidatedToolBatchExecutor.execute(listOf(call("1")), definitions, true) { throw CancellationException("cancel") }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }
}
