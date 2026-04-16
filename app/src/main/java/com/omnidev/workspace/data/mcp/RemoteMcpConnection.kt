package com.omnidev.workspace.data.mcp

import com.omnidev.workspace.data.tools.ToolDefinition

class RemoteMcpConnection(
    private val config: McpServerConfig,
    private val httpClient: McpHttpClient
) : McpConnection {

    private var isInitialized = false

    override suspend fun getSupportedTools(serverName: String): List<ToolDefinition> {
        if (!isInitialized) {
            httpClient.initialize(config.url, config.env)
            isInitialized = true
        }
        val tools = httpClient.fetchTools(serverName, config.url, config.env)

        // Filter based on config.tools ("*" means all)
        val allowedTools = config.tools
        return if (allowedTools.isEmpty() || allowedTools.contains("*")) {
            tools
        } else {
            tools.filter { tool ->
                val originalName = tool.name.removePrefix("mcp_${serverName}_")
                allowedTools.contains(originalName)
            }
        }
    }

    override suspend fun executeTool(serverName: String, originalToolName: String, arguments: Map<String, String>): String {
        if (!isInitialized) {
            httpClient.initialize(config.url, config.env)
            isInitialized = true
        }
        return httpClient.executeTool(serverName, originalToolName, config.url, arguments, config.env)
    }
}
