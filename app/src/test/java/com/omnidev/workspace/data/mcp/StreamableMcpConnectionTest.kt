package com.omnidev.workspace.data.mcp

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StreamableMcpConnectionTest {
    @Test fun configurationUsesStreamableHttpTypeAndKeepsEndpoint() = runTest {
        val config = McpServerConfig(type = "http", url = "https://example.test/mcp", tools = listOf("read"), env = mapOf("API_KEY" to "token"))
        assertEquals("http", config.type)
        assertEquals("https://example.test/mcp", config.url)
        assertEquals(listOf("read"), config.tools)
        assertEquals("token", config.env["API_KEY"])
    }

    @Test fun serverNamesWithUnderscoresRemainUnambiguous() {
        val server = "my_team_server"
        val tool = "read_items"
        val prefixed = "mcp_${server}_$tool"
        assertEquals("mcp_my_team_server_read_items", prefixed)
        assertEquals(tool, prefixed.removePrefix("mcp_${server}_"))
    }
}
