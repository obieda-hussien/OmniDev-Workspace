package com.omnidev.workspace.data.tools.mcp

import com.omnidev.workspace.data.tools.CompositeToolManager
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.mcp.McpRegistry
import kotlinx.coroutines.flow.first

object McpAgentBridge {
    suspend fun fetchAndRegisterMcpTools(mcpRegistry: McpRegistry, compositeToolManager: CompositeToolManager) {
        try {
            val tools = mcpRegistry.fetchAllAvailableTools()
            // Dynamic injection is handled in AgentPipeline.kt during setup.
        } catch (e: Exception) {
            // Ignore if MCP fetching fails
        }
    }
}
