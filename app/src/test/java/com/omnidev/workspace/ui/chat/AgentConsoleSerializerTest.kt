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
}
