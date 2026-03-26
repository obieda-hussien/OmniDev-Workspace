package com.omnidev.workspace.data.tools

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * Robust web search tool with provider fallback:
 * 1) SerpApi (if API key configured)
 * 2) Google Custom Search API (if API key + CX configured)
 * 3) Header-spoofed Google HTML scraping
 * 4) Header-spoofed DuckDuckGo HTML scraping
 */
object WebSearchTool {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_RESULTS = 5
    private const val HTTP_RETRY_COUNT = 2
    private const val MAX_RESULTS_DEEP = 8
    private const val MAX_CHARS_PER_SITE = 6_000
    private const val FETCH_PAGE_TIMEOUT_MS = 10_000L

    private data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String
    )

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "web_search",
            description = "Search the web with robust provider fallback and return clean markdown results. " +
                "Prefers API-backed providers when configured (SerpApi or Google CSE), then falls back to " +
                "desktop-header HTML scraping.",
            parameters = listOf(
                ToolParameter(
                    name = "query",
                    type = "string",
                    description = "Search query text.",
                    required = true
                )
            )
        ),
        ToolDefinition(
            name = "web_search_deep",
            description = "Search the web and read the full content of top results in parallel — like ChatGPT or Gemini browse mode. " +
                "Returns actual article text from multiple sources simultaneously, not just titles and snippets. " +
                "Best for research, summarizing topics, fact-checking, and comprehensive answers from multiple sources.",
            parameters = listOf(
                ToolParameter(name = "query", type = "string", description = "Search query text.", required = true),
                ToolParameter(name = "max_sites", type = "integer", description = "Number of sites to read in parallel (1–8, default 5).", required = false)
            )
        )
    )

    suspend fun execute(
        query: String,
        serpApiKey: String?,
        googleApiKey: String?,
        googleCseCx: String?
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            return@withContext ToolExecutionResult("Missing required argument: query", isError = true)
        }

        val normalizedQuery = query.trim()
        val deduped = getSearchResults(normalizedQuery, serpApiKey, googleApiKey, googleCseCx, MAX_RESULTS)

        if (deduped.isEmpty()) {
            return@withContext ToolExecutionResult(
                output = "No results found for: \"$normalizedQuery\"",
                isError = false
            )
        }

        ToolExecutionResult(formatAsMarkdown(normalizedQuery, deduped))
    }

    private suspend fun getSearchResults(
        query: String,
        serpApiKey: String?,
        googleApiKey: String?,
        googleCseCx: String?,
        maxResults: Int
    ): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val errors = mutableListOf<String>()
        val acceptLanguage = preferredAcceptLanguage(query)

        if (serpApiKey?.isNotBlank() == true) {
            results += tryProvider("SerpApi", errors) {
                searchViaSerpApi(query, serpApiKey, acceptLanguage)
            }
        }

        if (results.isEmpty() && googleApiKey?.isNotBlank() == true && googleCseCx?.isNotBlank() == true) {
            results += tryProvider("Google CSE", errors) {
                searchViaGoogleCse(query, googleApiKey, googleCseCx, acceptLanguage)
            }
        }

        if (results.isEmpty()) {
            results += tryProvider("Google scrape", errors) { scrapeGoogle(query, acceptLanguage) }
        }

        if (results.isEmpty()) {
            results += tryProvider("DuckDuckGo scrape", errors) { scrapeDuckDuckGo(query, acceptLanguage) }
        }

        if (results.isEmpty()) {
            results += tryProvider("DuckDuckGo lite", errors) {
                scrapeDuckDuckGoLite(query, acceptLanguage)
            }
        }

        if (results.isEmpty()) {
            results += tryProvider("Bing scrape", errors) { scrapeBing(query, acceptLanguage) }
        }

        return results
            .filter { it.title.isNotBlank() && it.url.startsWith("http") }
            .distinctBy { it.url }
            .take(maxResults)
    }

    suspend fun executeDeep(
        query: String,
        maxSites: Int,
        serpApiKey: String?,
        googleApiKey: String?,
        googleCseCx: String?
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            return@withContext ToolExecutionResult("Missing required argument: query", isError = true)
        }

        val normalizedQuery = query.trim()
        val clampedMax = maxSites.coerceIn(1, MAX_RESULTS_DEEP)
        val targets = getSearchResults(normalizedQuery, serpApiKey, googleApiKey, googleCseCx, clampedMax)

        if (targets.isEmpty()) {
            return@withContext ToolExecutionResult(
                output = "No results found for: \"$normalizedQuery\"",
                isError = false
            )
        }

        data class PageResult(val result: SearchResult, val content: String)

        val pages: List<PageResult> = coroutineScope {
            targets.map { result ->
                async(Dispatchers.IO) {
                    val content = withTimeoutOrNull(FETCH_PAGE_TIMEOUT_MS) {
                        try { fetchPageContent(result.url) } catch (e: Exception) {
                            android.util.Log.w("WebSearchTool", "Failed to fetch ${result.url}: ${e.message}")
                            null
                        }
                    }
                    PageResult(result, content?.takeIf { it.isNotBlank() } ?: result.snippet)
                }
            }.map { it.await() }
        }

        val output = buildString {
            appendLine("# Deep Search: \"$normalizedQuery\"")
            appendLine("Read ${pages.size} sources in parallel.")
            appendLine("---")
            pages.forEachIndexed { index, page ->
                appendLine()
                appendLine("## ${index + 1}. ${page.result.title}")
                appendLine("**URL:** ${page.result.url}")
                appendLine()
                appendLine(page.content)
                appendLine()
                appendLine("---")
            }
        }.trimEnd()

        ToolExecutionResult(output = output)
    }

    private fun fetchPageContent(url: String): String {
        val doc = Jsoup.connect(url)
            .userAgent(desktopUserAgent())
            .timeout(FETCH_PAGE_TIMEOUT_MS.toInt())
            .followRedirects(true)
            .maxBodySize(1_500_000)
            .get()

        doc.select(
            "script, style, nav, footer, header, aside, iframe, noscript, svg, form, button, input, select, textarea, " +
                ".ad, .ads, .advertisement, .sidebar, .menu, #cookie-banner, .cookie-notice, .popup, .newsletter, " +
                ".social-share, [aria-hidden=true]"
        ).remove()

        val content = doc.selectFirst("article")
            ?: doc.selectFirst("main")
            ?: doc.selectFirst("[role=main]")
            ?: doc.selectFirst(".post-content, .entry-content, .article-content, .article-body, .story-content, .post-body, #article-body, .main-content")
            ?: doc.selectFirst(".content, #content, #main")
            ?: doc.body()

        val text = content?.wholeText()
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.joinToString("\n")
            ?.trim() ?: ""

        return if (text.length > MAX_CHARS_PER_SITE) text.take(MAX_CHARS_PER_SITE) else text
    }

    private suspend fun searchViaSerpApi(
        query: String,
        apiKey: String,
        acceptLanguage: String
    ): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://serpapi.com/search.json?q=$encoded&engine=google&api_key=$apiKey&num=$MAX_RESULTS"
        val json = httpGet(url, acceptLanguage)
        val root = JSONObject(json)
        val array = root.optJSONArray("organic_results") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val title = item.optString("title").trim()
                val link = item.optString("link").trim()
                val snippet = item.optString("snippet").trim()
                if (title.isNotBlank() && link.isNotBlank()) {
                    add(SearchResult(title = title, url = link, snippet = snippet))
                }
            }
        }
    }

    private suspend fun searchViaGoogleCse(
        query: String,
        apiKey: String,
        cx: String,
        acceptLanguage: String
    ): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.googleapis.com/customsearch/v1?key=$apiKey&cx=$cx&q=$encoded&num=$MAX_RESULTS"
        val json = httpGet(url, acceptLanguage)
        val root = JSONObject(json)
        val array = root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val title = item.optString("title").trim()
                val link = item.optString("link").trim()
                val snippet = item.optString("snippet").trim()
                if (title.isNotBlank() && link.isNotBlank()) {
                    add(SearchResult(title = title, url = link, snippet = snippet))
                }
            }
        }
    }

    private fun scrapeGoogle(query: String, acceptLanguage: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = Jsoup.connect("https://www.google.com/search?q=$encoded&hl=en")
            .userAgent(desktopUserAgent())
            .header("Accept-Language", acceptLanguage)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .timeout(READ_TIMEOUT_MS)
            .get()

        return doc.select("div#search div.g").mapNotNull { node ->
            val title = node.selectFirst("h3")?.text()?.trim().orEmpty()
            val rawHref = node.selectFirst("a[href]")?.attr("href").orEmpty()
            val snippet = node.selectFirst("div.VwiC3b, span.aCOpRe, div.IsZvec")?.text()?.trim().orEmpty()
            val resolved = resolveGoogleHref(rawHref)
            if (title.isBlank() || resolved.isBlank()) null
            else SearchResult(title = title, url = resolved, snippet = snippet)
        }
    }

    private fun scrapeDuckDuckGo(query: String, acceptLanguage: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = Jsoup.connect("https://duckduckgo.com/html/?q=$encoded")
            .userAgent(desktopUserAgent())
            .header("Accept-Language", acceptLanguage)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .timeout(READ_TIMEOUT_MS)
            .get()

        return doc.select("div.result").mapNotNull { result ->
            val anchor = result.selectFirst("a.result__a")
            val title = anchor?.text()?.trim().orEmpty()
            val href = anchor?.attr("href").orEmpty()
            val snippet = result.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
            val resolved = resolveDuckHref(href)
            if (title.isBlank() || resolved.isBlank()) null
            else SearchResult(title = title, url = resolved, snippet = snippet)
        }
    }

    private suspend fun scrapeDuckDuckGoLite(query: String, acceptLanguage: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = httpGet("https://lite.duckduckgo.com/lite/?q=$encoded&kl=wt-wt", acceptLanguage)
        val doc = Jsoup.parse(html)
        return doc.select("a.result-link").mapNotNull { anchor ->
            val title = anchor.text().trim()
            val href = anchor.attr("href")
            val resolved = resolveDuckHref(href)
            if (title.isBlank() || resolved.isBlank()) null
            else SearchResult(
                title = title,
                url = resolved,
                snippet = anchor.parent()?.nextElementSibling()?.text()?.trim().orEmpty()
            )
        }
    }

    private fun scrapeBing(query: String, acceptLanguage: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = Jsoup.connect("https://www.bing.com/search?q=$encoded")
            .userAgent(desktopUserAgent())
            .header("Accept-Language", acceptLanguage)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .timeout(READ_TIMEOUT_MS)
            .get()

        return doc.select("li.b_algo").mapNotNull { node ->
            val anchor = node.selectFirst("h2 a") ?: return@mapNotNull null
            val title = anchor.text().trim()
            val href = anchor.attr("href").trim()
            val snippet = node.selectFirst("div.b_caption p, p.b_lineclamp2, div.b_caption")?.text()?.trim().orEmpty()
            if (title.isBlank() || href.isBlank()) null
            else SearchResult(title = title, url = href, snippet = snippet)
        }
    }

    private fun resolveGoogleHref(href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        if (!href.startsWith("/url?")) return ""
        val encoded = Regex("""[?&]q=([^&]+)""").find(href)?.groupValues?.getOrNull(1) ?: return ""
        return URLDecoder.decode(encoded, "UTF-8")
    }

    private fun resolveDuckHref(href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        val encoded = Regex("""[?&]uddg=([^&]+)""").find(href)?.groupValues?.getOrNull(1) ?: return ""
        return URLDecoder.decode(encoded, "UTF-8")
    }

    private fun formatAsMarkdown(query: String, results: List<SearchResult>): String = buildString {
        appendLine("Web search results for: \"$query\"")
        appendLine()
        results.forEachIndexed { index, item ->
            val safeTitle = escapeHtml(item.title.replace("\n", " ").trim())
            val safeSnippet = escapeHtml(
                item.snippet.replace("\n", " ").trim().ifBlank { "No snippet available." }
            )
            appendLine("${index + 1}. <a href=\"${item.url}\">$safeTitle</a> - $safeSnippet")
        }
    }.trimEnd()

    private suspend fun httpGet(url: String, acceptLanguage: String): String {
        var lastError: Throwable? = null
        for (attempt in 0 until HTTP_RETRY_COUNT) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("User-Agent", desktopUserAgent())
                    setRequestProperty("Accept-Language", acceptLanguage)
                    setRequestProperty("Accept", "application/json,text/html,*/*")
                }
                return conn.useAndReadBody()
            } catch (e: Throwable) {
                lastError = e
                val isRetryable = e is ConnectException ||
                    e is SocketTimeoutException ||
                    e is InterruptedIOException
                if (attempt < HTTP_RETRY_COUNT - 1 && isRetryable) {
                    delay(250L * (1L shl attempt))
                    continue
                }
                break
            }
        }
        throw IllegalStateException(
            "HTTP request failed for $url: ${lastError?.message ?: "unknown error"}",
            lastError
        )
    }

    private fun HttpURLConnection.useAndReadBody(): String {
        return try {
            val code = responseCode
            val stream = if (code in 200..299) inputStream else errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: ${body.take(500)}")
            }
            body
        } finally {
            disconnect()
        }
    }

    private fun desktopUserAgent(): String =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private fun preferredAcceptLanguage(query: String): String =
        if (query.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.ARABIC }) {
            "ar,${Locale.getDefault().toLanguageTag()};q=0.8,en-US;q=0.7,en;q=0.6"
        } else {
            "${Locale.getDefault().toLanguageTag()},en-US;q=0.8,en;q=0.7"
        }

    private suspend fun tryProvider(
        providerName: String,
        errors: MutableList<String>,
        block: suspend () -> List<SearchResult>
    ): List<SearchResult> {
        val result = try {
            block()
        } catch (e: Exception) {
            errors.add("$providerName: ${e.message}")
            return emptyList()
        }
        return result.also {
            if (it.isEmpty()) errors.add("$providerName: no results")
        }
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
