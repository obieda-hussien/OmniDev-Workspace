package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.tools.*
import org.junit.Assert.*
import org.junit.Test

class ToolCallPreflightTest {
    private val guard = ToolCallPreflight(listOf(ToolDefinition("edit", "Edit", listOf(
        ToolParameter("action", "string", "Action", allowedValues = listOf("read", "write")),
        ToolParameter("path", "string", "Path"),
        ToolParameter("content", "string", "Content", false, requiredForActions = listOf("write")),
        ToolParameter("count", "integer", "Count", false),
        ToolParameter("enabled", "boolean", "Flag", false),
        ToolParameter("ratio", "number", "Ratio", false),
        ToolParameter("payload", "object", "Payload", false),
        ToolParameter("items", "array", "Items", false)
    ))))
    private fun call(args: Map<String, String> = emptyMap(), name: String = "edit") = ToolCall("1", name, args)
    private val read = mapOf("action" to "read", "path" to "a")

    @Test fun `invented and case changed names are refused`() {
        assertEquals("TOOL_NOT_EXPOSED", guard.check(call(read, "Edit"))?.classification)
        assertNotNull(guard.check(call(read, "delete_everything")))
    }
    @Test fun `missing and extra keys are refused`() {
        assertNotNull(guard.check(call(mapOf("path" to "a"))))
        assertNotNull(guard.check(call(read + ("Path" to "b"))))
    }
    @Test fun `explicit action enum prevents invented operations`() {
        assertNotNull(guard.check(call(read + ("action" to "delete"))))
        assertNotNull(guard.check(call(read + ("action" to ""))))
        assertNull(guard.check(call(read)))
    }
    @Test fun `action dependent parameters are required but empty file content remains valid`() {
        assertNotNull(guard.check(call(read + ("action" to "write"))))
        assertNull(guard.check(call(read + mapOf("action" to "write", "content" to ""))))
    }
    @Test fun `numeric boolean and container types are checked`() {
        for ((key, value) in listOf("count" to "1.2", "enabled" to "yes", "ratio" to "NaN", "payload" to "[]", "items" to "{}")) {
            assertNotNull(key, guard.check(call(read + (key to value))))
        }
        assertNull(guard.check(call(read + mapOf("count" to "2", "enabled" to "true", "ratio" to "0.5", "payload" to "{}", "items" to "[]"))))
    }
    @Test fun `decode errors cannot become zero argument calls`() {
        val zero = ToolCallPreflight(listOf(ToolDefinition("erase", "Erase")))
        assertNotNull(zero.check(ToolCall("1", "erase", emptyMap(), argumentError = "Invalid JSON")))
    }
    @Test fun `invalid member blocks every call in batch`() {
        val results = guard.checkBatch(listOf(call(read), call(read, "invented").copy(id = "2")))
        assertTrue(results.all { it?.isError == true })
        assertEquals("BATCH_PREFLIGHT_BLOCKED", results[0]?.classification)
    }
    @Test fun `duplicate mutations and ids are rejected`() {
        assertTrue(guard.checkBatch(listOf(call(read), call(read))).all { it != null })
        assertTrue(guard.checkBatch(listOf(call(read), call(read).copy(id = "2"))).all { it != null })
    }
    @Test fun `oversized batch is rejected`() {
        assertTrue(guard.checkBatch((1..9).map { call(read).copy(id = "$it") }).all { it != null })
    }
}
