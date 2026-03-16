package com.omnidev.workspace.data.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

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

        val results = mutableListOf<SearchResult>()
        val normalizedQuery = query.trim()
        val errors = mutableListOf<String>()

        if (serpApiKey?.isNotBlank() == true) {
            runCatching { searchViaSerpApi(normalizedQuery, serpApiKey) }
                .onSuccess { results.addAll(it) }
                .onFailure { errors.add("SerpApi: ${it.message}") }
        }

        if (results.isEmpty() && googleApiKey?.isNotBlank() == true && googleCseCx?.isNotBlank() == true) {
            runCatching { searchViaGoogleCse(normalizedQuery, googleApiKey, googleCseCx) }
                .onSuccess { results.addAll(it) }
                .onFailure { errors.add("Google CSE: ${it.message}") }
        }

        if (results.isEmpty()) {
            runCatching { scrapeGoogle(normalizedQuery) }
                .onSuccess { results.addAll(it) }
                .onFailure { errors.add("Google scrape: ${it.message}") }
        }

        if (results.isEmpty()) {
            runCatching { scrapeDuckDuckGo(normalizedQuery) }
                .onSuccess { results.addAll(it) }
                .onFailure { errors.add("DuckDuckGo scrape: ${it.message}") }
        }

        val deduped = results
            .filter { it.title.isNotBlank() && it.url.startsWith("http") }
            .distinctBy { it.url }
            .take(MAX_RESULTS)

        if (deduped.isEmpty()) {
            val detail = if (errors.isNotEmpty()) "\nTried providers: ${errors.joinToString(" | ")}" else ""
            return@withContext ToolExecutionResult(
                output = "No results found for: \"$normalizedQuery\"$detail",
                isError = false
            )
        }

        ToolExecutionResult(formatAsMarkdown(normalizedQuery, deduped))
    }

    private fun searchViaSerpApi(query: String, apiKey: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://serpapi.com/search.json?q=$encoded&engine=google&api_key=$apiKey&num=$MAX_RESULTS"
        val json = httpGet(url)
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

    private fun searchViaGoogleCse(query: String, apiKey: String, cx: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.googleapis.com/customsearch/v1?key=$apiKey&cx=$cx&q=$encoded&num=$MAX_RESULTS"
        val json = httpGet(url)
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

    private fun scrapeGoogle(query: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = Jsoup.connect("https://www.google.com/search?q=$encoded&hl=en")
            .userAgent(desktopUserAgent())
            .header("Accept-Language", "en-US,en;q=0.9")
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

    private fun scrapeDuckDuckGo(query: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = Jsoup.connect("https://duckduckgo.com/html/?q=$encoded")
            .userAgent(desktopUserAgent())
            .header("Accept-Language", "en-US,en;q=0.9")
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

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", desktopUserAgent())
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Accept", "application/json,text/html,*/*")
        }
        return conn.useAndReadBody()
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

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
