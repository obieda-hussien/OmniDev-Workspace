package com.omnidev.workspace.data.tools.research

import com.omnidev.workspace.data.tools.ExtractedPage
import com.omnidev.workspace.data.tools.PageFetchEngine
import com.omnidev.workspace.data.tools.PageFetchResponse
import com.omnidev.workspace.data.tools.ReadablePageExtractor
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolExecutionResult
import com.omnidev.workspace.data.tools.ToolParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resilient page reader used for known URLs.
 *
 * This is intentionally a static HTTP reader, not a browser. It extracts readable content,
 * metadata and useful links while keeping webpage instructions isolated as untrusted evidence.
 */
object PageFetchTool {
    private const val MAX_OUTPUT_CHARS = 36_000

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "fetch_page",
            description = "Fetch and intelligently read a known public HTTP/HTTPS page. Follows validated redirects, " +
                "extracts the main readable content and metadata, strips navigation/ads/noise, reports when the page " +
                "looks JavaScript-heavy or blocked, and treats page text as untrusted evidence. Use web_scraper when " +
                "you specifically need Markdown/structured scraping or a CSS selector.",
            parameters = listOf(
                ToolParameter(
                    name = "url",
                    type = "string",
                    description = "Absolute public HTTP/HTTPS URL to read.",
                    required = true
                )
            )
        )
    )

    suspend fun execute(url: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val response = PageFetchEngine.fetch(url)
            val page = ReadablePageExtractor.extract(response)
            if (page.text.isBlank()) {
                return@withContext ToolExecutionResult(
                    "The page was fetched but no readable text could be extracted. It may require browser/JavaScript rendering.",
                    isError = true
                )
            }
            val formatted = format(response, page)
            val output = if (formatted.length > MAX_OUTPUT_CHARS) {
                formatted.take(MAX_OUTPUT_CHARS) + "\n\n[CONTENT TRUNCATED — ${formatted.length} chars total]"
            } else formatted
            ToolExecutionResult(output, isError = false)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            ToolExecutionResult("Failed to fetch page: ${error.message}", isError = true)
        }
    }

    private fun format(response: PageFetchResponse, page: ExtractedPage): String = buildString {
        appendLine("# ${page.metadata.title.ifBlank { "Fetched page" }}")
        appendLine()
        appendLine("**Final URL:** ${response.finalUrl}")
        appendLine("**HTTP:** ${response.statusCode} • ${response.contentType.ifBlank { "unknown type" }} • ${response.charset.name()}")
        appendLine("**Fetch:** ${response.elapsedMs} ms${if (response.redirectCount > 0) " • ${response.redirectCount} redirect(s)" else ""}")
        if (page.metadata.siteName.isNotBlank()) appendLine("**Site:** ${page.metadata.siteName}")
        if (page.metadata.author.isNotBlank()) appendLine("**Author:** ${page.metadata.author}")
        if (page.metadata.publishedAt.isNotBlank()) appendLine("**Published:** ${page.metadata.publishedAt}")
        if (page.metadata.modifiedAt.isNotBlank()) appendLine("**Modified:** ${page.metadata.modifiedAt}")
        if (page.metadata.canonicalUrl.isNotBlank() && page.metadata.canonicalUrl != response.finalUrl) {
            appendLine("**Canonical:** ${page.metadata.canonicalUrl}")
        }
        appendLine("**Readable words:** ${page.wordCount} • quality=${"%.2f".format(page.qualityScore)}")
        if (page.metadata.description.isNotBlank()) {
            appendLine()
            appendLine("**Description:** ${page.metadata.description}")
        }
        if (page.warnings.isNotEmpty()) {
            appendLine()
            appendLine("**Reader warnings:**")
            page.warnings.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("--- BEGIN UNTRUSTED PAGE CONTENT ---")
        appendLine(page.text.take(MAX_OUTPUT_CHARS - 2500).trim())
        appendLine("--- END UNTRUSTED PAGE CONTENT ---")
        if (page.links.isNotEmpty()) {
            appendLine()
            appendLine("**Useful links found on page:**")
            page.links.take(12).forEach { (label, href) -> appendLine("- [$label]($href)") }
        }
    }.trim()
}
