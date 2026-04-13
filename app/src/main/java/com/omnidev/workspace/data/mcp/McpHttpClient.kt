package com.omnidev.workspace.data.mcp

import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class McpHttpClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .build()
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Initializes the MCP connection.
     * Note: In a full MCP implementation, this would establish the SSE connection and handle the protocol handshake.
     * For this phase, we are adapting to the standard HTTP flow assuming the endpoint handles standard REST/SSE requests
     * as defined in the simplified target architecture.
     */
    suspend fun initialize(url: String, env: Map<String, String>) = withContext(Dispatchers.IO) {
        // Implement initialize handshake if the specific MCP server requires it.
        // For standard HTTP/SSE tools, this might just verify the endpoint is reachable.
        val requestBuilder = Request.Builder().url("$url/initialize")
        env.forEach { (key, value) ->
            if (key.equals("Authorization", ignoreCase = true) || key.endsWith("_TOKEN", ignoreCase = true) || key.endsWith("_KEY", ignoreCase = true)) {
                requestBuilder.addHeader("Authorization", "Bearer $value")
            } else {
                requestBuilder.addHeader(key, value)
            }
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    println("MCP Initialize failed: ${response.code}")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun fetchTools(serverName: String, url: String, env: Map<String, String>): List<ToolDefinition> = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url("$url/tools/list")

        env.forEach { (key, value) ->
            if (key.equals("Authorization", ignoreCase = true) || key.endsWith("_TOKEN", ignoreCase = true) || key.endsWith("_KEY", ignoreCase = true)) {
                // If the key is specifically an auth token but not named Authorization
                val headerName = if(key.equals("Authorization", true)) key else "Authorization"
                val headerValue = if(value.startsWith("Bearer ", true)) value else "Bearer $value"
                requestBuilder.addHeader(headerName, headerValue)
            } else {
                requestBuilder.addHeader(key, value)
            }
        }

        val request = requestBuilder.build()

        return@withContext try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    println("MCP Fetch Tools failed for $serverName: ${response.code}")
                    return@use emptyList()
                }

                val responseBody = response.body?.string() ?: return@use emptyList()
                val mcpResponse = json.decodeFromString<McpToolsResponse>(responseBody)

                mcpResponse.tools.map { mcpTool ->
                    val prefixedName = "mcp_${serverName}_${mcpTool.name}"

                    val parameters = mutableListOf<ToolParameter>()

                    // Parse inputSchema if present (simplified assumption of schema structure)
                    mcpTool.inputSchema?.jsonObject?.get("properties")?.jsonObject?.forEach { (propName, propDesc) ->
                        val propObj = propDesc.jsonObject
                        val type = propObj["type"]?.jsonPrimitive?.content ?: "string"
                        val desc = propObj["description"]?.jsonPrimitive?.content ?: ""

                        // Check if required
                        val requiredList = mcpTool.inputSchema.jsonObject["required"]?.jsonArray
                        val isRequired = requiredList?.any { it.jsonPrimitive.content == propName } ?: false

                        parameters.add(ToolParameter(
                            name = propName,
                            type = type,
                            description = desc,
                            required = isRequired
                        ))
                    }

                    ToolDefinition(
                        name = prefixedName,
                        description = mcpTool.description,
                        parameters = parameters
                    )
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun executeTool(serverName: String, originalToolName: String, url: String, arguments: Map<String, String>, env: Map<String, String>): String = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url("$url/tools/call")

        env.forEach { (key, value) ->
            if (key.equals("Authorization", ignoreCase = true) || key.endsWith("_TOKEN", ignoreCase = true) || key.endsWith("_KEY", ignoreCase = true)) {
                val headerName = if(key.equals("Authorization", true)) key else "Authorization"
                val headerValue = if(value.startsWith("Bearer ", true)) value else "Bearer $value"
                requestBuilder.addHeader(headerName, headerValue)
            } else {
                requestBuilder.addHeader(key, value)
            }
        }

        // Using JsonElement map directly since values could be anything, but we get String from LLM
        val argElements = arguments.mapValues { JsonPrimitive(it.value) as JsonElement }
        val callRequest = McpToolCallRequest(
            name = originalToolName,
            arguments = JsonObject(argElements)
        )

        val jsonBody = json.encodeToString(callRequest)
        val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
        requestBuilder.post(body)

        return@withContext try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    "Error: MCP Server returned HTTP ${response.code}\n${response.body?.string()}"
                } else {
                    val responseBody = response.body?.string() ?: ""
                    try {
                        val mcpResponse = json.decodeFromString<McpToolCallResponse>(responseBody)
                        if (mcpResponse.isError == true) {
                            "Error: ${mcpResponse.error ?: "Unknown MCP Error"}"
                        } else {
                            mcpResponse.content.joinToString("\n") { it.text }
                        }
                    } catch (e: Exception) {
                        // Fallback if the response isn't strictly the McpToolCallResponse format
                        responseBody
                    }
                }
            }
        } catch (e: IOException) {
            "Error executing MCP tool: ${e.message}"
        }
    }
}

// Data classes matching MCP specification
@Serializable
data class McpToolsResponse(
    val tools: List<McpTool>
)

@Serializable
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonElement? = null
)

@Serializable
data class McpToolCallRequest(
    val name: String,
    val arguments: JsonObject
)

@Serializable
data class McpToolCallResponse(
    val content: List<McpContent>,
    val isError: Boolean? = false,
    val error: String? = null
)

@Serializable
data class McpContent(
    val type: String,
    val text: String
)
