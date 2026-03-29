package com.omnidev.workspace.data.tools

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * Deep Research web scraper that fetches a URL, parses the HTML via Jsoup,
 * and converts it to clean, token-optimized Markdown for LLM consumption.
 *
 * Strips scripts, styles, ads, nav elements, and other non-content noise.
 * Extracts only headers, paragraphs, lists, links, tables, and code blocks.
 */
object WebScraperTool {

    /** Maximum characters in the converted Markdown output per single fetch. */
    private const val MAX_OUTPUT_CHARS = 30_000

    /** Connection timeout in milliseconds. */
    private const val TIMEOUT_MS = 15_000

    /** Maximum characters per page when fetching multiple URLs. */
    private const val MAX_CHARS_PER_PAGE = 8_000
    
    /** Timeout for fetching multiple pages in parallel. */
    private const val MULTI_FETCH_TIMEOUT_MS = 12_000L
    
    /** Maximum number of URLs that can be scraped in parallel. */
    private const val MAX_URLS_MULTIPLE = 8
    
    /** Maximum characters for the combined output of multiple pages. */
    private const val MAX_COMBINED_OUTPUT_CHARS = 50_000

    // ─────────────────────────────────────────────────────────────────────
    // Tool Definition
    // ─────────────────────────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "web_scraper",
            description = "Fetches a web page and converts it to clean, token-optimized Markdown. " +
                "Strips scripts, styles, ads, and navigation. Returns only content: headers, " +
                "paragraphs, lists, links, and code blocks. Use for reading articles, docs, and research.",
            parameters = listOf(
                ToolParameter("url", "string", "The full URL to fetch (must start with http:// or https://)", required = true),
                ToolParameter("selector", "string", "Optional CSS selector to extract only a specific section (e.g., 'article', 'main', '.content')", required = false)
            )
        ),
        ToolDefinition(
            name = "scrape_multiple",
            description = "Fetch and read multiple web pages in parallel, converting each to clean Markdown. " +
                "Pass a comma-separated list of URLs. Returns full content from all pages simultaneously. " +
                "Ideal for comparing sources, reading a list of links, or researching a topic across multiple sites.",
            parameters = listOf(
                ToolParameter("urls", "string", "Comma-separated list of URLs to fetch (up to 8).", required = true),
                ToolParameter("selector", "string", "Optional CSS selector applied to all pages.", required = false)
            )
        )
    )

    // ─────────────────────────────────────────────────────────────────────
    // Execution Routing
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Executes the appropriate tool based on the action name.
     * This allows the CompositeToolManager to route calls here easily.
     */
    suspend fun executeTool(action: String, args: Map<String, String>): ToolExecutionResult {
        return when (action) {
            "web_scraper" -> {
                val url = args["url"] ?: return ToolExecutionResult("Missing 'url' parameter.", isError = true)
                execute(url, args["selector"])
            }
            "scrape_multiple" -> {
                val urlsStr = args["urls"] ?: return ToolExecutionResult("Missing 'urls' parameter.", isError = true)
                val urls = urlsStr.split(",").map { it.trim() }.filter { it.isNotBlank() }
                executeMultiple(urls, args["selector"])
            }
            else -> ToolExecutionResult("Unknown action: $action", isError = true)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Core Logic
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Fetches a single URL and converts it to Markdown.
     */
    suspend fun execute(url: String, selector: String? = null): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
                return@withContext ToolExecutionResult(
                    "Invalid URL: must start with http:// or https://",
                    isError = true
                )
            }

            try {
                // Fetch the HTML document
                val doc: Document = Jsoup.connect(url)
                    // Spoof user agent to avoid basic bot detection
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .timeout(TIMEOUT_MS)
                    .followRedirects(true)
                    .maxBodySize(2_000_000) // 2 MB max to prevent memory exhaustion
                    .get()

                // Aggressively remove non-content noise from the DOM
                doc.select("script, style, nav, footer, header, aside, iframe, " +
                    "noscript, svg, form, button, input, select, textarea, " +
                    ".ad, .ads, .advertisement, .sidebar, .menu, .nav, " +
                    "#cookie-banner, .cookie-notice, .popup, [aria-hidden=true]").remove()

                // Target the main content area
                val content: Element = if (!selector.isNullOrBlank()) {
                    doc.selectFirst(selector) ?: doc.body()
                } else {
                    // Smart fallback cascade for common content wrappers
                    doc.selectFirst("article")
                        ?: doc.selectFirst("main")
                        ?: doc.selectFirst("[role=main]")
                        ?: doc.selectFirst(".post-content, .entry-content, .article-content, .article-body, .story-content, .post-body, #article-body, .main-content, .blog-content, .page-content")
                        ?: doc.selectFirst(".content, #content, #main")
                        ?: doc.body()
                }

                val title = doc.title()
                
                // Build the Markdown string
                val markdown = buildString {
                    if (title.isNotBlank()) {
                        appendLine("# $title")
                        appendLine()
                    }
                    appendLine("**Source:** $url")
                    appendLine()
                    
                    // Parse the DOM tree recursively
                    convertToMarkdown(content, this)
                }

                // Clean up excessive blank lines generated during parsing
                val cleanMarkdown = markdown.replace(Regex("\\n{3,}"), "\n\n").trim()

                // Truncate if it exceeds the token limit
                val output = if (cleanMarkdown.length > MAX_OUTPUT_CHARS) {
                    cleanMarkdown.take(MAX_OUTPUT_CHARS) + "\n\n[CONTENT TRUNCATED — ${cleanMarkdown.length} chars total]"
                } else {
                    cleanMarkdown
                }

                ToolExecutionResult(output = output)
                
            } catch (e: Exception) {
                ToolExecutionResult(
                    output = "Failed to fetch URL '$url': ${e.message}",
                    isError = true
                )
            }
        }

    /**
     * Fetches multiple URLs in parallel.
     */
    suspend fun executeMultiple(urls: List<String>, selector: String?): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            if (urls.isEmpty()) {
                return@withContext ToolExecutionResult("No URLs provided.", isError = true)
            }

            // Launch parallel requests with timeout
            val results: List<Pair<String, ToolExecutionResult?>> = coroutineScope {
                urls.take(MAX_URLS_MULTIPLE).map { url ->
                    async(Dispatchers.IO) {
                        val result = withTimeoutOrNull(MULTI_FETCH_TIMEOUT_MS) {
                            execute(url.trim(), selector)
                        }
                        Pair(url.trim(), result)
                    }
                }.map { it.await() }
            }

            // Combine the results
            val combined = buildString {
                results.forEachIndexed { index, (url, result) ->
                    appendLine("---")
                    appendLine("## Site ${index + 1}: $url")
                    appendLine()
                    
                    when {
                        result == null -> appendLine("*(Timed out fetching this page.)*")
                        result.isError -> appendLine("*(Error: ${result.output})*")
                        else -> {
                            val content = if (result.output.length > MAX_CHARS_PER_PAGE) {
                                result.output.take(MAX_CHARS_PER_PAGE) + "\n\n[TRUNCATED]"
                            } else {
                                result.output
                            }
                            appendLine(content)
                        }
                    }
                    appendLine()
                }
            }.trimEnd()

            // Cap the total output size to protect the LLM context window
            val capped = if (combined.length > MAX_COMBINED_OUTPUT_CHARS) {
                combined.take(MAX_COMBINED_OUTPUT_CHARS) + "\n\n[TOTAL OUTPUT CAPPED AT $MAX_COMBINED_OUTPUT_CHARS CHARS]"
            } else {
                combined
            }

            ToolExecutionResult(output = capped)
        }

    // ─────────────────────────────────────────────────────────────────────
    // Markdown Parser
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Recursively converts an HTML element tree to Markdown.
     * FIX: Replaced depth tracking with a simpler, flatter node visitor to prevent infinite recursion
     * and excessive blank lines.
     */
    private fun convertToMarkdown(element: Element, sb: StringBuilder) {
        for (child in element.childNodes()) {
            when (child) {
                is TextNode -> {
                    val text = child.text().trim()
                    // Only append text if it has meaning, prevent weird spacing
                    if (text.isNotEmpty()) sb.append(text).append(" ")
                }
                is Element -> {
                    val tag = child.tagName().lowercase()
                    when (tag) {
                        "h1", "h2", "h3", "h4", "h5", "h6" -> {
                            sb.appendLine()
                            val hashes = "#".repeat(tag.substring(1).toInt())
                            sb.appendLine("$hashes ${child.text().trim()}")
                            sb.appendLine()
                        }
                        "p" -> {
                            sb.appendLine()
                            convertToMarkdown(child, sb)
                            sb.appendLine()
                        }
                        "br" -> sb.appendLine()
                        "a" -> {
                            val href = child.attr("abs:href")
                            val linkText = child.text().trim()
                            if (linkText.isNotBlank() && href.isNotBlank()) {
                                sb.append("[$linkText]($href) ")
                            } else if (linkText.isNotBlank()) {
                                sb.append("$linkText ")
                            }
                        }
                        "strong", "b" -> sb.append("**${child.text().trim()}** ")
                        "em", "i" -> sb.append("*${child.text().trim()}* ")
                        "code" -> sb.append("`${child.text().trim()}` ")
                        "pre" -> {
                            val codeEl = child.selectFirst("code")
                            val lang = codeEl?.className()?.replace("language-", "") ?: ""
                            val code = (codeEl ?: child).text()
                            sb.appendLine()
                            sb.appendLine("```$lang")
                            sb.appendLine(code)
                            sb.appendLine("```")
                            sb.appendLine()
                        }
                        "ul", "ol" -> {
                            sb.appendLine()
                            var index = 1
                            for (li in child.children()) {
                                if (li.tagName() == "li") {
                                    val prefix = if (tag == "ol") "${index++}." else "-"
                                    sb.append("$prefix ${li.text().trim()}")
                                    sb.appendLine()
                                }
                            }
                            sb.appendLine()
                        }
                        "blockquote" -> {
                            sb.appendLine()
                            child.text().lines().forEach { line ->
                                if (line.isNotBlank()) sb.appendLine("> ${line.trim()}")
                            }
                            sb.appendLine()
                        }
                        "table" -> {
                            sb.appendLine()
                            convertTable(child, sb)
                            sb.appendLine()
                        }
                        "img" -> {
                            val alt = child.attr("alt")
                            val src = child.attr("abs:src")
                            if (src.isNotBlank()) {
                                val altText = alt.ifBlank { "Image" }
                                sb.append("![$altText]($src) ")
                            }
                        }
                        "div", "section", "article", "main", "span", "header", "footer" -> {
                            // Flattens containers, just parse their children
                            convertToMarkdown(child, sb)
                        }
                        "li" -> {
                            // Handled inside ul/ol, but just in case it's floating:
                            sb.append("- ${child.text().trim()}")
                            sb.appendLine()
                        }
                        else -> {
                            // Fallback for unknown elements: just process children
                            convertToMarkdown(child, sb)
                        }
                    }
                }
            }
        }
    }

    /**
     * Converts an HTML table to a Markdown table format.
     */
    private fun convertTable(table: Element, sb: StringBuilder) {
        val rows = table.select("tr")
        if (rows.isEmpty()) return

        for ((rowIndex, row) in rows.withIndex()) {
            val cells = row.select("th, td")
            if (cells.isEmpty()) continue
            
            // Sanitize cell text to prevent markdown table breaking
            val cellTexts = cells.map { it.text().replace("|", "\\|").replace("\n", " ").trim() }
            
            sb.appendLine("| ${cellTexts.joinToString(" | ")} |")
            
            // Add separator after header row
            if (rowIndex == 0) {
                sb.appendLine("| ${cellTexts.joinToString(" | ") { "---" }} |")
            }
        }
    }
}
