package com.omnidev.workspace.data.mcp

import com.omnidev.workspace.OmniDevApp
import com.omnidev.workspace.data.skills.ChatCapabilityStore
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.domain.model.ToolAccessMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class McpRegistry(
    private val configManager: McpConfigManager,
    private val httpClient: McpHttpClient = McpHttpClient()
) {
    private val refreshMutex = Mutex()
    @Volatile private var routes: Map<String, McpToolRoute> = emptyMap()
    private val discovery = McpToolDiscovery(connect = { config ->
        when (config.type.lowercase()) {
            "http", "streamable_http" -> StreamableMcpConnection(config)
            "rest" -> RemoteMcpConnection(config, httpClient)
            "native" -> NativeLocalMcpConnection()
            "git", "hybrid_git" -> HybridGitMcpConnection()
            else -> null
        }
    })

    private fun toolsAllowedForChat(): Boolean = runCatching {
        ChatCapabilityStore.read(OmniDevApp.instance.applicationContext).toolAccessMode != ToolAccessMode.DISABLED
    }.getOrDefault(true)

    /**
     * Returns cached schemas and sessions; stale servers refresh concurrently with bounded discovery.
     * Add-to-chat `Tools = Off` is enforced before any remote discovery happens,
     * so disabling tools also suppresses MCP schemas and avoids unnecessary I/O.
     */
    suspend fun fetchAllAvailableTools(): List<ToolDefinition> = withContext(Dispatchers.IO) {
        if (!toolsAllowedForChat()) {
            routes = emptyMap()
            return@withContext emptyList()
        }

        refreshMutex.withLock {
            val (tools, nextRoutes) = discovery.discover(configManager.getServers())
            if (!toolsAllowedForChat()) {
                routes = emptyMap()
                return@withContext emptyList()
            }
            routes = nextRoutes
            tools
        }
    }

    /** Execute a registered MCP tool while respecting the active chat capability policy. */
    suspend fun executeMcpTool(toolName: String, arguments: Map<String, String>): String = withContext(Dispatchers.IO) {
        if (!toolsAllowedForChat()) {
            return@withContext "Error: Tools are disabled for this chat from Add to chat."
        }
        if (!toolName.startsWith("mcp_")) {
            return@withContext "Error: Not an MCP tool."
        }

        val route = routes[toolName]
            ?: return@withContext "Error: MCP tool is not registered. Refresh the server's tool list."

        if (configManager.getServers()[route.server] != route.config) {
            return@withContext "Error: MCP server configuration changed. Refresh its tool list."
        }
        try {
            route.connection.executeTool(route.server, route.original, arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Error executing tool via MCP: ${e.message}"
        }
    }
}
