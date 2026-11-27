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

data class McpDiscoveryCoverage(
    val includedServers: Int,
    val undiscoveredServers: Int,
    val timedOutServers: Int = 0,
    val failedServers: Int = 0,
    val unsupportedServers: Int = 0,
    val conflictingServers: Int = 0
)

/** Cache schemas and live sessions, including failed discovery, independently of model turns. */
internal class McpToolDiscovery(
    private val connect: (McpServerConfig) -> McpConnection?,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val timeoutMillis: Long = 3_000,
    private val successTtlMillis: Long = 5 * 60_000,
    private val failureTtlMillis: Long = 60_000
) {
    data class Snapshot(
        val tools: List<ToolDefinition>,
        val routes: Map<String, McpToolRoute>,
        val coverage: McpDiscoveryCoverage
    )

    private enum class Omission { TIMEOUT, FAILED, UNSUPPORTED }

    private data class Entry(
        val config: McpServerConfig,
        val connection: McpConnection?,
        val tools: List<ToolDefinition>,
        val refreshAt: Long,
        val omission: Omission?
    )
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    suspend fun discover(servers: Map<String, McpServerConfig>): Snapshot = mutex.withLock {
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
                val (tools, omission) = try {
                    connection = connection ?: connect(config)
                    val active = connection
                    if (active == null) null to Omission.UNSUPPORTED
                    else withTimeoutOrNull(timeoutMillis) { active.getSupportedTools(name) to null }
                        ?: (null to Omission.TIMEOUT)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null to Omission.FAILED
                }
                name to Entry(config, if (tools == null) null else connection, tools.orEmpty(),
                    clock() + if (tools == null) failureTtlMillis else successTtlMillis, omission)
            } }.awaitAll()
        }
        entries.putAll(refreshed)
        val tools = mutableListOf<ToolDefinition>()
        val routes = mutableMapOf<String, McpToolRoute>()
        val conflictingServers = mutableSetOf<String>()
        snapshot.keys.forEach { name ->
            val entry = entries.getValue(name)
            val connection = entry.connection ?: return@forEach
            val prefix = "mcp_${name}_"
            entry.tools.filter { it.name.startsWith(prefix) }.forEach { tool ->
                val route = McpToolRoute(name, tool.name.removePrefix(prefix), entry.config, connection)
                val previous = routes.putIfAbsent(tool.name, route)
                if (previous == null) {
                    tools.add(tool)
                } else {
                    conflictingServers += previous.server
                    conflictingServers += name
                }
            }
        }
        if (conflictingServers.isNotEmpty()) {
            routes.values.removeAll { it.server in conflictingServers }
            tools.removeAll { it.name !in routes }
        }
        val includedServers = entries.values.count { it.connection != null } - conflictingServers.size
        val omissions = entries.values.mapNotNull { it.omission }.groupingBy { it }.eachCount()
        val coverage = McpDiscoveryCoverage(
            includedServers = includedServers,
            undiscoveredServers = snapshot.size - includedServers,
            timedOutServers = omissions[Omission.TIMEOUT] ?: 0,
            failedServers = omissions[Omission.FAILED] ?: 0,
            unsupportedServers = omissions[Omission.UNSUPPORTED] ?: 0,
            conflictingServers = conflictingServers.size
        )
        Snapshot(tools, routes, coverage)
    }
}
