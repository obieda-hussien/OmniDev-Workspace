package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.mcp.McpDiscoveryCoverage
import com.omnidev.workspace.data.tools.*
import org.junit.Assert.*
import org.junit.Test

class RunToolCatalogTest {
    @Test fun `escaped schema entries count against the text tool budget`() {
        val escaped = ToolDefinition("escaped_tool", "A tool with escaped values", listOf(
            ToolParameter("argument", "string", "Required value", allowedValues = listOf("\\".repeat(750)))
        ))
        val fallback = ToolDefinition("small_tool", "A compact tool")
        val budget = ToolSurfaceBudget(maxSchemaChars = 1_500)
        val candidates = listOf(escaped, fallback).map { ToolSurfaceCandidate(it, ToolSurfaceSource.OBJECTIVE) }

        val selection = ToolSurfaceSelector.select(candidates, budget)
        val rendered = ToolTextProtocol.schemas(selection.definitions)

        assertTrue(rendered.length <= budget.maxSchemaChars)
        assertEquals(rendered.length, selection.snapshot.schemaChars)
        assertEquals(listOf("discover_tools", "small_tool"), selection.definitions.map { it.name })
        assertEquals(1, selection.snapshot.omitted)
    }

    @Test fun `inventory enumerates all permitted tools without expanding schemas`() {
        val run = RunToolCatalog(registry, "all available tools and capabilities")
        val before = run.definitions()
        val pages = listOf(run.discover(""), run.discover("inventory:40"), run.discover("inventory:80"))
        assertTrue(pages.none { it.isError })
        val names = pages.flatMap { result ->
            result.output.lineSequence().filter { it.startsWith("registered_") }
                .map { it.substringBefore(" — ") }.toList()
        }
        assertEquals(registry.map { it.name }.sorted(), names)
        assertTrue(pages.first().output.contains("inventory:40"))
        assertTrue(pages.last().output.contains("End of inventory"))
        assertEquals(before, run.definitions())
        assertTrue(run.discover("inventory:-1").isError)
        assertTrue(run.discover("inventory:121").isError)
        assertTrue(run.discover("inventory:bad").isError)
    }

    @Test fun `inventory separates registered authorized and loaded totals`() {
        val allowed = listOf(ToolDefinition("read_file", "Read a file"), ToolDefinition("web_search", "Search the web"))
        val run = RunToolCatalog(
            allowed, "read_file", mentionedToolNames = setOf("read_file"), registeredToolCount = 5
        )

        val page = run.discover("inventory")
        assertTrue(page.output.contains("Registered: 5; authorized: 2; schemas loaded: 1."))
        assertTrue(page.output.contains("Live backend availability: unknown."))
        assertTrue(page.output.contains("read_file — schema loaded"))
        assertTrue(page.output.contains("web_search — schema on demand"))
        assertEquals(setOf("discover_tools", "read_file"), run.definitions().map { it.name }.toSet())

        val reply = OmniIdentityReply.reply("list all tools", null, run)!!
        assertTrue(reply.contains("Registered tools: 5. Authorized for this session: 2. Schemas loaded: 1."))
        assertTrue(reply.contains("Live backend availability is unknown."))
    }

    @Test fun `partial MCP discovery is visible without loading schemas or claiming live readiness`() {
        val run = RunToolCatalog(registry, "all tools", mcpCoverage = McpDiscoveryCoverage(2, 1, timedOutServers = 1))
        val before = run.definitions()

        val page = run.discover("inventory:40").output
        val reply = OmniIdentityReply.reply("list all tools", null, run)!!

        assertTrue(page.contains("Servers included: 2. Undiscovered: 1."))
        assertTrue(reply.contains("Servers included: 2. Undiscovered: 1."))
        assertTrue(page.contains("Omission reasons: timed out: 1."))
        assertTrue(reply.contains("Omission reasons: timed out: 1."))
        assertTrue(page.contains("Live backend availability: unknown."))
        assertEquals(before, run.definitions())
    }

    @Test fun `inventory can report observed backend health without changing authorization or schemas`() {
        val run = RunToolCatalog(registry, "all tools")
        val before = run.definitions()
        val backends = RuntimeBackendLedger()
        backends.observe(
            ToolCall("check", "agent_runtime", mapOf("action" to "env_probe")),
            ToolExecutionResult(
                "probe",
                backend = "runtime-probe",
                verification = SetupState(termuxPrefix = "/termux").probeEvidence()
            )
        )

        val page = run.discover("inventory:40", backends.availabilitySummary())

        assertTrue(page.output.contains("android-shell=unavailable, termux=ready, rish=unavailable"))
        assertTrue(page.output.contains("Other backends: unknown."))
        assertTrue(page.output.contains("authorized: 120"))
        assertEquals(before, run.definitions())
        assertTrue(run.discover("inventory").output.contains("Live backend availability: unknown."))
    }

    @Test fun `source filter pages only authorized registrations without reloading schemas`() {
        val local = ToolDefinition("mcp_named_local", "A local tool")
        val remote = (1..43).map { ToolDefinition("remote_$it", "A discovered tool") }
        val catalog = RunToolCatalog(
            listOf(local) + remote, "all tools",
            registeredToolCount = 45,
            mcpToolNames = remote.map { it.name }.toSet() + "disabled_tool"
        )
        val before = catalog.definitions()

        val first = catalog.discover("inventory:mcp")
        val second = catalog.discover("inventory:mcp:40")
        val localPage = catalog.discover("inventory:local")
        assertFalse(first.isError)
        assertFalse(second.isError)
        assertFalse(localPage.isError)
        assertTrue(first.output.contains("Source: mcp; total: 43"))
        assertTrue(first.output.contains("inventory:mcp:40"))
        assertTrue(second.output.contains("End of inventory."))
        val remoteNames = listOf(first, second).flatMap { page ->
            page.output.lineSequence().filter { it.startsWith("remote_") }
                .map { it.substringBefore(" — ") }.toList()
        }
        assertEquals(remote.map { it.name }.sorted(), remoteNames)
        assertFalse(first.output.contains("disabled_tool"))
        assertFalse(second.output.contains(local.name))
        assertTrue(localPage.output.contains("Source: local; total: 1"))
        assertTrue(localPage.output.contains(local.name))
        assertTrue(catalog.discover("inventory:mcp:44").isError)
        assertEquals(before, catalog.definitions())

        val reply = OmniIdentityReply.reply("list all tools", null, catalog)!!
        assertTrue(reply.contains("`remote_1` [mcp]"))
        assertTrue(reply.contains("`mcp_named_local` [local]"))
        assertFalse(reply.contains("disabled_tool"))
    }

    @Test fun `category cursor keeps the same filter and rejects invalid offsets`() {
        val code = (1..44).map { ToolDefinition("build_project_$it", "Build a project") }
        val browser = ToolDefinition("web_search", "Search the web")
        val run = RunToolCatalog(code + browser, "all tools")
        val first = run.discover("inventory:code_terminal")
        val second = run.discover("inventory:code_terminal:40")

        assertFalse(first.isError)
        assertFalse(second.isError)
        assertTrue(first.output.contains("category", ignoreCase = true))
        assertTrue(first.output.contains("inventory:code_terminal:40"))
        assertTrue(second.output.contains("End of inventory."))
        val names = listOf(first, second).flatMap { page ->
            page.output.lineSequence().filter { it.startsWith("build_project_") }
                .map { it.substringBefore(" — ") }.toList()
        }
        assertEquals(code.map { it.name }.sorted(), names)
        assertFalse(first.output.contains("web_search"))
        assertFalse(second.output.contains("web_search"))
        assertTrue(run.discover("inventory:code_terminal:45").isError)
        assertTrue(run.discover("inventory:code_terminal:0:1").isError)
        assertTrue(run.discover("inventory:imaginary:0").isError)
    }

    @Test fun `schema loading preserves current observation hints until the next prepare`() {
        val catalog = RunToolCatalog(registry, "web_search")
        val initiallyDeferred = registry.filterNot { catalog.isLoaded(it.name) }
        val observation = initiallyDeferred[0].name
        val requested = initiallyDeferred[1].name
        catalog.prepare("web_search", observation)

        assertEquals(ToolSurfaceSource.OBSERVATION, catalog.surface.entries.singleOrNull { it.name == observation }?.source)
        assertFalse(catalog.discover(requested).isError)
        assertEquals(ToolSurfaceSource.OBSERVATION, catalog.surface.entries.singleOrNull { it.name == observation }?.source)

        catalog.prepare("web_search")
        assertFalse(catalog.isLoaded(observation))
        assertTrue(catalog.isLoaded(requested))
    }

    @Test fun `mentions preload priority tools while discovery can load supporting tools`() {
        val selected = (1..12).map { "registered_$it" }.toSet()
        val catalog = RunToolCatalog(registry, "registered_120", mentionedToolNames = selected)
        assertEquals(selected + "discover_tools", catalog.definitions().map { it.name }.toSet())
        assertTrue(catalog.isPermitted("registered_120"))
        catalog.prepare("registered_120", "registered_119")
        catalog.discover("registered_118")
        assertTrue(catalog.definitions().any { it.name == "registered_118" })
        for (number in 13..120) catalog.discover("registered_$number")
        assertTrue(catalog.definitions().map { it.name }.containsAll(selected))
        assertTrue(catalog.definitions().size <= 41)
    }

    private val registry = (1..120).map { ToolDefinition("registered_$it", "Registered operation $it", listOf(
        ToolParameter("path", "string", "Exact target path")
    )) }

    @Test fun `failure telemetry cannot retrieve unrelated domains without lexical evidence`() {
        val run = RunToolCatalog(registry, "registered_120")
        assertTrue(run.search("failed returned error class tool_error false persistent", 6, lexicalOnly = true).isEmpty())
    }

    @Test fun `workspace search puts project tools ahead of unrelated search domains`() {
        val tools = listOf(ToolDefinition("search_knowledge", "Search workspace memory for file operations"),
            ToolDefinition("search_messages", "Search chat history"), ToolDefinition("search_codebase", "Find source matches"))
        val catalog = RunToolCatalog(tools, "file operations or workspace search")
        assertEquals("search_codebase", catalog.search("file operations or workspace search", 1).single().name)
        assertFalse(catalog.discover("").isError)
    }
    @Test fun `initial schema set is small and includes discovery`() {
        val run = RunToolCatalog(registry, "registered_119")
        assertTrue(run.definitions().size <= 25)
        assertTrue(run.definitions().any { it.name == "discover_tools" })
        assertTrue(run.definitions().any { it.name == "registered_119" })
        val newCost = run.definitions().sumOf { it.toString().length }
        assertTrue(newCost < registry.sumOf { it.toString().length } / 2)
    }
    @Test fun `exact names outrank partial text and historical quality`() {
        val run = RunToolCatalog(registry, "registered_120", quality = mapOf("registered_1" to 1f))
        assertEquals("registered_120", run.search("registered_120", 1).single().name)
    }
    @Test fun `empty discovery query falls back to the current run objective`() {
        val target = ToolDefinition(
            "read_project_file",
            "Read a file inside the project workspace",
            listOf(ToolParameter("path", "string", "Exact file path"))
        )
        val run = RunToolCatalog(listOf(target), "read project file")
        assertFalse(RunToolCatalog.DISCOVER.parameters.single().required)

        val result = run.discover("")
        assertFalse(result.isError)
        assertTrue(result.output.contains("read_project_file"))
    }

    @Test fun `discovery loads schemas for the next request and stays bounded`() {
        val run = RunToolCatalog(registry, "registered_120")
        for (number in 1..120) {
            assertFalse(run.discover("registered_$number").isError)
            assertTrue(run.definitions().any { it.name == "registered_$number" })
            assertTrue(run.definitions().size <= 41)
        }
    }
    @Test fun `unregistered and disabled tools cannot be restored by preferences`() {
        val run = RunToolCatalog(registry.take(2), "erase_all", preferred = setOf("erase_all"))
        run.discover("erase_all")
        assertTrue(run.definitions().all { it.name in setOf("discover_tools", "registered_1", "registered_2") })
    }
    @Test fun `Unicode intent retrieves Arabic operation descriptions`() {
        val run = RunToolCatalog(listOf(ToolDefinition("a", "البحث عن الرسائل القديمة"), ToolDefinition("b", "قراءة الملفات")), "الرسائل")
        assertEquals("a", run.search("الرسائل", 1).single().name)
    }
}
