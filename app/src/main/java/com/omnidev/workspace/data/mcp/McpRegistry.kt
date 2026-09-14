package com.omnidev.workspace.data.mcp

import com.omnidev.workspace.data.tools.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class McpRegistry(
    private val configManager: McpConfigManager,
    private val httpClient: McpHttpClient = McpHttpClient()
) {
    // Dynamic mapping of active connections
    private data class Route(val server: String, val original: String, val connection: McpConnection)
    @Volatile private var routes: Map<String, Route> = emptyMap()
    private val refreshMutex = Mutex()

    /**
     * Fetches all available tools from all configured MCP servers.
     * Converts them into native ToolDefinitions with prefixed names.
     */
    suspend fun fetchAllAvailableTools(): List<ToolDefinition> = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
        val servers = configManager.getServers()
        val nextRoutes = mutableMapOf<String, Route>()
        val allTools = mutableListOf<ToolDefinition>()

        // Clear old connections to keep in sync with config updates


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



            try {
                val tools = connection.getSupportedTools(serverName)
                tools.forEach { tool ->
                    val prefix = "mcp_${serverName}_"
                    if (tool.name.startsWith(prefix)) {
                        check(tool.name !in nextRoutes) { "Duplicate MCP tool name: ${tool.name}" }
                        nextRoutes[tool.name] = Route(serverName, tool.name.removePrefix(prefix), connection)
                        allTools.add(tool)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("Failed to fetch tools from MCP server '$serverName': ${e.message}")
                e.printStackTrace()
            }
        }

        routes = nextRoutes.toMap()
        allTools
        }
    }

    /**
     * Executes a tool that was registered via MCP.
     * The toolName must be in the format `mcp_{serverName}_{originalToolName}`.
     */
    suspend fun executeMcpTool(toolName: String, arguments: Map<String, String>): String = withContext(Dispatchers.IO) {
        if (!toolName.startsWith("mcp_")) {
            return@withContext "Error: Not an MCP tool."
        }

        val route = routes[toolName]
            ?: return@withContext "Error: MCP tool is not registered. Refresh the server's tool list."

        try {
            route.connection.executeTool(route.server, route.original, arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Error executing tool via MCP: ${e.message}"
        }
    }
}
