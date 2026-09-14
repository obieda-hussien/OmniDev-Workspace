package com.omnidev.workspace.data.mcp

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class StreamableMcpConnectionTest {
    @Test fun initializesSessionListsToolsAndCallsOriginalNameWithTypedArguments() = runTest {
        val methods = mutableListOf<String>()
        var toolParams: JsonObject? = null
        var sessionHeader: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { exchange ->
            val request = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val method = request.getValue("method").jsonPrimitive.content
            methods.add(method)
            if (method == "notifications/initialized") {
                exchange.sendResponseHeaders(202, -1); exchange.close()
            } else {
                val result = when (method) {
                    "initialize" -> {
                        exchange.responseHeaders.add("Mcp-Session-Id", "test-session")
                        """{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"test","version":"1"}}"""
                    }
                    "tools/list" -> """{"tools":[{"name":"read_items","description":"Read","inputSchema":{"type":"object","properties":{"count":{"type":"integer"}},"required":["count"]}}]}"""
                    else -> {
                        sessionHeader = exchange.requestHeaders.getFirst("Mcp-Session-Id")
                        toolParams = request["params"]!!.jsonObject
                        """{"isError":true,"content":[{"type":"text","text":"Not found"}]}"""
                    }
                }
                val response = """{"jsonrpc":"2.0","id":${request["id"]},"result":$result}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
        }
        server.start()
        try {
            val connection = StreamableMcpConnection(McpServerConfig("http", "http://127.0.0.1:${server.address.port}/mcp"))
            val tools = connection.getSupportedTools("my_server")
            assertEquals("mcp_my_server_read_items", tools.single().name)
            assertTrue(connection.executeTool("my_server", "read_items", mapOf("count" to "2")).startsWith("Error:"))
            assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/call"), methods)
            assertEquals("test-session", sessionHeader)
            assertEquals("read_items", toolParams!!["name"]!!.jsonPrimitive.content)
            assertFalse(toolParams!!["arguments"]!!.jsonObject["count"]!!.jsonPrimitive.isString)
        } finally { server.stop(0) }
    }
}
