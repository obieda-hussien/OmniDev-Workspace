package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.tools.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ToolSurfaceTest {
    private fun tool(name: String, description: String = name) = ToolDefinition(name, description,
        listOf(ToolParameter("query", "string", "Query")))
    private val registry = (1..100).map { tool("lookup_$it", "record_$it") } +
        tool("web_search", "Search public documentation") + tool("read_file", "Read source content") +
        tool("patch_file_content", "Patch source content")
    private fun focus(key: String, query: String, check: String = "", revision: String = "p1") =
        ToolSurfaceFocus(revision, key, query, setOf(IntentClassifier.ToolDomain.CODE_TERMINAL), check)

    @Test fun `active step and independent verification precede whole objective`() {
        val catalog = RunToolCatalog(registry, "web_search", budget = ToolSurfaceBudget(maxTools = 2))
        catalog.prepare("web_search", activeStep = focus("S2", "patch_file_content", "read_file"))
        assertEquals(listOf("discover_tools", "read_file", "patch_file_content"), catalog.definitions().map { it.name })
        assertEquals(listOf(ToolSurfaceSource.VERIFICATION, ToolSurfaceSource.ACTIVE_STEP), catalog.surface.entries.map { it.source })
    }

    @Test fun `step or revision change expires on demand tools instead of accumulating history`() {
        val catalog = RunToolCatalog(registry, "web_search", budget = ToolSurfaceBudget(maxTools = 2))
        val first = focus("S1", "read_file")
        catalog.prepare("web_search", activeStep = first)
        assertTrue(catalog.ensureLoaded("lookup_99"))
        catalog.prepare("web_search", activeStep = first)
        assertTrue(catalog.isLoaded("lookup_99"))
        catalog.prepare("web_search", activeStep = first.copy(revision = "p2"))
        assertFalse(catalog.isLoaded("lookup_99"))
        assertTrue(catalog.ensureLoaded("lookup_98"))
        catalog.prepare("web_search", activeStep = focus("S2", "patch_file_content", "read_file", "p2"))
        assertFalse(catalog.isLoaded("lookup_98"))
        assertTrue(catalog.isLoaded("patch_file_content"))
    }

    @Test fun `bounded discovery is optional and follows latest objective and active step`() {
        val catalog = RunToolCatalog(registry, "web_search")
        catalog.prepare("read_file")
        assertTrue(catalog.discover("").output.contains("read_file"))
        catalog.prepare("read_file", activeStep = focus("S2", "patch_file_content"))
        assertTrue(catalog.discover("").output.contains("patch_file_content"))
    }

    @Test fun `restarting rebuilds identical surface from saved focus without persistent catalog state`() {
        val f = focus("S2", "patch_file_content", "read_file")
        fun restore(defs: List<ToolDefinition>) = RunToolCatalog(defs, "web_search").also { it.prepare("web_search", activeStep = f) }
        assertEquals(restore(registry).definitions(), restore(registry.reversed()).definitions())
    }

    @Test fun `seeded registry permutations produce deterministic bounded surfaces`() {
        val random = Random(4401)
        val budget = ToolSurfaceBudget(maxTools = 5, maxSchemaChars = 2_500)
        var expected: List<ToolDefinition>? = null
        repeat(100) {
            val catalog = RunToolCatalog(registry.shuffled(random), "source content", budget = budget)
            catalog.prepare("source content", activeStep = focus("S1", "patch_file_content", "read_file"))
            val defs = catalog.definitions()
            assertTrue(defs.size <= 6)
            assertTrue(catalog.surface.schemaChars <= budget.maxSchemaChars)
            if (expected == null) expected = defs else assertEquals(expected, defs)
        }
    }

    @Test fun `over budget schemas are deferred whole and never executed by discovery`() {
        val huge = ToolDefinition("large_tool", "Large operation", (1..80).map {
            ToolParameter("required_$it", "string", "Required value", required = true)
        })
        val catalog = RunToolCatalog(listOf(huge), "large_tool", budget = ToolSurfaceBudget(maxSchemaChars = 1_500))
        val result = catalog.discover("large_tool")
        assertTrue(result.isError)
        assertEquals("TOOL_SCHEMA_BUDGET", result.classification)
        assertFalse(catalog.ensureLoaded("large_tool"))
        assertTrue(catalog.isPermitted("large_tool"))
        assertEquals("TOOL_NOT_EXPOSED", ToolCallPreflight(catalog.definitions()).check(ToolCall("x", "large_tool", emptyMap()))?.classification)
    }

    @Test fun `specialization preserves complete registered argument contracts`() {
        val definition = ToolDefinition("multi_action", "Operate on records", listOf(
            ToolParameter("action", "string", "Action", allowedValues = listOf("inspect_record", "erase_record")),
            ToolParameter("id", "string", "Record ID", required = false, requiredForActions = listOf("erase_record"))))
        val catalog = RunToolCatalog(listOf(definition), "inspect_record")
        val exposed = catalog.definitions().single { it.name == "multi_action" }
        assertEquals(definition.parameters, exposed.parameters)
        assertEquals("multi_action", catalog.search("erase_record", 1).single().name)
        val preflight = ToolCallPreflight(catalog.definitions())
        assertNull(preflight.check(ToolCall("read", "multi_action", mapOf("action" to "inspect_record"))))
        assertEquals("INVALID_TOOL_ARGUMENTS", preflight.check(ToolCall("write", "multi_action", mapOf("action" to "erase_record")))?.classification)
    }

    @Test fun `hints observations mentions and verification never restore denied tools`() {
        val catalog = RunToolCatalog(registry, "root_shell_tool", preferred = setOf("root_shell_tool"),
            additionalDomains = setOf(IntentClassifier.ToolDomain.ROOT_CONTROL), mentionedToolNames = setOf("root_shell_tool"))
        catalog.prepare("root_shell_tool", "root_shell_tool", focus("S1", "root_shell_tool", "root_shell_tool"))
        assertFalse(catalog.ensureLoaded("root_shell_tool"))
        assertFalse(catalog.discover("root_shell_tool").output.contains("root_shell_tool:"))
        assertFalse(catalog.isPermitted("root_shell_tool"))
    }

    @Test fun `discovery can replace a hint under budget pressure without widening permission`() {
        val catalog = RunToolCatalog(registry, "read_file", mentionedToolNames = setOf("read_file"), budget = ToolSurfaceBudget(maxTools = 1))
        assertTrue(catalog.isLoaded("read_file"))
        assertTrue(catalog.ensureLoaded("web_search"))
        assertEquals(listOf("discover_tools", "web_search"), catalog.definitions().map { it.name })
        assertTrue(catalog.isPermitted("read_file"))
    }

    @Test fun `maximum sized batch can load all exact permitted tools on demand`() {
        val catalog = RunToolCatalog(registry, "web_search")
        val calls = (90..97).map { ToolCall("call_$it", "lookup_$it", mapOf("query" to "value")) }
        val defs = PipelineRecovery.loadOnDemand(calls, catalog.definitions(), catalog)
        assertTrue(ToolCallPreflight(defs).checkBatch(calls).all { it == null })
    }

    @Test fun `diagnostics contain only operational counts and registry metadata`() {
        val catalog = RunToolCatalog(registry, "secret-objective")
        catalog.prepare("password=secret", "private-result", focus("S1", "private-step"))
        val diagnostic = catalog.surface.toString() + catalog.surface.summary()
        listOf("secret-objective", "password=secret", "private-result", "private-step").forEach { assertFalse(diagnostic.contains(it)) }
    }

    @Test fun `small model budget is bounded independently of registry size`() {
        assertEquals(ToolSurfaceBudget(maxTools = 12, maxSchemaChars = 2_457), ToolSurfaceBudget.forContext(4_096))
        assertEquals(ToolSurfaceBudget(), ToolSurfaceBudget.forContext(Int.MAX_VALUE))
    }
}
