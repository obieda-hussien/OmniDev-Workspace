package com.omnidev.workspace.data.mcp

import com.omnidev.workspace.data.tools.ToolDefinition

interface McpConnection {
    suspend fun getSupportedTools(serverName: String): List<ToolDefinition>
    suspend fun executeTool(serverName: String, originalToolName: String, arguments: Map<String, String>): String
}
