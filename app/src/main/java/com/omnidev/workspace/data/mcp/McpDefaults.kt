package com.omnidev.workspace.data.mcp

import kotlinx.serialization.json.Json

/** Used only when no configuration has been saved; an explicitly empty configuration stays empty. */
object McpDefaults {
    fun configuration() = McpConfigWrapper(linkedMapOf(
        "context7" to server("https://mcp.context7.com/mcp"),
        "usefulai" to server("https://api.usefulai.fun/mcp"),
        "microsoft-learn" to server("https://learn.microsoft.com/api/mcp"),
        "github" to server("https://api.githubcopilot.com/mcp"),
        "mt-manager" to server("http://127.0.0.1:8787/mcp")
    ))
    private fun server(url: String) = McpServerConfig(type = "http", url = url, tools = listOf("*"), env = emptyMap())

    internal fun readSaved(value: String?, json: Json): McpConfigWrapper =
        if (value == null) configuration() else runCatching { json.decodeFromString<McpConfigWrapper>(value) }
            .getOrDefault(McpConfigWrapper())
}
