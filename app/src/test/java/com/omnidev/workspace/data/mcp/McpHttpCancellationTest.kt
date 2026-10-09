package com.omnidev.workspace.data.mcp

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.EventListener
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class McpHttpCancellationTest {
    @Test fun rejectedDiscoveryIsReportedAsFailureForBothTransports() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
        val client = OkHttpClient()
        try {
            val url = "http://127.0.0.1:${server.address.port}/mcp"
            for (streamable in listOf(true, false)) {
                val failure = runCatching {
                    if (streamable) StreamableMcpConnection(McpServerConfig("http", url), client).getSupportedTools("one")
                    else McpHttpClient(client).fetchTools("one", url, emptyMap())
                }.exceptionOrNull()
                assertTrue("Rejected discovery must use failure backoff", failure is IOException)
            }
        } finally {
            server.stop(0)
            client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
        }
    }

    @Test fun cancellationStopsStreamableAndRestRequestsWhileReadingAHangingBody() = runBlocking {
        for (streamable in listOf(true, false)) {
            val bodyStarted = CompletableDeferred<Unit>()
            val readingBody = CompletableDeferred<Unit>()
            val callStopped = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val executor = Executors.newCachedThreadPool()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.executor = executor
            server.createContext("/") { exchange ->
                try {
                    exchange.requestBody.use { it.readBytes() }
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write(": keep-alive\n\n".toByteArray())
                    exchange.responseBody.flush()
                    bodyStarted.complete(Unit)
                    release.await(5, TimeUnit.SECONDS)
                } finally { exchange.close() }
            }
            server.start()
            val client = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS)
                .eventListener(object : EventListener() {
                    override fun responseBodyStart(call: Call) { readingBody.complete(Unit) }
                    override fun callFailed(call: Call, ioe: IOException) { callStopped.complete(Unit) }
                }).build()
            try {
                val url = "http://127.0.0.1:${server.address.port}/mcp"
                val job = async {
                    if (streamable) StreamableMcpConnection(McpServerConfig("http", url), client).getSupportedTools("one")
                    else McpHttpClient(client).fetchTools("one", url, emptyMap())
                }
                withTimeout(3_000) { bodyStarted.await(); readingBody.await() }
                // The server leaves the body open. Cancellation must not wait for the 120-second HTTP deadline.
                withTimeout(1_000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
                withTimeout(2_000) { callStopped.await() }
            } finally {
                release.countDown(); server.stop(0); executor.shutdownNow()
                client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
            }
        }
    }
}
