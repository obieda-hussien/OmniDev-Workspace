package com.omnidev.workspace.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConsoleSerializerTest {

    @Test
    fun `serialize and deserialize preserves phase entries`() {
        val entries = listOf(
            AgentConsoleEntry.PhaseEntry(
                phase = "Analyze",
                detail = "Reviewing request and constraints",
                timestamp = 1234L,
                id = 1L
            ),
            AgentConsoleEntry.TokenEntry(
                totalTokens = 250,
                budget = 1000,
                timestamp = 1235L,
                id = 2L
            )
        )

        val encoded = AgentConsoleSerializer.serialize(entries)
        val decoded = AgentConsoleSerializer.deserialize(encoded)

        assertEquals(2, decoded.size)
        val phase = decoded[0] as AgentConsoleEntry.PhaseEntry
        assertEquals("Analyze", phase.phase)
        assertEquals("Reviewing request and constraints", phase.detail)
        assertEquals(1234L, phase.timestamp)
        assertEquals(1L, phase.id)
    }

    @Test
    fun `deserialize tolerates phase entry without detail`() {
        val encoded = """[{"type":"phase","phase":"Report","ts":2000,"id":9}]"""
        val decoded = AgentConsoleSerializer.deserialize(encoded)
        assertEquals(1, decoded.size)
        val phase = decoded.first() as AgentConsoleEntry.PhaseEntry
        assertEquals("Report", phase.phase)
        assertTrue(phase.detail == null)
    }
    @Test
    fun `all events and statistics survive past the old 120 event limit`() {
        val entries = buildList {
            for (i in 1..240) {
                add(AgentConsoleEntry.ThinkingEntry(iteration = i, timestamp = i.toLong() * 10, id = (i * 3 - 2).toLong()))
                add(AgentConsoleEntry.ToolEntry("tool", "{\"iteration\":$i}", i,
                    timestamp = i.toLong() * 10 + 1, id = (i * 3 - 1).toLong()))
                add(AgentConsoleEntry.ResultEntry("tool", "ok", false,
                    timestamp = i.toLong() * 10 + 2, id = (i * 3).toLong()))
            }
        }
        val compacted = AgentConsoleSerializer.compact(entries)
        assertEquals(720, compacted.size)
        assertEquals(240, compacted.filterIsInstance<AgentConsoleEntry.ThinkingEntry>().size)
        assertEquals(240, compacted.filterIsInstance<AgentConsoleEntry.ToolEntry>().size)
        assertEquals(entries.first().id, compacted.first().id)
        val restored = AgentConsoleSerializer.deserialize(AgentConsoleSerializer.serialize(entries))
        assertEquals(720, restored.size)
        assertEquals(entries.first().id, restored.first().id)
        assertEquals(entries.last().id, restored.last().id)
        assertEquals(240, restored.filterIsInstance<AgentConsoleEntry.ToolEntry>().size)
    }

    @Test
    fun `repeated identical tool calls and results remain distinct events`() {
        val entries = listOf(
            AgentConsoleEntry.ToolEntry("read_file", "{}", 3, id = 1L),
            AgentConsoleEntry.ToolEntry("read_file", "{}", 3, id = 2L),
            AgentConsoleEntry.ResultEntry("read_file", "ok", false, id = 3L),
            AgentConsoleEntry.ResultEntry("read_file", "ok", false, id = 4L)
        )
        assertEquals(entries, AgentConsoleSerializer.compact(entries))
        assertEquals(4, AgentConsoleSerializer.deserialize(AgentConsoleSerializer.serialize(entries)).size)
    }

    @Test
    fun `explicit truncation is possible without changing the default complete history`() {
        val entries = (1..180).map {
            AgentConsoleEntry.ThinkingEntry(iteration = it, id = it.toLong())
        }
        assertEquals(180, AgentConsoleSerializer.compact(entries).size)
        assertEquals(30, AgentConsoleSerializer.compact(entries, maxEntries = 30).size)
    }

}
