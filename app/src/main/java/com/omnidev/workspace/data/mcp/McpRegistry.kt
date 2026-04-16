package com.omnidev.workspace.data.mcp

import com.omnidev.workspace.data.tools.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class McpRegistry(
    private val configManager: McpConfigManager,
    private val httpClient: McpHttpClient = McpHttpClient()
) {
    // Dynamic mapping of active connections
    private val activeConnections = mutableMapOf<String, McpConnection>()

    /**
     * Fetches all available tools from all configured MCP servers.
     * Converts them into native ToolDefinitions with prefixed names.
     */
    suspend fun fetchAllAvailableTools(): List<ToolDefinition> = withContext(Dispatchers.IO) {
        val servers = configManager.getServers()
        val allTools = mutableListOf<ToolDefinition>()

        // Clear old connections to keep in sync with config updates
        activeConnections.clear()

        for ((serverName, config) in servers) {
            val connection: McpConnection = when (config.type.lowercase()) {
                "http" -> RemoteMcpConnection(config, httpClient)
                "native" -> NativeLocalMcpConnection()
                "git", "hybrid_git" -> HybridGitMcpConnection()
                else -> {
                    println("Unsupported MCP type '${config.type}' for server '$serverName'")
                    continue
                }
            }

            activeConnections[serverName] = connection

            try {
                val tools = connection.getSupportedTools(serverName)
                allTools.addAll(tools)
            } catch (e: Exception) {
                println("Failed to fetch tools from MCP server '$serverName': ${e.message}")
                e.printStackTrace()
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

        // Example tool name: mcp_github-cloud_search_repos
        // We split by '_' but limit to 3 to handle originalToolNames that contain '_'
        val prefixRemoved = toolName.removePrefix("mcp_")
        val underscoreIndex = prefixRemoved.indexOf('_')

        if (underscoreIndex == -1) {
            return@withContext "Error: Invalid MCP tool name format."
        }

        val serverName = prefixRemoved.substring(0, underscoreIndex)
        val originalToolName = prefixRemoved.substring(underscoreIndex + 1)

        val connection = activeConnections[serverName]
            ?: return@withContext "Error: MCP Server '$serverName' not configured or not active."

        try {
            connection.executeTool(serverName, originalToolName, arguments)
        } catch (e: Exception) {
            "Error executing tool via MCP: ${e.message}"
        }
    }
}
