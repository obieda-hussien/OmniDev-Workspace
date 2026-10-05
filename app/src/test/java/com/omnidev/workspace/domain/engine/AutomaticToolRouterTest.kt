package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.data.tools.*
import org.junit.Assert.*
import org.junit.Test

class AutomaticToolRouterTest {
    private val tools = listOf(
        ToolDefinition("message_lookup", "Search messages", listOf(ToolParameter("query", "string", "Search query"))),
        ToolDefinition("file_lookup", "Search files", listOf(ToolParameter("path", "string", "File path")))
    )
    private fun proposal(intent: String, args: String = "{}"): CompletionResponse = CompletionResponse(
        """{"omni_operation":{"intent":"$intent","arguments":$args}}"""
    )

    @Test fun `unique operation becomes a real proposal with unchanged arguments`() {
        val catalog = RunToolCatalog(tools, "messages")
        val call = AutomaticToolRouter(catalog).adapt(proposal("Search messages", """{"query":"Ahmed"}"""), setOf("message_lookup")).toolCalls.single()
        assertEquals("message_lookup", call.name)
        assertEquals(mapOf("query" to "Ahmed"), call.arguments)
        assertTrue(call.textProtocol)
        assertNull(call.argumentError)
    }
    @Test fun `ambiguous search never executes an arbitrary candidate`() {
        val catalog = RunToolCatalog(tools, "Search")
        val call = AutomaticToolRouter(catalog).adapt(proposal("Search"), tools.map { it.name }.toSet()).toolCalls.single()
        assertNotNull(call.argumentError)
        assertTrue(call.argumentError!!.contains("ambiguous"))
    }
    @Test fun `unexposed match requires a second proposal after schema loading`() {
        val catalog = RunToolCatalog(tools, "files")
        val router = AutomaticToolRouter(catalog)
        val rejected = router.adapt(proposal("message_lookup"), emptySet()).toolCalls.single()
        assertNotNull(rejected.argumentError)
        assertTrue(catalog.definitions().any { it.name == "message_lookup" })
        assertNull(router.adapt(proposal("message_lookup"), setOf("message_lookup")).toolCalls.single().argumentError)
    }
    @Test fun `equal concrete matches also require clarification`() {
        val registry = listOf(ToolDefinition("search_a", "Search messages"), ToolDefinition("search_b", "Search messages"))
        val router = AutomaticToolRouter(RunToolCatalog(registry, "messages"))
        assertNotNull(router.adapt(proposal("Search messages"), setOf("search_a", "search_b")).toolCalls.single().argumentError)
    }
    @Test fun `native calls override operation text`() {
        val native = proposal("Search messages").copy(toolCalls = listOf(ToolCall("native", "file_lookup", mapOf("path" to "a"))))
        assertEquals(native, AutomaticToolRouter(RunToolCatalog(tools, "files")).adapt(native, emptySet()))
    }
    @Test fun `malformed envelope and null parameters are rejected`() {
        val router = AutomaticToolRouter(RunToolCatalog(tools, "messages"))
        for (content in listOf("""{"omni_operation":broken""", proposal("Search messages", """{"query":null}""").content)) {
            assertNotNull(router.adapt(CompletionResponse(content), emptySet()).toolCalls.single().argumentError)
        }
    }
    @Test fun `ordinary prose and tool-looking quoted text do not execute`() {
        val router = AutomaticToolRouter(RunToolCatalog(tools, "messages"))
        val prose = CompletionResponse("Example: " + proposal("Search messages").content)
        assertEquals(prose, router.adapt(prose, emptySet()))
    }
    @Test fun `retrieval recovers only twice and never restores denied definitions`() {
        val catalog = RunToolCatalog(tools.take(1), "files")
        val router = AutomaticToolRouter(catalog)
        assertNotNull(router.recovery("files", "file_lookup"))
        assertNotNull(router.recovery("files", "file_lookup"))
        assertNull(router.recovery("files", "file_lookup"))
        assertFalse(catalog.definitions().any { it.name == "file_lookup" })
    }
    @Test fun `observation loads a capability without a discover call`() {
        val registry = (1..80).map { ToolDefinition("tool_$it", "operation_$it") }
        val catalog = RunToolCatalog(registry, "operation_80")
        val missing = registry.first { tool -> catalog.definitions().none { it.name == tool.name } }
        assertTrue(missing.name in catalog.prepare("operation_80", missing.description))
        assertTrue(catalog.definitions().any { it.name == missing.name })
    }
}
