package com.omnidev.workspace.data.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Direct HTTP client tool for Android environments where curl/wget are unavailable.
 *
 * Upgrades in this version:
 * 1. Safe Stream Reading: Prevents OutOfMemory (OOM) by capping the read buffer.
 * 2. PATCH Workaround: Android's HttpURLConnection natively crashes on "PATCH". 
 * This implements the standard `X-HTTP-Method-Override` workaround.
 * 3. Markdown Stripper: LLMs often inject ` ```json ` around the headers. This strips it.
 * 4. Default User-Agent: Bypasses basic Cloudflare/WAF bot-blocks.
 */
object NetworkRequestTool {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    
    /** Max characters to read from the response to prevent LLM context overflow & OOM */
    private const val MAX_RESPONSE_CHARS = 15_000
    
    private val ALLOWED_METHODS = setOf("GET", "POST", "PUT", "DELETE", "PATCH")

    // ─────────────────────────────────────────────────────────────────────
    // Tool Definition
    // ─────────────────────────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "network_request",
            description = "Perform an HTTP request directly from Android's networking stack. " +
                "Useful for API testing, webhooks, or fetching raw JSON/Data. " +
                "Automatically truncates large responses to save context window.",
            parameters = listOf(
                ToolParameter("url", "string", "Target URL (http/https).", required = true),
                ToolParameter("method", "string", "HTTP method: GET, POST, PUT, DELETE, PATCH.", required = false),
                ToolParameter(
                    "headers",
                    "string",
                    "Optional JSON object of headers, e.g. {\"Authorization\":\"Bearer ...\"}.",
                    required = false
                ),
                ToolParameter("body", "string", "Optional request body string for POST/PUT/PATCH.", required = false)
            )
        )
    )

    // ─────────────────────────────────────────────────────────────────────
    // Execution Router
    // ─────────────────────────────────────────────────────────────────────

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
                "Invalid 'headers' JSON format. Expected a flat JSON object like {\"Header\":\"Value\"}.",
                isError = true
            )

        runCatching {
            executeHttp(url = url, method = method, headers = headers, body = args["body"])
        }.getOrElse {
            ToolExecutionResult("Network request failed: ${it.message}", isError = true)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Core HTTP Engine
    // ─────────────────────────────────────────────────────────────────────

    private fun executeHttp(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?
    ): ToolExecutionResult {
        var actualMethod = method
        val finalHeaders = headers.toMutableMap()

        // Android's HttpURLConnection throws ProtocolException for "PATCH".
        // The standard workaround is to use POST with an override header.
        if (actualMethod == "PATCH") {
            actualMethod = "POST"
            finalHeaders["X-HTTP-Method-Override"] = "PATCH"
        }

        // Set a default User-Agent if the LLM didn't provide one to bypass basic bot protection
        if (finalHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            finalHeaders["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 OmniDev/1.0"
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = actualMethod
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
        }

        try {
            // 1. Inject Headers
            finalHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            
            // 2. Inject Body (if applicable)
            val requiresBody = actualMethod == "POST" || actualMethod == "PUT"
            if (requiresBody) {
                conn.doOutput = true
                if (finalHeaders.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }
                
                if (!body.isNullOrEmpty()) {
                    conn.outputStream.use { os ->
                        os.write(body.toByteArray(Charsets.UTF_8))
                    }
                }
            }

            // 3. Await Response
            val code = conn.responseCode
            val message = conn.responseMessage ?: ""
            val responseHeaders = conn.headerFields
                .filterKeys { it != null }
                .mapValues { (_, values) -> values.joinToString(", ") }

            // 4. Safe Body Reading (OOM & Token Protection)
            val bodyStream = if (code in 200..299) conn.inputStream else conn.errorStream
            val rawBodyBuilder = StringBuilder()
            var isTruncated = false

            bodyStream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val buffer = CharArray(4096)
                var read: Int
                while (reader.read(buffer).also { read = it } != -1) {
                    rawBodyBuilder.append(buffer, 0, read)
                    if (rawBodyBuilder.length > MAX_RESPONSE_CHARS) {
                        isTruncated = true
                        break
                    }
                }
            }

            val rawBody = rawBodyBuilder.toString()

            // 5. Format Output
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
                if (rawBody.isEmpty()) {
                    appendLine("(empty body)")
                } else {
                    append(rawBody)
                    if (isTruncated) {
                        appendLine("\n\n[RESPONSE TRUNCATED DUE TO LENGTH LIMIT (${MAX_RESPONSE_CHARS} CHARS)]")
                    }
                }
            }

            return ToolExecutionResult(output = output, isError = code !in 200..299, truncated = isTruncated)
        } finally {
            conn.disconnect()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Parses the headers JSON string safely.
     * Strips Markdown formatting (like ```json ... ```) which LLMs frequently inject.
     */
    private fun parseHeaders(raw: String?): Map<String, String>? {
        if (raw.isNullOrBlank()) return emptyMap()
        
        // Strip markdown backticks if the LLM hallucinated them
        val cleanedRaw = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return runCatching {
            val json = JSONObject(cleanedRaw)
            buildMap {
                json.keys().forEach { key ->
                    put(key, json.optString(key, ""))
                }
            }
        }.getOrNull()
    }
}
