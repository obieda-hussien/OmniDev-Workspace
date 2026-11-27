package com.omnidev.workspace.data.mcp

import com.omnidev.workspace.data.tools.ToolDefinition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class McpToolDiscoveryTest {
    private fun config(url: String = "https://example.test/mcp") = McpServerConfig("http", url, listOf("*"))
    private class Connection(private val discover: suspend (String) -> List<ToolDefinition>) : McpConnection {
        override suspend fun getSupportedTools(serverName: String) = discover(serverName)
        override suspend fun executeTool(serverName: String, originalToolName: String, arguments: Map<String, String>) = "done"
    }

    @Test fun cachesSchemasAndReusesConnectionsWhenSchemasExpire() = runBlocking {
        var now = 0L
        var creations = 0
        var queries = 0
        val connection = Connection { name -> queries++; listOf(ToolDefinition("mcp_${name}_read", "Read data")) }
        val discovery = McpToolDiscovery(connect = { creations++; connection }, clock = { now }, successTtlMillis = 100)
        val servers = mapOf("one" to config())
        val first = discovery.discover(servers)
        repeat(3) { assertEquals(first.tools, discovery.discover(servers).tools) }
        assertEquals(1, creations); assertEquals(1, queries)
        assertSame(connection, first.routes.getValue("mcp_one_read").connection)
        now = 101
        discovery.discover(servers)
        assertEquals(1, creations); assertEquals(2, queries)
    }

    @Test fun failedDiscoveryHasBackoffAndDoesNotProbeOnEachTurn() = runBlocking {
        var now = 0L
        var queries = 0
        val connection = Connection { queries++; throw IOException("Offline") }
        val discovery = McpToolDiscovery(connect = { connection }, clock = { now }, failureTtlMillis = 100)
        val servers = mapOf("offline" to config())
        assertTrue(discovery.discover(servers).tools.isEmpty())
        repeat(3) { assertTrue(discovery.discover(servers).tools.isEmpty()) }
        assertEquals(1, queries)
        now = 101
        discovery.discover(servers)
        assertEquals(2, queries)
    }

    @Test fun configChangesInvalidateSessionsAndRemovedServersLoseRoutes() = runBlocking {
        var creations = 0
        val discovery = McpToolDiscovery(connect = { creations++; Connection { name -> listOf(ToolDefinition("mcp_${name}_read", "Read data")) } })
        val first = discovery.discover(mapOf("one" to config())).routes.getValue("mcp_one_read")
        val changed = config().copy(env = mapOf("API_KEY" to "replacement-fixture"))
        val next = discovery.discover(mapOf("one" to changed)).routes.getValue("mcp_one_read")
        assertEquals(2, creations); assertNotSame(first.connection, next.connection); assertEquals(changed, next.config)
        assertTrue(discovery.discover(emptyMap()).routes.isEmpty())
    }

    @Test fun hangingServersAreConcurrentBoundedAndDoNotHideHealthyTools() = runBlocking {
        val allStarted = CompletableDeferred<Unit>()
        var started = 0
        var cancelled = 0
        val discovery = McpToolDiscovery(connect = { Connection { name ->
            started++
            if (started == 3) allStarted.complete(Unit)
            if (name == "healthy") {
                allStarted.await()
                listOf(ToolDefinition("mcp_healthy_read", "Read data"))
            } else try { awaitCancellation() } finally { cancelled++ }
        } }, timeoutMillis = 250)
        val result = withTimeout(2_000) { discovery.discover(mapOf("slow1" to config(), "slow2" to config(), "healthy" to config())) }
        assertTrue(allStarted.isCompleted)
        assertEquals(listOf("mcp_healthy_read"), result.tools.map { it.name })
        assertEquals(2, cancelled)
        assertEquals(McpDiscoveryCoverage(1, 2, timedOutServers = 2), result.coverage)
        assertEquals(result.coverage,
            discovery.discover(mapOf("slow1" to config(), "slow2" to config(), "healthy" to config())).coverage)
        assertEquals(3, started)
    }

    @Test fun failedSessionRefreshDropsStaleRoutesAndReconnectsAfterBackoff() = runBlocking {
        var now = 0L
        var fail = false
        var creations = 0
        val discovery = McpToolDiscovery(connect = { creations++; Connection { name ->
            if (fail) throw IOException("Session expired")
            listOf(ToolDefinition("mcp_${name}_read", "Read data"))
        } }, clock = { now }, successTtlMillis = 100, failureTtlMillis = 50)
        val servers = mapOf("one" to config())
        assertFalse(discovery.discover(servers).routes.isEmpty())
        now = 101; fail = true
        assertTrue(discovery.discover(servers).routes.isEmpty())
        fail = false
        assertTrue(discovery.discover(servers).routes.isEmpty())
        assertEquals(1, creations)
        now = 152
        assertFalse(discovery.discover(servers).routes.isEmpty())
        assertEquals(2, creations)
    }

    @Test fun emptySchemaListAndFailedDiscoveryHaveDifferentCoverage() = runBlocking {
        val discovery = McpToolDiscovery(connect = { Connection { name ->
            if (name == "offline") throw IOException("Offline")
            emptyList()
        } })
        val snapshot = discovery.discover(mapOf("empty" to config(), "offline" to config()))

        assertEquals(McpDiscoveryCoverage(includedServers = 1, undiscoveredServers = 1, failedServers = 1), snapshot.coverage)
        assertTrue(snapshot.tools.isEmpty())
        assertEquals(snapshot.coverage, discovery.discover(mapOf("empty" to config(), "offline" to config())).coverage)
        assertEquals(McpDiscoveryCoverage(1, 0), discovery.discover(mapOf("empty" to config())).coverage)
    }

    @Test fun duplicateNamesOnOneServerDoNotHideOtherServers() = runBlocking {
        val discovery = McpToolDiscovery(connect = { Connection { name ->
            val tool = ToolDefinition("mcp_${name}_read", "Read data")
            if (name == "repeated") listOf(tool, tool) else listOf(tool)
        } })

        val result = discovery.discover(mapOf("repeated" to config(), "healthy" to config()))

        assertEquals(listOf("mcp_healthy_read"), result.tools.map { it.name })
        assertEquals(setOf("mcp_healthy_read"), result.routes.keys)
        assertEquals(McpDiscoveryCoverage(1, 1, conflictingServers = 1), result.coverage)
    }

    @Test fun collidingServerPrefixesExcludeBothUntilTheConflictIsRemoved() = runBlocking {
        val discovery = McpToolDiscovery(connect = { Connection { name ->
            when (name) {
                "team" -> listOf(ToolDefinition("mcp_team_notes_read", "Team notes"))
                "team_notes" -> listOf(
                    ToolDefinition("mcp_team_notes_read", "Read notes"),
                    ToolDefinition("mcp_team_notes_list", "List notes")
                )
                else -> listOf(ToolDefinition("mcp_${name}_read", "Read data"))
            }
        } }, clock = { 0L })

        val result = discovery.discover(mapOf("team" to config(), "team_notes" to config(), "healthy" to config()))

        assertEquals(listOf("mcp_healthy_read"), result.tools.map { it.name })
        assertEquals(setOf("mcp_healthy_read"), result.routes.keys)
        assertEquals(McpDiscoveryCoverage(1, 2, conflictingServers = 2), result.coverage)

        val restored = discovery.discover(mapOf("team" to config(), "healthy" to config()))

        assertEquals("team", restored.routes.getValue("mcp_team_notes_read").server)
        assertEquals(McpDiscoveryCoverage(2, 0), restored.coverage)
    }

    @Test fun unsupportedConnectorIsCountedSeparatelyFromDiscoveryFailure() = runBlocking {
        val discovery = McpToolDiscovery(connect = { config ->
            if (config.type == "unsupported") null else Connection { throw IOException("Offline") }
        })
        val result = discovery.discover(mapOf("unsupported" to config().copy(type = "unsupported"), "offline" to config()))

        assertEquals(McpDiscoveryCoverage(0, 2, failedServers = 1, unsupportedServers = 1), result.coverage)
    }

    @Test fun callerCancellationPropagatesToPendingDiscovery() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val discovery = McpToolDiscovery(connect = { Connection {
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        } })
        val job = async { discovery.discover(mapOf("one" to config())) }
        withTimeout(2_000) { started.await() }
        job.cancelAndJoin()
        assertTrue(cancelled.isCompleted)
    }
}
