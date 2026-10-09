package com.omnidev.workspace.data.mcp

import com.omnidev.workspace.data.tools.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal data class McpToolRoute(
    val server: String,
    val original: String,
    val config: McpServerConfig,
    val connection: McpConnection
)

/** Cache schemas and live sessions, including failed discovery, independently of model turns. */
internal class McpToolDiscovery(
    private val connect: (McpServerConfig) -> McpConnection?,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val timeoutMillis: Long = 3_000,
    private val successTtlMillis: Long = 5 * 60_000,
    private val failureTtlMillis: Long = 60_000
) {
    private data class Entry(
        val config: McpServerConfig,
        val connection: McpConnection?,
        val tools: List<ToolDefinition>,
        val refreshAt: Long
    )
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    suspend fun discover(servers: Map<String, McpServerConfig>): Pair<List<ToolDefinition>, Map<String, McpToolRoute>> = mutex.withLock {
        val snapshot = servers.mapValues { (_, config) -> config.copy(tools = config.tools.toList(), env = config.env.toMap()) }
        entries.keys.retainAll(snapshot.keys)
        val stale = snapshot.filter { (name, config) ->
            val previous = entries[name]
            previous == null || previous.config != config || clock() >= previous.refreshAt
        }
        val refreshed = coroutineScope {
            stale.map { (name, config) -> async {
                val previous = entries[name]?.takeIf { it.config == config }
                var connection = previous?.connection
                val tools = try {
                    connection = connection ?: connect(config)
                    connection?.let { active -> withTimeoutOrNull(timeoutMillis) { active.getSupportedTools(name) } }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                name to Entry(config, if (tools == null) null else connection, tools.orEmpty(),
                    clock() + if (tools == null) failureTtlMillis else successTtlMillis)
            } }.awaitAll()
        }
        entries.putAll(refreshed)
        val tools = mutableListOf<ToolDefinition>()
        val routes = mutableMapOf<String, McpToolRoute>()
        snapshot.keys.forEach { name ->
            val entry = entries.getValue(name)
            val connection = entry.connection ?: return@forEach
            val prefix = "mcp_${name}_"
            entry.tools.filter { it.name.startsWith(prefix) }.forEach { tool ->
                check(tool.name !in routes) { "Duplicate MCP tool name: ${tool.name}" }
                routes[tool.name] = McpToolRoute(name, tool.name.removePrefix(prefix), entry.config, connection)
                tools.add(tool)
            }
        }
        tools to routes
    }
}
