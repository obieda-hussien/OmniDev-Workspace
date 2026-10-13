package com.omnidev.workspace.data.mcp

class NativeLocalMcpConnection : McpConnection {
    override suspend fun getSupportedTools(serverName: String): List<com.omnidev.workspace.data.tools.ToolDefinition> = emptyList()
    override suspend fun executeTool(serverName: String, originalToolName: String, arguments: Map<String, String>) = "Error: use the user-delegated document tools in Lite."
}
