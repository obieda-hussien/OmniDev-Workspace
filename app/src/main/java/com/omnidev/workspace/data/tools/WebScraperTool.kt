package com.omnidev.workspace.data.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Structured, defensive web scraper for articles, docs and research pages.
 *
 * The scraper shares the same hardened fetch and readability pipeline as fetch_page and deep research,
 * then renders clean Markdown with metadata and explicit untrusted-content boundaries.
 */
object WebScraperTool {
    private const val MAX_OUTPUT_CHARS = 36_000
    private const val MAX_CHARS_PER_PAGE = 12_000
    private const val MAX_COMBINED_OUTPUT_CHARS = 64_000
    private const val MAX_URLS_MULTIPLE = 8
    private const val MULTI_FETCH_TIMEOUT_MS = 22_000L

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "web_scraper",
            description = "Fetch a public webpage with redirect/network safety checks, isolate the most readable content, " +
                "extract metadata and useful links, and convert the result to token-efficient Markdown. Reports likely " +
                "JavaScript/paywall/blocking issues instead of silently returning junk. Supports an optional CSS selector.",
            parameters = listOf(
                ToolParameter("url", "string", "Public http:// or https:// URL to scrape.", required = true),
                ToolParameter("selector", "string", "Optional CSS selector to target a specific section.", required = false),
                ToolParameter("mode", "string", "Optional output mode: full (default), content, metadata, or links.", required = false),
                ToolParameter("max_chars", "integer", "Optional output cap from 1000 to 50000 characters.", required = false)
            )
        ),
        ToolDefinition(
            name = "scrape_multiple",
            description = "Scrape up to 8 public pages in parallel with independent failure isolation and structured Markdown output. " +
                "URLs may be comma- or newline-separated. Useful for source comparison and deep research.",
            parameters = listOf(
                ToolParameter("urls", "string", "Comma- or newline-separated URLs (up to 8).", required = true),
                ToolParameter("selector", "string", "Optional CSS selector applied to every page.", required = false),
                ToolParameter("mode", "string", "Optional output mode: full (default), content, metadata, or links.", required = false),
                ToolParameter("max_chars", "integer", "Optional per-page output cap from 1000 to 20000 characters.", required = false)
            )
        )
    )

    suspend fun executeTool(action: String, args: Map<String, String>): ToolExecutionResult {
        return when (action) {
            "web_scraper" -> {
                val url = args["url"] ?: return ToolExecutionResult("Missing 'url' parameter.", isError = true)
                execute(
                    url = url,
                    selector = args["selector"],
                    mode = args["mode"],
                    maxChars = args["max_chars"]?.toIntOrNull()
                )
            }
            "scrape_multiple" -> {
                val raw = args["urls"] ?: return ToolExecutionResult("Missing 'urls' parameter.", isError = true)
                val urls = raw.split(Regex("[\\n,]+"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                executeMultiple(
                    urls = urls,
                    selector = args["selector"],
                    mode = args["mode"],
                    maxChars = args["max_chars"]?.toIntOrNull()
                )
            }
            else -> ToolExecutionResult("Unknown action: $action", isError = true)
        }
    }

    suspend fun execute(
        url: String,
        selector: String? = null,
        mode: String? = null,
        maxChars: Int? = null
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (!PageFetchEngine.isSyntacticallySafePublicUrl(url)) {
            return@withContext ToolExecutionResult("Invalid or non-public URL.", isError = true)
        }
        try {
            val response = PageFetchEngine.fetch(url)
            val page = ReadablePageExtractor.extract(response, selector)
            val normalizedMode = mode?.trim()?.lowercase().orEmpty().ifBlank { "full" }
            if (normalizedMode !in setOf("full", "content", "metadata", "links")) {
                return@withContext ToolExecutionResult("Invalid mode '$normalizedMode'. Use full, content, metadata, or links.", isError = true)
            }
            val cap = (maxChars ?: MAX_OUTPUT_CHARS).coerceIn(1_000, 50_000)
            ToolExecutionResult(format(response, page, normalizedMode, cap))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            ToolExecutionResult("Failed to scrape '$url': ${error.message}", isError = true)
        }
    }

    suspend fun executeMultiple(
        urls: List<String>,
        selector: String?,
        mode: String? = null,
        maxChars: Int? = null
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val cleanUrls = urls.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(MAX_URLS_MULTIPLE)
        if (cleanUrls.isEmpty()) return@withContext ToolExecutionResult("No URLs provided.", isError = true)

        val perPageCap = (maxChars ?: MAX_CHARS_PER_PAGE).coerceIn(1_000, 20_000)
        val results = coroutineScope {
            cleanUrls.map { url ->
                async(Dispatchers.IO) {
                    val result = withTimeoutOrNull(MULTI_FETCH_TIMEOUT_MS) {
                        execute(url, selector, mode, perPageCap)
                    }
                    url to result
                }
            }.awaitAll()
        }

        val successCount = results.count { it.second?.isError == false }
        val combined = buildString {
            appendLine("# Multi-page scrape")
            appendLine("Fetched $successCount/${results.size} pages successfully.")
            appendLine()
            results.forEachIndexed { index, (url, result) ->
                appendLine("---")
                appendLine("## Source ${index + 1}: $url")
                appendLine()
                when {
                    result == null -> appendLine("*(Timed out while reading this page.)*")
                    result.isError -> appendLine("*(Error: ${result.output})*")
                    else -> appendLine(result.output.take(perPageCap))
                }
                appendLine()
            }
        }.trimEnd()

        val capped = if (combined.length > MAX_COMBINED_OUTPUT_CHARS) {
            combined.take(MAX_COMBINED_OUTPUT_CHARS) + "\n\n[TOTAL OUTPUT CAPPED AT $MAX_COMBINED_OUTPUT_CHARS CHARS]"
        } else combined
        ToolExecutionResult(capped, isError = successCount == 0)
    }

    private fun format(
        response: PageFetchResponse,
        page: ExtractedPage,
        mode: String,
        cap: Int
    ): String {
        val metadata = buildString {
            appendLine("# ${page.metadata.title.ifBlank { "Scraped page" }}")
            appendLine()
            appendLine("**Final URL:** ${response.finalUrl}")
            appendLine("**HTTP:** ${response.statusCode} • ${response.contentType.ifBlank { "unknown" }} • ${response.charset.name()}")
            appendLine("**Fetch:** ${response.elapsedMs} ms${if (response.redirectCount > 0) " • ${response.redirectCount} redirect(s)" else ""}")
            if (page.metadata.siteName.isNotBlank()) appendLine("**Site:** ${page.metadata.siteName}")
            if (page.metadata.author.isNotBlank()) appendLine("**Author:** ${page.metadata.author}")
            if (page.metadata.publishedAt.isNotBlank()) appendLine("**Published:** ${page.metadata.publishedAt}")
            if (page.metadata.modifiedAt.isNotBlank()) appendLine("**Modified:** ${page.metadata.modifiedAt}")
            if (page.metadata.language.isNotBlank()) appendLine("**Language:** ${page.metadata.language}")
            if (page.metadata.canonicalUrl.isNotBlank()) appendLine("**Canonical:** ${page.metadata.canonicalUrl}")
            appendLine("**Readable words:** ${page.wordCount} • quality=${"%.2f".format(page.qualityScore)}")
            if (page.metadata.description.isNotBlank()) {
                appendLine()
                appendLine("**Description:** ${page.metadata.description}")
            }
            if (page.warnings.isNotEmpty()) {
                appendLine()
                appendLine("**Scraper warnings:**")
                page.warnings.forEach { appendLine("- $it") }
            }
        }.trim()

        val links = if (page.links.isEmpty()) "No useful public links extracted." else buildString {
            page.links.forEach { (label, href) -> appendLine("- [$label]($href)") }
        }.trim()

        val content = page.markdown.ifBlank { page.text }
        val raw = when (mode) {
            "metadata" -> metadata
            "links" -> "$metadata\n\n## Extracted links\n$links"
            "content" -> "--- BEGIN UNTRUSTED PAGE CONTENT ---\n$content\n--- END UNTRUSTED PAGE CONTENT ---"
            else -> buildString {
                appendLine(metadata)
                appendLine()
                appendLine("--- BEGIN UNTRUSTED PAGE CONTENT ---")
                appendLine(content)
                appendLine("--- END UNTRUSTED PAGE CONTENT ---")
                if (page.links.isNotEmpty()) {
                    appendLine()
                    appendLine("## Extracted links")
                    appendLine(links)
                }
            }.trim()
        }
        return if (raw.length > cap) raw.take(cap) + "\n\n[CONTENT TRUNCATED — ${raw.length} chars total]" else raw
    }
}
