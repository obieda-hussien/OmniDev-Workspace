package com.omnidev.workspace.data.mcp

import com.omnidev.workspace.data.tools.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class McpRegistry(
    private val configManager: McpConfigManager,
    private val httpClient: McpHttpClient = McpHttpClient()
) {

    /**
     * Fetches all available tools from all configured MCP servers.
     * Converts them into native ToolDefinitions with prefixed names.
     */
    suspend fun fetchAllAvailableTools(): List<ToolDefinition> = withContext(Dispatchers.IO) {
        val servers = configManager.getServers()
        val allTools = mutableListOf<ToolDefinition>()

        for ((serverName, config) in servers) {
            // In the future we can branch based on config.type (e.g., "http" vs "stdio")
            if (config.type == "http") {
                try {
                    // Initialize the connection
                    httpClient.initialize(config.url, config.env)
                    // Fetch tools
                    val tools = httpClient.fetchTools(serverName, config.url, config.env)

                    // Filter if 'tools' array in config is not empty and not just ["*"]
                    val allowedTools = config.tools
                    val filteredTools = if (allowedTools.isEmpty() || allowedTools.contains("*")) {
                        tools
                    } else {
                        tools.filter { tool ->
                            val originalName = tool.name.removePrefix("mcp_${serverName}_")
                            allowedTools.contains(originalName)
                        }
                    }

                    allTools.addAll(filteredTools)
                } catch (e: Exception) {
                    println("Failed to fetch tools from MCP server '$serverName': ${e.message}")
                    e.printStackTrace()
                }
            }
        }

        allTools
    }

    /**
     * Executes a tool that was registered via MCP.
     * The toolName must be in the format `mcp_{serverName}_{originalToolName}`.
     */
    suspend fun executeMcpTool(toolName: String, arguments: Map<String, String>): String = withContext(Dispatchers.IO) {
        if (!toolName.startsWith("mcp_")) {
            return@withContext "Error: Not an MCP tool."
        }

        val parts = toolName.removePrefix("mcp_").split("_", limit = 2)
        if (parts.size < 2) {
             return@withContext "Error: Invalid MCP tool name format."
        }

        val serverName = parts[0]
        val originalToolName = parts[1]

        val servers = configManager.getServers()
        val config = servers[serverName] ?: return@withContext "Error: MCP Server '$serverName' not configured."

        if (config.type == "http") {
             httpClient.executeTool(serverName, originalToolName, config.url, arguments, config.env)
        } else {
             "Error: Unsupported MCP transport type '${config.type}'."
        }
    }
}
