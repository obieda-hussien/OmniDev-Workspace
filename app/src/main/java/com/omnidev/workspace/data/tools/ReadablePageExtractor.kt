package com.omnidev.workspace.data.tools

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import kotlin.math.max

internal data class PageMetadata(
    val title: String = "",
    val description: String = "",
    val canonicalUrl: String = "",
    val siteName: String = "",
    val author: String = "",
    val publishedAt: String = "",
    val modifiedAt: String = "",
    val language: String = ""
)

internal data class ExtractedPage(
    val metadata: PageMetadata,
    val text: String,
    val markdown: String,
    val links: List<Pair<String, String>>,
    val wordCount: Int,
    val qualityScore: Double,
    val warnings: List<String>
)

/** Readability-style DOM extraction shared by fetch_page, web_scraper and deep research. */
internal object ReadablePageExtractor {
    private const val MAX_LINKS = 40
    private const val MIN_CANDIDATE_TEXT = 180

    private const val NOISE_SELECTOR =
        "script,style,noscript,template,nav,footer,aside,iframe,svg,canvas,form,button,input,select,textarea," +
            "[aria-hidden=true],[hidden],[role=navigation],[role=banner],[role=contentinfo]," +
            ".ad,.ads,.advertisement,.advert,.sidebar,.menu,.nav,.navbar,.breadcrumb,.breadcrumbs," +
            ".cookie,.cookie-banner,.cookie-notice,.consent,.popup,.modal,.newsletter,.social-share,.share-buttons," +
            ".related,.recommendations,.recommended,.comments,.comment-list,.login,.signup,.subscribe"

    fun extract(response: PageFetchResponse, selector: String? = null): ExtractedPage {
        val type = response.contentType.lowercase()
        if (!type.contains("html") && !type.contains("xhtml")) {
            val clean = response.body.replace("\u0000", "").trim()
            val words = wordCount(clean)
            return ExtractedPage(
                metadata = PageMetadata(canonicalUrl = response.finalUrl),
                text = clean,
                markdown = clean,
                links = emptyList(),
                wordCount = words,
                qualityScore = if (words >= 80) 0.75 else 0.45,
                warnings = emptyList()
            )
        }

        val doc = Jsoup.parse(response.body, response.finalUrl)
        val metadata = extractMetadata(doc, response.finalUrl)
        val scriptCount = doc.select("script").size
        doc.select(NOISE_SELECTOR).remove()

        val content = selectContent(doc, selector)
        val text = normalizeReadableText(content)
        val markdown = MarkdownPageRenderer.render(content)
        val links = extractLinks(content)
        val words = wordCount(text)
        val warnings = mutableListOf<String>()
        if (!selector.isNullOrBlank() && runCatching { doc.selectFirst(selector) }.getOrNull() == null) {
            warnings += "Requested selector was not found; readability fallback was used."
        }
        if (words < 80) warnings += "Very little readable text was found; this may be a JavaScript-rendered, blocked, or paywalled page."
        if (scriptCount >= 15 && words < 180) warnings += "The page looks JavaScript-heavy; browser rendering may expose more content."
        if (looksBlockedOrPaywalled(text)) warnings += "The extracted text contains access/cookie/paywall language; content may be incomplete."

        val quality = qualityScore(content, text, markdown, warnings)
        return ExtractedPage(metadata, text, markdown, links, words, quality, warnings.distinct())
    }

    private fun extractMetadata(doc: Document, finalUrl: String): PageMetadata {
        fun firstMeta(vararg selectors: String): String = selectors.asSequence()
            .mapNotNull { doc.selectFirst(it)?.attr("content")?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull().orEmpty()

        val jsonLd = doc.select("script[type=application/ld+json]").joinToString("\n") { it.data() }
        fun jsonString(key: String): String = Regex(
            "[\\\"']${Regex.escape(key)}[\\\"']\\s*:\\s*[\\\"']([^\\\"']{1,500})[\\\"']",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(jsonLd)?.groupValues?.getOrNull(1)?.replace("\\/", "/")?.trim().orEmpty()

        val title = firstMeta("meta[property=og:title]", "meta[name=twitter:title]")
            .ifBlank { jsonString("headline") }
            .ifBlank { doc.title().trim() }
            .ifBlank { doc.selectFirst("h1")?.text()?.trim().orEmpty() }
        val description = firstMeta(
            "meta[property=og:description]", "meta[name=description]", "meta[name=twitter:description]"
        ).ifBlank { jsonString("description") }
        val canonical = doc.selectFirst("link[rel=canonical][href]")?.absUrl("href")?.trim().orEmpty()
            .ifBlank { firstMeta("meta[property=og:url]") }
            .ifBlank { finalUrl }
        val author = firstMeta("meta[name=author]", "meta[property=article:author]")
            .ifBlank { jsonString("author") }
        val published = firstMeta(
            "meta[property=article:published_time]", "meta[name=date]", "meta[name=pubdate]",
            "meta[itemprop=datePublished]", "meta[name=dc.date]", "meta[name=dc.date.issued]"
        ).ifBlank { jsonString("datePublished") }
        val modified = firstMeta(
            "meta[property=article:modified_time]", "meta[itemprop=dateModified]", "meta[name=last-modified]"
        ).ifBlank { jsonString("dateModified") }
        val siteName = firstMeta("meta[property=og:site_name]").ifBlank { runCatching { java.net.URI(finalUrl).host }.getOrNull().orEmpty() }
        val language = doc.selectFirst("html[lang]")?.attr("lang")?.trim().orEmpty()

        return PageMetadata(title, description, canonical, siteName, author, published, modified, language)
    }

    private fun selectContent(doc: Document, selector: String?): Element {
        if (!selector.isNullOrBlank()) {
            runCatching { doc.selectFirst(selector) }.getOrNull()?.let { return it }
        }

        val obvious = listOf(
            "article", "main", "[role=main]", ".post-content", ".entry-content", ".article-content",
            ".article-body", ".story-content", ".post-body", "#article-body", ".main-content",
            ".blog-content", ".page-content", "#content", "#main"
        ).mapNotNull { doc.selectFirst(it) }
            .filter { it.text().length >= MIN_CANDIDATE_TEXT }

        val scored = (obvious + doc.select("article,main,section,div").filter { it.text().length >= MIN_CANDIDATE_TEXT })
            .distinct()
            .map { it to contentScore(it) }
            .sortedByDescending { it.second }

        return scored.firstOrNull()?.first ?: doc.body()
    }

    private fun contentScore(element: Element): Double {
        val textLength = element.text().length.toDouble()
        if (textLength <= 0.0) return 0.0
        val paragraphs = element.select("p").size
        val headings = element.select("h1,h2,h3,h4").size
        val codeBlocks = element.select("pre,code").size
        val tables = element.select("table").size
        val linkText = element.select("a").sumOf { it.text().length }.toDouble()
        val linkDensity = (linkText / textLength).coerceIn(0.0, 1.0)
        val classHints = (element.className() + " " + element.id()).lowercase()
        val positive = if (Regex("article|content|post|story|body|main|documentation|docs").containsMatchIn(classHints)) 450.0 else 0.0
        val negative = if (Regex("nav|menu|sidebar|footer|header|comment|related|recommend|share|promo").containsMatchIn(classHints)) 700.0 else 0.0
        val tagBonus = when (element.tagName().lowercase()) {
            "article" -> 900.0
            "main" -> 550.0
            "section" -> 120.0
            else -> 0.0
        }
        val boundedText = textLength.coerceAtMost(6_000.0)
        return boundedText + paragraphs * 120.0 + headings * 45.0 + codeBlocks * 55.0 + tables * 80.0 +
            positive + tagBonus - negative - linkDensity * textLength * 0.85
    }

    private fun normalizeReadableText(content: Element): String {
        val clone = content.clone()
        clone.select("br").append("\n")
        clone.select("p,li,h1,h2,h3,h4,h5,h6,pre,blockquote,tr").append("\n")
        return clone.wholeText()
            .replace('\u00A0', ' ')
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun extractLinks(content: Element): List<Pair<String, String>> = content.select("a[href]")
        .mapNotNull { anchor ->
            val text = anchor.text().replace(Regex("\\s+"), " ").trim()
            val href = anchor.absUrl("href").trim()
            if (text.length < 2 || !PageFetchEngine.isSyntacticallySafePublicUrl(href)) null else text to href
        }
        .distinctBy { it.second }
        .take(MAX_LINKS)

    private fun qualityScore(content: Element, text: String, markdown: String, warnings: List<String>): Double {
        val words = wordCount(text)
        val paragraphCount = content.select("p").size
        val structural = (paragraphCount / 8.0).coerceAtMost(1.0)
        val length = (words / 500.0).coerceAtMost(1.0)
        val markdownSignal = if (markdown.contains("# ") || markdown.contains("## ")) 0.12 else 0.0
        return (0.25 + structural * 0.3 + length * 0.35 + markdownSignal - warnings.size * 0.08).coerceIn(0.0, 1.0)
    }

    private fun looksBlockedOrPaywalled(text: String): Boolean {
        val sample = text.take(2500).lowercase()
        return listOf(
            "enable javascript", "please verify you are human", "access denied", "sign in to continue",
            "subscribe to continue", "subscription required", "disable your ad blocker", "accept cookies to continue"
        ).any(sample::contains)
    }

    private fun wordCount(text: String): Int = text.split(Regex("\\s+")).count { it.isNotBlank() }
}

internal object MarkdownPageRenderer {
    fun render(root: Element): String {
        val out = StringBuilder()
        renderChildren(root, out, 0)
        return out.toString().replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun renderChildren(element: Element, out: StringBuilder, listDepth: Int) {
        for (node in element.childNodes()) {
            when (node) {
                is TextNode -> appendText(out, node.text())
                is Element -> renderElement(node, out, listDepth)
            }
        }
    }

    private fun renderElement(el: Element, out: StringBuilder, listDepth: Int) {
        when (val tag = el.tagName().lowercase()) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                blankLine(out)
                out.append("#".repeat(tag.drop(1).toInt())).append(' ').append(el.text().trim()).append('\n')
                blankLine(out)
            }
            "p" -> { blankLine(out); renderChildren(el, out, listDepth); blankLine(out) }
            "br" -> out.append('\n')
            "a" -> {
                val text = el.text().trim()
                val href = el.absUrl("href")
                if (text.isNotBlank() && PageFetchEngine.isSyntacticallySafePublicUrl(href)) out.append('[').append(text).append("](").append(href).append(") ")
                else appendText(out, text)
            }
            "strong", "b" -> out.append("**").append(el.text().trim()).append("** ")
            "em", "i" -> out.append('*').append(el.text().trim()).append("* ")
            "code" -> if (el.parent()?.tagName()?.equals("pre", ignoreCase = true) != true) {
                out.append('`').append(el.text().replace("`", "\\`").trim()).append("` ")
            }
            "pre" -> {
                blankLine(out)
                val code = el.selectFirst("code") ?: el
                val lang = code.classNames().firstOrNull { it.startsWith("language-") }?.removePrefix("language-").orEmpty()
                out.append("```").append(lang).append('\n').append(code.wholeText().trimEnd()).append("\n```\n")
                blankLine(out)
            }
            "ul", "ol" -> {
                blankLine(out)
                el.children().filter { it.tagName().equals("li", true) }.forEachIndexed { index, li ->
                    out.append("  ".repeat(listDepth))
                    out.append(if (tag == "ol") "${index + 1}. " else "- ")
                    renderListItem(li, out, listDepth + 1)
                    if (!out.endsWith("\n")) out.append('\n')
                }
                blankLine(out)
            }
            "blockquote" -> {
                blankLine(out)
                el.wholeText().lines().filter { it.isNotBlank() }.forEach { out.append("> ").append(it.trim()).append('\n') }
                blankLine(out)
            }
            "table" -> { blankLine(out); renderTable(el, out); blankLine(out) }
            "img" -> {
                val src = el.absUrl("src")
                val alt = el.attr("alt").trim().ifBlank { "Image" }
                if (PageFetchEngine.isSyntacticallySafePublicUrl(src)) out.append("![$alt]($src) ")
            }
            "hr" -> { blankLine(out); out.append("---\n"); blankLine(out) }
            else -> renderChildren(el, out, listDepth)
        }
    }

    private fun renderListItem(li: Element, out: StringBuilder, depth: Int) {
        for (node in li.childNodes()) {
            when (node) {
                is TextNode -> appendText(out, node.text())
                is Element -> if (node.tagName().equals("ul", true) || node.tagName().equals("ol", true)) {
                    out.append('\n'); renderElement(node, out, depth)
                } else renderElement(node, out, depth)
            }
        }
    }

    private fun renderTable(table: Element, out: StringBuilder) {
        val rows = table.select("tr").take(40)
        if (rows.isEmpty()) return
        var wroteHeader = false
        rows.forEach { row ->
            val cells = row.select("th,td").take(12)
            if (cells.isEmpty()) return@forEach
            val values = cells.map { it.text().replace("|", "\\|").replace(Regex("\\s+"), " ").trim() }
            out.append("| ").append(values.joinToString(" | ")).append(" |\n")
            if (!wroteHeader) {
                out.append("| ").append(values.joinToString(" | ") { "---" }).append(" |\n")
                wroteHeader = true
            }
        }
    }

    private fun appendText(out: StringBuilder, raw: String) {
        val text = raw.replace(Regex("\\s+"), " ").trim()
        if (text.isBlank()) return
        if (out.isNotEmpty() && !out.endsWith(" ") && !out.endsWith("\n")) out.append(' ')
        out.append(text)
    }

    private fun blankLine(out: StringBuilder) {
        if (out.isEmpty()) return
        if (!out.endsWith("\n")) out.append('\n')
        if (!out.endsWith("\n\n")) out.append('\n')
    }
}
