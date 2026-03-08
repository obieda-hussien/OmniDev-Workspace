package com.omnidev.workspace.data.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private const val MAX_OUTPUT_CHARS = 12_000

    /** Connection timeout in milliseconds. */
    private const val TIMEOUT_MS = 15_000

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
                    doc.selectFirst("article") ?: doc.selectFirst("main") ?: 
                    doc.selectFirst("[role=main]") ?: doc.selectFirst(".content") ?: 
                    doc.selectFirst("#content") ?: doc.body()
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
