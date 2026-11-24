package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.tools.*
import org.junit.Assert.*
import org.junit.Test

class RunToolCatalogTest {
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
