package com.omnidev.workspace.data.mcp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class McpDefaultsTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    @Test fun freshConfigurationHasTheRequestedFiveHttpServers() {
        val defaults = McpDefaults.readSaved(null, json)
        assertEquals(listOf("context7", "usefulai", "microsoft-learn", "github", "mt-manager"), defaults.mcpServers.keys.toList())
        assertEquals(listOf("https://mcp.context7.com/mcp", "https://api.usefulai.fun/mcp",
            "https://learn.microsoft.com/api/mcp", "https://api.githubcopilot.com/mcp", "http://127.0.0.1:8787/mcp"),
            defaults.mcpServers.values.map { it.url })
        assertTrue(defaults.mcpServers.values.all { it.type == "http" && it.tools == listOf("*") && it.env.isEmpty() })
        assertEquals(defaults, McpDefaults.readSaved(json.encodeToString(defaults), json))
    }
    @Test fun preservesExistingCustomConfigurationAndExplicitEmptyConfiguration() {
        val custom = McpConfigWrapper(mapOf("mine" to McpServerConfig("http", "https://example.com/mcp", listOf("read"), mapOf("TOKEN" to "fixture"))))
        assertEquals(custom, McpDefaults.readSaved(json.encodeToString(custom), json))
        assertTrue(McpDefaults.readSaved("{\"mcpServers\":{}}", json).mcpServers.isEmpty())
    }
    @Test fun corruptSavedDataDoesNotSilentlyEnableNewServers() {
        assertTrue(McpDefaults.readSaved("invalid", json).mcpServers.isEmpty())
    }
}
