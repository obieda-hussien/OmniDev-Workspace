package com.omnidev.workspace.data.mcp

import com.omnidev.workspace.data.tools.CompositeToolManager

class McpAgentBridge(
    private val mcpRegistry: McpRegistry,
    private val compositeToolManager: CompositeToolManager
) {
    suspend fun syncToolsToAgent() {
        try {
            val tools = mcpRegistry.fetchAllAvailableTools()
            // Dynamic tools are synced via McpRegistry
        } catch (_: Exception) {}
    }
}
