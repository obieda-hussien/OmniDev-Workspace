package com.omnidev.workspace.data.mcp

import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** MCP 2025-06-18 Streamable HTTP, with JSON-RPC and response-stream support. */
class StreamableMcpConnection(
    private val config: McpServerConfig,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).build()
) : McpConnection {
    private val ids = AtomicLong()
    private val initialization = Mutex()
    private var initialized = false
    private var session: String? = null
    private var protocol = "2025-06-18"
    private val schemas = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()

    private suspend fun ensureInitialized() = initialization.withLock {
        if (!initialized) {
            val result = rpc("initialize", buildJsonObject {
                put("protocolVersion", protocol)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") { put("name", "OmniDev"); put("version", "1.0") }
            })
            val selected = result["protocolVersion"]?.jsonPrimitive?.content ?: throw IOException("MCP server omitted protocol version")
            require(selected in setOf("2025-06-18", "2025-03-26", "2024-11-05")) { "Unsupported MCP protocol: $selected" }
            protocol = selected
            rpc("notifications/initialized", buildJsonObject {}, notification = true)
            initialized = true
        }
    }

    private suspend fun rpc(method: String, params: JsonObject, notification: Boolean = false): JsonObject = withContext(Dispatchers.IO) {
        val id = ids.incrementAndGet()
        val payload = buildJsonObject {
            put("jsonrpc", "2.0"); if (!notification) put("id", id)
            put("method", method); put("params", params)
        }
        val builder = Request.Builder().url(config.url)
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", protocol)
        config.env.forEach { (key, value) ->
            when {
                key.equals("Authorization", true) -> builder.header("Authorization", value)
                key.endsWith("_KEY", true) || key.endsWith("_TOKEN", true) ->
                    builder.header("Authorization", if (value.startsWith("Bearer ", true)) value else "Bearer $value")
                else -> builder.header(key, value)
            }
        }
        session?.let { builder.header("Mcp-Session-Id", it) }
        builder.post(payload.toString().toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("MCP $method failed with HTTP ${response.code}")
            if (method == "initialize") session = response.header("Mcp-Session-Id")
            if (notification) return@withContext buildJsonObject {}
            val body = response.body ?: throw IOException("Empty MCP response")
            fun decode(text: String): JsonObject? {
                val envelope = Json.parseToJsonElement(text).jsonObject
                if (envelope["id"]?.jsonPrimitive?.longOrNull != id) return null
                envelope["error"]?.let { throw IOException("MCP error: $it") }
                return envelope["result"]?.jsonObject ?: throw IOException("MCP response missing result")
            }
            if (response.header("Content-Type").orEmpty().contains("text/event-stream")) {
                val reader = body.charStream().buffered()
                val event = StringBuilder()
                var received = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    received += line.length
                    if (received > 2_000_000) throw IOException("MCP response exceeds size limit")
                    if (line.isBlank() && event.isNotEmpty()) {
                        decode(event.toString())?.let { return@withContext it }
                        event.setLength(0)
                    } else if (line.startsWith("data:")) {
                        if (event.isNotEmpty()) event.append('\n')
                        event.append(line.substringAfter(':').trimStart())
                    }
                }
                throw IOException("MCP stream ended without a matching response")
            }
            val reader = body.charStream()
            val text = StringBuilder()
            val buffer = CharArray(8192)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (text.length + count > 2_000_000) throw IOException("MCP response exceeds size limit")
                text.append(buffer, 0, count)
            }
            decode(text.toString()) ?: throw IOException("MCP response ID mismatch")
        }
    }

    override suspend fun getSupportedTools(serverName: String): List<ToolDefinition> {
        ensureInitialized()
        val tools = mutableListOf<ToolDefinition>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = rpc("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } })
            page["tools"]?.jsonArray?.forEach { item ->
                val tool = item.jsonObject
                val name = tool.getValue("name").jsonPrimitive.content
                if (config.tools.isEmpty() || "*" in config.tools || name in config.tools) {
                    val schema = tool["inputSchema"]?.jsonObject ?: buildJsonObject {}
                    schemas[name] = schema
                    val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                    val parameters = schema["properties"]?.jsonObject?.map { (key, value) ->
                        val prop = value.jsonObject
                        ToolParameter(key, (prop["type"] as? JsonPrimitive)?.content ?: "string",
                            prop["description"]?.jsonPrimitive?.content.orEmpty(), key in required)
                    }.orEmpty()
                    tools.add(ToolDefinition("mcp_${serverName}_$name", tool["description"]?.jsonPrimitive?.content.orEmpty(), parameters))
                }
            }
            cursor = page["nextCursor"]?.jsonPrimitive?.contentOrNull
            if (cursor != null && !cursors.add(cursor!!)) throw IOException("MCP server repeated pagination cursor")
            if (cursors.size > 100) throw IOException("MCP tool catalogue exceeds page limit")
        } while (cursor != null)
        return tools
    }

    override suspend fun executeTool(serverName: String, originalToolName: String, arguments: Map<String, String>): String {
        ensureInitialized()
        val properties = schemas[originalToolName]?.get("properties")?.jsonObject
        val result = rpc("tools/call", buildJsonObject {
            put("name", originalToolName)
            putJsonObject("arguments") { arguments.forEach { (key, value) ->
                val type = (properties?.get(key)?.jsonObject?.get("type") as? JsonPrimitive)?.content
                put(key, if (type in setOf("integer", "number", "boolean", "object", "array")) Json.parseToJsonElement(value) else JsonPrimitive(value))
            } }
        })
        val content = result["content"]?.jsonArray?.joinToString("\n") {
            it.jsonObject["text"]?.jsonPrimitive?.content ?: it.toString()
        }.orEmpty()
        return if (result["isError"]?.jsonPrimitive?.booleanOrNull == true) "Error: $content" else content
    }
}
