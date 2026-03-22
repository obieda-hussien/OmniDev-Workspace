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
import org.jsoup.safety.Safelist

/**
 * Deep Research web scraper that fetches a URL, parses the HTML via Jsoup,
 * and converts it to clean, token-optimized Markdown for LLM consumption.
 *
 * Strips scripts, styles, ads, nav elements, and other non-content noise.
 * Extracts only headers, paragraphs, lists, links, and code blocks.
 */
object WebScraperTool {

    /** Maximum characters in the converted Markdown output. */
    private const val MAX_OUTPUT_CHARS = 30_000

    /** Connection timeout in milliseconds. */
    private const val TIMEOUT_MS = 15_000

    private const val MAX_CHARS_PER_PAGE = 8_000
    private const val MULTI_FETCH_TIMEOUT_MS = 12_000L
    private const val MAX_URLS_MULTIPLE = 8
    private const val MAX_COMBINED_OUTPUT_CHARS = 50_000

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

    suspend fun execute(url: String, selector: String? = null): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
                return@withContext ToolExecutionResult(
                    "Invalid URL: must start with http:// or https://",
                    isError = true
                )
            }

            try {
                val doc: Document = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Linux; Android 14) OmniDev/1.0")
                    .timeout(TIMEOUT_MS)
                    .followRedirects(true)
                    .maxBodySize(2_000_000) // 2 MB max
                    .get()

                // Remove non-content elements
                doc.select("script, style, nav, footer, header, aside, iframe, " +
                    "noscript, svg, form, button, input, select, textarea, " +
                    ".ad, .ads, .advertisement, .sidebar, .menu, .nav, " +
                    "#cookie-banner, .cookie-notice, .popup").remove()

                // If a CSS selector is specified, narrow down to that section
                val content: Element = if (!selector.isNullOrBlank()) {
                    doc.selectFirst(selector) ?: doc.body()
                } else {
                    // Try common content selectors first
                    doc.selectFirst("article")
                        ?: doc.selectFirst("main")
                        ?: doc.selectFirst("[role=main]")
                        ?: doc.selectFirst(".post-content, .entry-content, .article-content, .article-body, .story-content, .post-body, #article-body, .main-content, .blog-content, .page-content")
                        ?: doc.selectFirst(".content, #content, #main")
                        ?: doc.body()
                }

                val title = doc.title()
                val markdown = buildString {
                    if (title.isNotBlank()) {
                        appendLine("# $title")
                        appendLine()
                    }
                    appendLine("**Source:** $url")
                    appendLine()
                    convertToMarkdown(content, this, depth = 0)
                }

                val output = if (markdown.length > MAX_OUTPUT_CHARS) {
                    markdown.take(MAX_OUTPUT_CHARS) + "\n\n[CONTENT TRUNCATED — ${markdown.length} chars total]"
                } else {
                    markdown
                }

                ToolExecutionResult(output = output)
            } catch (e: Exception) {
                ToolExecutionResult(
                    output = "Failed to fetch URL '$url': ${e.message}",
                    isError = true
                )
            }
        }

    suspend fun executeMultiple(urls: List<String>, selector: String?): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            if (urls.isEmpty()) {
                return@withContext ToolExecutionResult("No URLs provided.", isError = true)
            }

            val results: List<Pair<String, ToolExecutionResult?>> = coroutineScope {
                urls.take(MAX_URLS_MULTIPLE).mapIndexed { i, url ->
                    async(Dispatchers.IO) {
                        val result = withTimeoutOrNull(MULTI_FETCH_TIMEOUT_MS) {
                            execute(url.trim(), selector)
                        }
                        Pair(url.trim(), result)
                    }
                }.map { it.await() }
            }

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

            val capped = if (combined.length > MAX_COMBINED_OUTPUT_CHARS) {
                combined.take(MAX_COMBINED_OUTPUT_CHARS) + "\n\n[TOTAL OUTPUT CAPPED AT $MAX_COMBINED_OUTPUT_CHARS CHARS]"
            } else {
                combined
            }

            ToolExecutionResult(output = capped)
        }

    /**
     * Recursively converts an HTML element tree to Markdown.
     */
    private fun convertToMarkdown(element: Element, sb: StringBuilder, depth: Int) {
        if (depth > 20) return // prevent infinite recursion

        for (child in element.childNodes()) {
            when (child) {
                is TextNode -> {
                    val text = child.text().trim()
                    if (text.isNotEmpty()) sb.append(text).append(" ")
                }
                is Element -> {
                    val tag = child.tagName().lowercase()
                    when (tag) {
                        "h1" -> {
                            sb.appendLine()
                            sb.appendLine("# ${child.text()}")
                            sb.appendLine()
                        }
                        "h2" -> {
                            sb.appendLine()
                            sb.appendLine("## ${child.text()}")
                            sb.appendLine()
                        }
                        "h3" -> {
                            sb.appendLine()
                            sb.appendLine("### ${child.text()}")
                            sb.appendLine()
                        }
                        "h4", "h5", "h6" -> {
                            sb.appendLine()
                            sb.appendLine("#### ${child.text()}")
                            sb.appendLine()
                        }
                        "p" -> {
                            sb.appendLine()
                            convertToMarkdown(child, sb, depth + 1)
                            sb.appendLine()
                        }
                        "br" -> sb.appendLine()
                        "a" -> {
                            val href = child.attr("abs:href")
                            val linkText = child.text().trim()
                            if (linkText.isNotBlank() && href.isNotBlank()) {
                                sb.append("[${linkText}]($href) ")
                            } else if (linkText.isNotBlank()) {
                                sb.append(linkText).append(" ")
                            }
                        }
                        "strong", "b" -> sb.append("**${child.text()}** ")
                        "em", "i" -> sb.append("*${child.text()}* ")
                        "code" -> sb.append("`${child.text()}` ")
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
                        "ul" -> {
                            sb.appendLine()
                            for (li in child.children()) {
                                if (li.tagName() == "li") {
                                    sb.append("- ${li.text()}")
                                    sb.appendLine()
                                }
                            }
                            sb.appendLine()
                        }
                        "ol" -> {
                            sb.appendLine()
                            child.children().forEachIndexed { i, li ->
                                if (li.tagName() == "li") {
                                    sb.append("${i + 1}. ${li.text()}")
                                    sb.appendLine()
                                }
                            }
                            sb.appendLine()
                        }
                        "blockquote" -> {
                            sb.appendLine()
                            child.text().lines().forEach { line ->
                                sb.appendLine("> $line")
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
                            if (alt.isNotBlank()) sb.append("![${alt}]($src) ")
                        }
                        "div", "section", "article", "main", "span" -> {
                            convertToMarkdown(child, sb, depth + 1)
                        }
                        "li" -> {
                            sb.append("- ${child.text()}")
                            sb.appendLine()
                        }
                        else -> {
                            // For unknown elements, just recurse
                            convertToMarkdown(child, sb, depth + 1)
                        }
                    }
                }
            }
        }
    }

    /**
     * Converts an HTML table to Markdown table format.
     */
    private fun convertTable(table: Element, sb: StringBuilder) {
        val rows = table.select("tr")
        if (rows.isEmpty()) return

        for ((rowIndex, row) in rows.withIndex()) {
            val cells = row.select("th, td")
            val cellTexts = cells.map { it.text().trim() }
            sb.appendLine("| ${cellTexts.joinToString(" | ")} |")
            if (rowIndex == 0) {
                sb.appendLine("| ${cellTexts.joinToString(" | ") { "---" }} |")
            }
        }
    }
}
