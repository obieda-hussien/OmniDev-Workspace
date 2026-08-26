package com.omnidev.workspace.data.tools.research

import com.omnidev.workspace.data.ipc.OmniCoreAgentTool
import com.omnidev.workspace.data.tools.ToolExecutionResult
import com.omnidev.workspace.data.tools.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL

object PageFetchTool {

    private const val MAX_CHARS = 32_000

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "fetch_page",
            description = "Fetches a webpage and extracts its text content. Use this to read documentation, articles, or search results.",
            parameters = listOf(
                com.omnidev.workspace.data.tools.ToolParameter(
                    name = "url",
                    type = "string",
                    description = "The absolute URL to fetch.",
                    required = true
                )
            )
        )
    )

    suspend fun execute(url: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            val code = connection.responseCode
            if (code !in 200..299) {
                return@withContext ToolExecutionResult("HTTP Error $code when fetching page.", isError = true)
            }

            val contentType = connection.contentType ?: ""
            if (!contentType.contains("text/html", ignoreCase = true) && !contentType.contains("text/plain", ignoreCase = true)) {
                return@withContext ToolExecutionResult("Unsupported content type: $contentType. Only text/html and text/plain are supported.", isError = true)
            }

            val html = connection.inputStream.bufferedReader().use { it.readText() }

            val doc = Jsoup.parse(html)

            // Strip boilerplate tags
            doc.select("script, style, nav, footer, header, aside, iframe, noscript, svg, form, button, input, select, textarea").remove()

            val textContent = doc.body().text()

            val truncated = textContent.take(MAX_CHARS)
            ToolExecutionResult(truncated, isError = false)
        } catch (e: Exception) {
            ToolExecutionResult("Failed to fetch page: ${e.message}", isError = true)
        } finally {
            connection?.disconnect()
        }
    }
}
