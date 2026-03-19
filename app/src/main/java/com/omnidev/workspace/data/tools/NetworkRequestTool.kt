package com.omnidev.workspace.data.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Direct HTTP client tool for Android environments where curl/wget are unavailable.
 */
object NetworkRequestTool {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private val ALLOWED_METHODS = setOf("GET", "POST", "PUT", "DELETE")

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "network_request",
            description = "Perform an HTTP request directly from Android's networking stack. " +
                "Useful for API testing when shell tools like curl/wget are unavailable.",
            parameters = listOf(
                ToolParameter("url", "string", "Target URL (http/https).", required = true),
                ToolParameter("method", "string", "HTTP method: GET, POST, PUT, DELETE.", required = false),
                ToolParameter(
                    "headers",
                    "string",
                    "Optional JSON object of headers, e.g. {\"Authorization\":\"Bearer ...\"}.",
                    required = false
                ),
                ToolParameter("body", "string", "Optional request body for POST/PUT.", required = false)
            )
        )
    )

    suspend fun execute(args: Map<String, String>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val url = args["url"]?.trim()
            ?: return@withContext ToolExecutionResult("Missing required argument: url", isError = true)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext ToolExecutionResult("Invalid URL: must start with http:// or https://", isError = true)
        }

        val method = (args["method"] ?: "GET").uppercase()
        if (method !in ALLOWED_METHODS) {
            return@withContext ToolExecutionResult(
                "Invalid method '$method'. Allowed: ${ALLOWED_METHODS.joinToString()}",
                isError = true
            )
        }

        val headers = parseHeaders(args["headers"])
            ?: return@withContext ToolExecutionResult(
                "Invalid 'headers' JSON. Expected object like {\"Header\":\"Value\"}.",
                isError = true
            )

        runCatching {
            executeHttp(url = url, method = method, headers = headers, body = args["body"])
        }.getOrElse {
            ToolExecutionResult("Network request failed: ${it.message}", isError = true)
        }
    }

    private fun executeHttp(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?
    ): ToolExecutionResult {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            doInput = true
        }

        try {
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if ((method == "POST" || method == "PUT") && body != null) {
                conn.doOutput = true
                if (!headers.keys.any { it.equals("Content-Type", ignoreCase = true) }) {
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }
                conn.outputStream.use { os ->
                    os.write(body.toByteArray(Charsets.UTF_8))
                }
            }

            val code = conn.responseCode
            val message = conn.responseMessage ?: ""
            val responseHeaders = conn.headerFields
                .filterKeys { it != null }
                .mapValues { (_, values) -> values.joinToString(", ") }

            val bodyStream = if (code in 200..299) conn.inputStream else conn.errorStream
            val rawBody = bodyStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            val output = buildString {
                appendLine("HTTP $code $message")
                appendLine("Headers:")
                if (responseHeaders.isEmpty()) {
                    appendLine("(none)")
                } else {
                    responseHeaders.forEach { (name, value) ->
                        appendLine("$name: $value")
                    }
                }
                appendLine()
                appendLine("Body:")
                append(rawBody)
            }

            return ToolExecutionResult(output = output, isError = code !in 200..299)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseHeaders(raw: String?): Map<String, String>? {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { key ->
                    put(key, json.optString(key, ""))
                }
            }
        }.getOrNull()
    }
}
