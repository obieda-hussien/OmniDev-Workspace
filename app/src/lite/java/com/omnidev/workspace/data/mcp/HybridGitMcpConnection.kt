package com.omnidev.workspace.data.mcp

class HybridGitMcpConnection : McpConnection {
    override suspend fun getSupportedTools(serverName: String): List<com.omnidev.workspace.data.tools.ToolDefinition> = emptyList()
    override suspend fun executeTool(serverName: String, originalToolName: String, arguments: Map<String, String>): String = "Error: local Git backend is not included in Lite."
}
