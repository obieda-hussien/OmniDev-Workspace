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
        repeat(3) { assertEquals(first.first, discovery.discover(servers).first) }
        assertEquals(1, creations); assertEquals(1, queries)
        assertSame(connection, first.second.getValue("mcp_one_read").connection)
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
        assertTrue(discovery.discover(servers).first.isEmpty())
        repeat(3) { assertTrue(discovery.discover(servers).first.isEmpty()) }
        assertEquals(1, queries)
        now = 101
        discovery.discover(servers)
        assertEquals(2, queries)
    }

    @Test fun configChangesInvalidateSessionsAndRemovedServersLoseRoutes() = runBlocking {
        var creations = 0
        val discovery = McpToolDiscovery(connect = { creations++; Connection { name -> listOf(ToolDefinition("mcp_${name}_read", "Read data")) } })
        val first = discovery.discover(mapOf("one" to config())).second.getValue("mcp_one_read")
        val changed = config().copy(env = mapOf("API_KEY" to "replacement-fixture"))
        val next = discovery.discover(mapOf("one" to changed)).second.getValue("mcp_one_read")
        assertEquals(2, creations); assertNotSame(first.connection, next.connection); assertEquals(changed, next.config)
        assertTrue(discovery.discover(emptyMap()).second.isEmpty())
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
        assertEquals(listOf("mcp_healthy_read"), result.first.map { it.name })
        assertEquals(2, cancelled)
        discovery.discover(mapOf("slow1" to config(), "slow2" to config(), "healthy" to config()))
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
        assertFalse(discovery.discover(servers).second.isEmpty())
        now = 101; fail = true
        assertTrue(discovery.discover(servers).second.isEmpty())
        fail = false
        assertTrue(discovery.discover(servers).second.isEmpty())
        assertEquals(1, creations)
        now = 152
        assertFalse(discovery.discover(servers).second.isEmpty())
        assertEquals(2, creations)
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
