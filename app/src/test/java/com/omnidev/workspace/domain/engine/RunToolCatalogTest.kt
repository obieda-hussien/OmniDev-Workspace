package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.tools.*
import org.junit.Assert.*
import org.junit.Test

class RunToolCatalogTest {
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
