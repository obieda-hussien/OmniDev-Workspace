package com.omnidev.workspace.data.mcp

import kotlinx.serialization.Serializable

@Serializable
data class McpConfigWrapper(
    val mcpServers: Map<String, McpServerConfig> = emptyMap()
)

@Serializable
data class McpServerConfig(
    val type: String,
    val url: String,
    val tools: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap()
)
