package com.omnidev.workspace.data.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class StreamableMcpConnectionTest {
    @Test fun configurationUsesStreamableHttpTypeAndKeepsEndpoint() = runTest {
        val config = McpServerConfig(type = "http", url = "https://example.test/mcp", tools = listOf("read"), env = mapOf("API_KEY" to "token"))
        assertEquals("http", config.type)
        assertEquals("https://example.test/mcp", config.url)
        assertEquals(listOf("read"), config.tools)
        assertEquals("token", config.env["API_KEY"])
    }

    @Test fun serverNamesWithUnderscoresRemainUnambiguous() {
        val server = "my_team_server"
        val tool = "read_items"
        val prefixed = "mcp_${server}_$tool"
        assertEquals("mcp_my_team_server_read_items", prefixed)
        assertEquals(tool, prefixed.removePrefix("mcp_${server}_"))
    }

    @Test fun missingToolsFieldIsUndiscoveredButAnEmptyArrayIsIncluded() = runBlocking {
        val discovery = McpToolDiscovery(connect = { config ->
            val page = if (config.url.endsWith("/empty")) """{"tools":[]}""" else "{}"
            StreamableMcpConnection(config, toolListFixture { page })
        })

        val result = discovery.discover(mapOf(
            "empty" to McpServerConfig("http", "https://example.test/empty"),
            "malformed" to McpServerConfig("http", "https://example.test/malformed")
        ))

        assertEquals(McpDiscoveryCoverage(includedServers = 1, undiscoveredServers = 1), result.coverage)
        assertTrue(result.tools.isEmpty())
        assertTrue(result.routes.isEmpty())
    }

    @Test fun malformedSecondPageDoesNotRegisterAPartialList() = runBlocking {
        val discovery = McpToolDiscovery(connect = { config ->
            StreamableMcpConnection(config, toolListFixture { request ->
                if (request["params"]?.jsonObject?.containsKey("cursor") == true) "{}"
                else """{"tools":[{"name":"read_item","inputSchema":{"type":"object"}}],"nextCursor":"page-2"}"""
            })
        })

        val result = discovery.discover(mapOf("paged" to McpServerConfig("http", "https://example.test/mcp")))

        assertEquals(McpDiscoveryCoverage(includedServers = 0, undiscoveredServers = 1), result.coverage)
        assertTrue(result.tools.isEmpty())
        assertTrue(result.routes.isEmpty())
    }

    private fun toolListFixture(listPage: (JsonObject) -> String): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            requireNotNull(request.body).writeTo(buffer)
            val payload = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val method = payload.getValue("method").jsonPrimitive.content
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("OK")
            if (method == "notifications/initialized") {
                response.code(202).body("".toResponseBody()).build()
            } else {
                val result = when (method) {
                    "initialize" -> """{"protocolVersion":"2025-06-18"}"""
                    "tools/list" -> listPage(payload)
                    else -> error("Unexpected fixture request: $method")
                }
                val id = payload.getValue("id")
                val json = """{"jsonrpc":"2.0","id":$id,"result":$result}"""
                response.code(200).body(json.toResponseBody("application/json".toMediaType())).build()
            }
        }.build()
}
