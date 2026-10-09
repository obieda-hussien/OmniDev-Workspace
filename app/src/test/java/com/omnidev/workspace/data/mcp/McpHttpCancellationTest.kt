package com.omnidev.workspace.data.mcp

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
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class McpHttpCancellationTest {
    /** Real loopback HTTP fixture using APIs available to Android unit-test compilation. */
    private class TestServer(private val respond: (Socket) -> Unit) : AutoCloseable {
        private val listener = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val url = "http://127.0.0.1:${listener.localPort}/mcp"
        private val task = executor.submit {
            try {
                while (!listener.isClosed) listener.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val input = socket.getInputStream().buffered()
                    val headers = StringBuilder()
                    while (!headers.endsWith("\r\n\r\n")) {
                        val next = input.read()
                        check(next >= 0 && headers.length < 65_536) { "Incomplete HTTP request" }
                        headers.append(next.toChar())
                    }
                    val length = headers.lines().firstOrNull { it.startsWith("Content-Length:", true) }
                        ?.substringAfter(':')?.trim()?.toInt() ?: 0
                    repeat(length) { check(input.read() >= 0) { "Incomplete HTTP request body" } }
                    respond(socket)
                }
            } catch (error: IOException) {
                if (!listener.isClosed) throw error
            }
        }
        override fun close() {
            listener.close()
            executor.shutdown()
            task.get(2, TimeUnit.SECONDS)
        }
    }

    @Test fun rejectedDiscoveryIsReportedAsFailureForBothTransports() = runBlocking {
        TestServer { socket ->
            socket.getOutputStream().apply {
                write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
        }.use { server ->
            val client = OkHttpClient()
            try {
                for (streamable in listOf(true, false)) {
                    val failure = runCatching {
                        if (streamable) StreamableMcpConnection(McpServerConfig("http", server.url), client).getSupportedTools("one")
                        else McpHttpClient(client).fetchTools("one", server.url, emptyMap())
                    }.exceptionOrNull()
                    assertTrue("Rejected discovery must use failure backoff", failure is IOException)
                }
            } finally {
                client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
            }
        }
    }

    @Test fun cancellationStopsStreamableAndRestRequestsWhileReadingAHangingBody() = runBlocking {
        for (streamable in listOf(true, false)) {
            val bodyStarted = CompletableDeferred<Unit>()
            val readingBody = CompletableDeferred<Unit>()
            val callStopped = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val server = TestServer { socket ->
                socket.getOutputStream().apply {
                    write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                        "Content-Length: 100000\r\nConnection: close\r\n\r\n: keep-alive\n\n").toByteArray())
                    flush()
                }
                bodyStarted.complete(Unit)
                release.await(5, TimeUnit.SECONDS)
            }
            val client = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS)
                .eventListener(object : EventListener() {
                    override fun responseBodyStart(call: Call) { readingBody.complete(Unit) }
                    override fun callFailed(call: Call, ioe: IOException) { callStopped.complete(Unit) }
                }).build()
            try {
                val job = async {
                    if (streamable) StreamableMcpConnection(McpServerConfig("http", server.url), client).getSupportedTools("one")
                    else McpHttpClient(client).fetchTools("one", server.url, emptyMap())
                }
                withTimeout(3_000) { bodyStarted.await(); readingBody.await() }
                // The server leaves the body open. Cancellation must not wait for the 120-second HTTP deadline.
                withTimeout(1_000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
                withTimeout(2_000) { callStopped.await() }
            } finally {
                release.countDown(); server.close()
                client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
            }
        }
    }
}
