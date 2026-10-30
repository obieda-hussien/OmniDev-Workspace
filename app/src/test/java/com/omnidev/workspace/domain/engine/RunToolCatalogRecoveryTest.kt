package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunToolCatalogRecoveryTest {
    private val registry = (1..80).map {
        ToolDefinition("tool_$it", "operation_$it", listOf(ToolParameter("path", "string", "Path")))
    }

    @Test fun `ensureLoaded exposes a permitted tool that was not loaded`() {
        val catalog = RunToolCatalog(registry, "operation_1")
        val missing = registry.first { tool -> catalog.definitions().none { it.name == tool.name } }
        assertFalse(catalog.isLoaded(missing.name))
        assertTrue(catalog.ensureLoaded(missing.name))
        assertTrue(catalog.isLoaded(missing.name))
        assertTrue(catalog.definitions().any { it.name == missing.name })
    }

    @Test fun `ensureLoaded never restores a tool outside the permitted registry`() {
        val catalog = RunToolCatalog(registry.take(3), "operation_1")
        assertFalse(catalog.ensureLoaded("tool_80"))
        assertFalse(catalog.isPermitted("tool_80"))
        assertTrue(catalog.definitions().none { it.name == "tool_80" })
    }

    @Test fun `discover without a query falls back to the run objective`() {
        val catalog = RunToolCatalog(registry, "operation_77")
        val result = catalog.discover("")
        assertFalse(result.isError)
        assertTrue(result.output.contains("tool_77"))
    }

    @Test fun `discover query is optional in the advertised schema`() {
        val query = RunToolCatalog.DISCOVER.parameters.single { it.name == "query" }
        assertFalse(query.required)
    }

    @Test fun `discover is still rejected when there is no query and no objective`() {
        val result = RunToolCatalog(registry, "").discover("  ")
        assertTrue(result.isError)
        assertEquals("INVALID_TOOL_ARGUMENTS", result.classification)
    }

    @Test fun `exact tool digest marks required parameters`() {
        val catalog = RunToolCatalog(registry.take(2), "operation_1")
        assertEquals("tool_1(path*); tool_2(path*)", catalog.exactToolDigest())
    }

    @Test fun `exact tool digest is bounded`() {
        val digest = RunToolCatalog(registry, "operation_1").exactToolDigest(limit = 5)
        assertTrue(digest.contains("+75 more"))
        assertEquals(5, digest.substringBefore("; +").split("; ").size)
    }
}
