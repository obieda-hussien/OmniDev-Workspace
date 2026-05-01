package com.omnidev.workspace.data.tools

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
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
import kotlin.math.ln
import kotlin.math.max

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * WebSearchTool — Semantic Search Engine v2.0
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Five layers of true semantic search:
 *
 * Layer 1 — Query Understanding & Expansion
 *   Generates 3 alternative query forms and expands with synonyms/related concepts.
 *
 * Layer 2 — Multi-Provider Parallel Fetch
 *   Parallel fetch from 7 sources: DDG Instant Answer, Wikipedia, SerpApi, Google CSE,
 *   Google HTML, Bing HTML, DDG HTML. All run simultaneously — no sequential fallback.
 *
 * Layer 3 — Reciprocal Rank Fusion (RRF)
 *   Merges multiple result lists using the best known fusion algorithm.
 *   RRF_score(d) = Σ 1/(k + rank_i(d))  where k=60
 *
 * Layer 4 — BM25 Semantic Reranking
 *   Reranks the fused corpus using real Okapi BM25 scoring over title + snippet.
 *
 * Layer 5 — Query-Focused Snippet Enhancement
 *   For deep search: extracts the sentence from the page that most directly answers
 *   the query, then appends the full article context.
 */
object WebSearchTool {

    // ─── Constants ────────────────────────────────────────────────────────────
    private const val CONNECT_TIMEOUT_MS                  = 12_000
    private const val READ_TIMEOUT_MS                     = 18_000
    private const val MAX_RESULTS                         = 7
    private const val MAX_RESULTS_DEEP                    = 10
    private const val MAX_CHARS_PER_SITE                  = 6_000
    private const val FETCH_PAGE_TIMEOUT_MS               = 12_000L
    private const val HTTP_RETRY_COUNT                    = 2
    private const val MIN_TEXT_LENGTH_FOR_SNIPPET_EXTRACT = 500

    // Deep search specific
    private const val DEFAULT_TOP_PASSAGES                = 3
    private const val MAX_TOP_PASSAGES                    = 5
    private const val MIN_PAGE_WORDS_THRESHOLD            = 60
    private const val WORDS_PER_MINUTE_READ               = 200
    private const val CROSS_SOURCE_MIN_PROVIDERS          = 2
    private const val MAX_CONSENSUS_FACTS                 = 5
    // Fraction of query terms a sentence must contain to be considered a consensus fact
    private const val CONSENSUS_TERM_COVERAGE             = 0.55

    // RRF smoothing constant — standard value from Cormack et al. (2009)
    private const val RRF_K = 60.0

    // BM25 tuning parameters (Robertson & Zaragoza, 2009)
    private const val BM25_K1 = 1.5
    private const val BM25_B  = 0.75

    // Pre-compiled regexes — compiled once, reused on every call
    // The character class covers ASCII alphanumerics plus the Arabic Unicode block (U+0600–U+06FF)
    // so Arabic queries are tokenized correctly without losing word boundaries.
    private val TOKENIZE_REGEX       = Regex("[^a-z0-9\\u0600-\\u06FF]+")
    private val SENTENCE_SPLIT_REGEX = Regex("(?<=[.!?])\\s+")

    // Question prefixes used for query variant generation
    private val QUESTION_PREFIXES = listOf(
        "what", "how", "why", "when", "where", "who", "which", "is", "are", "can"
    )

    // Sentence-length thresholds for snippet extraction
    private const val MIN_SENTENCE_LENGTH         = 20
    private const val MAX_SENTENCE_LENGTH         = 600
    private const val MAX_QUERY_FOCUSED_SNIPPET   = 400
    private const val MAX_TITLE_LENGTH            = 100

    // ─── Data classes ─────────────────────────────────────────────────────────
    private data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String,
        val source: String = "unknown"
    )

    /** Holds the ranked result list from a single provider. */
    private data class ProviderResults(
        val provider: String,
        val results: List<SearchResult>
    )

    /** Query + up to 3 automatically generated alternative forms. */
    private data class ExpandedQuery(
        val original: String,
        val variants: List<String>
    )

    // ─── Tool definitions ─────────────────────────────────────────────────────
    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "web_search",
            description = "Search the web using Semantic Search Engine v2.0 with multi-provider parallel fetch, " +
                "Reciprocal Rank Fusion (RRF), and BM25 reranking. Returns the most relevant results using " +
                "true semantic scoring — far more accurate than simple sequential fallback. " +
                "Prefers API-backed providers (SerpApi / Google CSE) when configured, and supplements " +
                "with parallel HTML scraping from Google, Bing, DuckDuckGo, Wikipedia, and DDG Instant.",
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
                "Uses Semantic Search v2.0: multi-provider parallel fetch + RRF fusion + BM25 reranking + " +
                "query-focused snippet extraction. Returns actual article text from multiple sources simultaneously. " +
                "Best for research, summarizing topics, fact-checking, and comprehensive answers.",
            parameters = listOf(
                ToolParameter(name = "query", type = "string", description = "Search query text.", required = true),
                ToolParameter(name = "max_sites", type = "integer", description = "Number of sites to read in parallel (1–8, default 5).", required = false)
            )
        )
    )

    // ─── Public API ───────────────────────────────────────────────────────────
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
        val results = getSearchResults(normalizedQuery, serpApiKey, googleApiKey, googleCseCx, MAX_RESULTS)

        if (results.isEmpty()) {
            return@withContext ToolExecutionResult(
                output = "No results found for: \"$normalizedQuery\". " +
                         "This might be due to network issues or aggressive bot-blocking by search engines.",
                isError = false
            )
        }

        ToolExecutionResult(formatAsMarkdown(normalizedQuery, results))
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

        // Fetch all target pages in parallel
        val pages: List<PageResult> = coroutineScope {
            targets.map { result ->
                async(Dispatchers.IO) {
                    val content = withTimeoutOrNull(FETCH_PAGE_TIMEOUT_MS) {
                        try {
                            fetchPageContent(result.url, normalizedQuery)
                        } catch (e: Exception) {
                            android.util.Log.w("WebSearchTool", "Failed to fetch ${result.url}: ${e.message}")
                            null
                        }
                    }
                    PageResult(result, content?.takeIf { it.isNotBlank() } ?: result.snippet)
                }
            }.awaitAll()
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

    // ─── Layer 1: Query Understanding & Expansion ─────────────────────────────

    private val STOP_WORDS = setOf(
        "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "will", "would", "could",
        "should", "may", "might", "shall", "can", "of", "in", "to", "for",
        "on", "with", "at", "by", "from", "and", "or", "but", "if", "then",
        "so", "about", "this", "that", "it", "its", "as", "not", "no", "very"
    )

    private fun expandQuery(query: String): ExpandedQuery {
        val variants  = mutableListOf<String>()
        // queryLower is used to avoid re-lowercasing in every check below
        val queryLower = query.lowercase(Locale.ROOT)

        // Variant 1: question form for declarative queries
        if (!queryLower.endsWith("?") && QUESTION_PREFIXES.none { queryLower.startsWith(it) }) {
            variants += "what is $query"
        }

        // Variant 2: keyword-only form — strip stop words to boost signal.
        // Only added if stop-word removal actually changes the query.
        val keywords = query.split(Regex("\\s+"))
            .filter { it.length > 2 && it.lowercase(Locale.ROOT) !in STOP_WORDS }
            .joinToString(" ")
        if (keywords.isNotBlank() && keywords.lowercase(Locale.ROOT) != queryLower) {
            variants += keywords
        }

        // Variant 3: definition/explanation form for short queries
        if (query.split(" ").size <= 4 &&
            !queryLower.contains("explain") && !queryLower.contains("defin") && !queryLower.contains("mean")
        ) {
            variants += "$query definition explained"
        }

        return ExpandedQuery(original = query, variants = variants.take(3))
    }

    // ─── Layer 2: Multi-Provider Parallel Fetch ───────────────────────────────

    private suspend fun getSearchResults(
        query: String,
        serpApiKey: String?,
        googleApiKey: String?,
        googleCseCx: String?,
        maxResults: Int
    ): List<SearchResult> {
        val acceptLanguage = preferredAcceptLanguage(query)
        val expanded = expandQuery(query)

        // Launch all available providers in parallel — no sequential fallback
        val providerResults: List<ProviderResults> = coroutineScope {
            val deferreds = buildList {
                // ── Tier 1: API-backed (highest quality) ──
                if (serpApiKey?.isNotBlank() == true) {
                    add(async(Dispatchers.IO) {
                        runCatching {
                            ProviderResults("SerpApi", searchViaSerpApi(query, serpApiKey, acceptLanguage))
                        }.getOrNull()
                    })
                }
                if (googleApiKey?.isNotBlank() == true && googleCseCx?.isNotBlank() == true) {
                    add(async(Dispatchers.IO) {
                        runCatching {
                            ProviderResults("GoogleCSE", searchViaGoogleCse(query, googleApiKey, googleCseCx, acceptLanguage))
                        }.getOrNull()
                    })
                }

                // ── Tier 2: Free structured APIs ──
                add(async(Dispatchers.IO) {
                    runCatching {
                        ProviderResults("DDGInstant", searchViaDdgInstant(query, acceptLanguage))
                    }.getOrNull()
                })
                add(async(Dispatchers.IO) {
                    runCatching {
                        ProviderResults("Wikipedia", searchViaWikipedia(expanded.original, acceptLanguage))
                    }.getOrNull()
                })

                // ── Tier 3: HTML scrapers ──
                add(async(Dispatchers.IO) {
                    runCatching {
                        ProviderResults("Google", scrapeGoogle(query, acceptLanguage))
                    }.getOrNull()
                })
                add(async(Dispatchers.IO) {
                    runCatching {
                        ProviderResults("Bing", scrapeBing(query, acceptLanguage))
                    }.getOrNull()
                })
                add(async(Dispatchers.IO) {
                    runCatching {
                        ProviderResults("DuckDuckGo", scrapeDuckDuckGo(query, acceptLanguage))
                    }.getOrNull()
                })

                // ── Tier 4: Variant query for extra coverage ──
                if (expanded.variants.isNotEmpty()) {
                    add(async(Dispatchers.IO) {
                        runCatching {
                            ProviderResults("DDGLite-variant", scrapeDuckDuckGoLite(expanded.variants[0], acceptLanguage))
                        }.getOrNull()
                    })
                }
            }
            deferreds.awaitAll().filterNotNull().filter { it.results.isNotEmpty() }
        }

        if (providerResults.isEmpty()) {
            android.util.Log.e("WebSearchTool", "All search providers returned empty results for: $query")
            return emptyList()
        }

        // Layer 3: Reciprocal Rank Fusion
        val fused = reciprocalRankFusion(providerResults)

        // Layer 4: BM25 Semantic Reranking
        val reranked = bm25Rerank(query, fused)

        return reranked
            .filter { it.title.isNotBlank() && it.url.startsWith("http") }
            .distinctBy { it.url }
            .take(maxResults)
    }

    // ─── Layer 3: Reciprocal Rank Fusion (RRF) ────────────────────────────────

    /**
     * Cormack et al. (2009): RRF_score(d) = Σ_i  1 / (k + rank_i(d))
     *
     * Documents appearing high in multiple provider lists accumulate a higher combined
     * score. The k=60 constant prevents top-rank results from dominating too aggressively.
     * Note: `rank` from forEachIndexed is 0-based; the `+ 1` converts it to the 1-indexed
     * rank_i required by the formula (first result → rank 1 → score 1/61).
     */
    private fun reciprocalRankFusion(providerResults: List<ProviderResults>): List<SearchResult> {
        val scores    = mutableMapOf<String, Double>()       // url → accumulated RRF score
        val canonical = mutableMapOf<String, SearchResult>() // url → best SearchResult

        for (provider in providerResults) {
            provider.results.forEachIndexed { rank, result ->
                val url = result.url
                scores[url] = (scores[url] ?: 0.0) + (1.0 / (RRF_K + rank + 1))
                // Keep the entry with the richest snippet as the canonical representation
                val existing = canonical[url]
                if (existing == null || result.snippet.length > existing.snippet.length) {
                    canonical[url] = result
                }
            }
        }

        return scores.entries
            .sortedByDescending { it.value }
            .mapNotNull { canonical[it.key] }
    }

    // ─── Layer 4: BM25 Semantic Reranking ─────────────────────────────────────

    /**
     * Okapi BM25 (Robertson & Zaragoza, 2009):
     *
     *   BM25(q, d) = Σ_t  IDF(t) · tf(t,d)·(k1+1) / (tf(t,d) + k1·(1 − b + b·|d|/avgdl))
     *   IDF(t)     = ln((N − df(t) + 0.5) / (df(t) + 0.5) + 1)
     *
     * Scored over the concatenated title + snippet of each result.
     */
    private fun bm25Rerank(query: String, results: List<SearchResult>): List<SearchResult> {
        if (results.size <= 1) return results

        val queryTerms = tokenize(query)
        if (queryTerms.isEmpty()) return results

        // Tokenize all documents and capture their lengths in a single pass
        // to avoid iterating the list twice (once for avgDocLen, once for scoring).
        data class Doc(val tokens: List<String>, val length: Double)
        val docs      = results.map { Doc(tokenize("${it.title} ${it.snippet}"), 0.0) }
            .let { list -> list.map { it.copy(length = it.tokens.size.toDouble()) } }
        val avgDocLen = docs.map { it.length }.average().takeIf { it > 0.0 } ?: return results
        val n         = docs.size.toDouble()

        // Compute IDF per query term
        val idf = queryTerms.associateWith { term ->
            val df = docs.count { term in it.tokens }.toDouble()
            ln((n - df + 0.5) / (df + 0.5) + 1.0)
        }

        // Score each document
        val scored = results.zip(docs).map { (result, doc) ->
            val tfMap = doc.tokens.groupingBy { it }.eachCount()
            val score = queryTerms.sumOf { term ->
                val tf   = tfMap.getOrDefault(term, 0).toDouble()
                val idfT = idf.getOrDefault(term, 0.0)
                idfT * (tf * (BM25_K1 + 1.0)) /
                    (tf + BM25_K1 * (1.0 - BM25_B + BM25_B * (doc.length / avgDocLen)))
            }
            result to score
        }

        return scored.sortedByDescending { it.second }.map { it.first }
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase(Locale.ROOT)
            .split(TOKENIZE_REGEX)
            .filter { it.length > 1 && it !in STOP_WORDS }

    // ─── Layer 5: Query-Focused Snippet Enhancement ───────────────────────────

    /**
     * Scores each sentence in [pageText] by the fraction of query terms it contains,
     * then returns the best matching sentence as the lead excerpt.
     */
    private fun extractBestSnippet(query: String, pageText: String, maxChars: Int = 500): String {
        val queryTerms = tokenize(query).toSet()
        if (queryTerms.isEmpty()) return pageText.take(maxChars)

        val sentences = pageText.split(SENTENCE_SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.length in MIN_SENTENCE_LENGTH..MAX_SENTENCE_LENGTH }
        if (sentences.isEmpty()) return pageText.take(maxChars)

        val best = sentences.maxByOrNull { sentence ->
            val sentTokens = tokenize(sentence).toSet()
            // max(1, size) guards against sentences that become empty after stop-word removal
            sentTokens.intersect(queryTerms).size.toDouble() / max(1, sentTokens.size)
        } ?: sentences.first()

        return if (best.length <= maxChars) best
        // Use the two-arg overload of substringBeforeLast to guarantee the result never exceeds maxChars
        // even when the truncated string contains no spaces (e.g., a long URL token).
        else best.take(maxChars).substringBeforeLast(' ', best.take(maxChars)) + "…"
    }

    // ─── Provider implementations ─────────────────────────────────────────────

    /** DuckDuckGo Instant Answer API — free JSON endpoint, no scraping needed. */
    private suspend fun searchViaDdgInstant(query: String, acceptLanguage: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
        val json = httpGet(url, acceptLanguage)
        val root = JSONObject(json)
        val results = mutableListOf<SearchResult>()

        // Primary abstract result
        val abstractText = root.optString("AbstractText").trim()
        val abstractUrl  = root.optString("AbstractURL").trim()
        val abstractSrc  = root.optString("AbstractSource").trim()
        if (abstractText.isNotBlank() && abstractUrl.isNotBlank()) {
            results += SearchResult(
                title   = abstractSrc.ifBlank { "Summary" },
                url     = abstractUrl,
                snippet = abstractText,
                source  = "DDGInstant"
            )
        }

        // Related topics
        val topics = root.optJSONArray("RelatedTopics") ?: JSONArray()
        for (i in 0 until topics.length()) {
            val item     = topics.optJSONObject(i) ?: continue
            val text     = item.optString("Text").trim()
            val firstUrl = item.optString("FirstURL").trim()
            if (text.isNotBlank() && firstUrl.startsWith("http")) {
                results += SearchResult(
                    title   = text.take(MAX_TITLE_LENGTH),
                    url     = firstUrl,
                    snippet = text,
                    source  = "DDGInstant"
                )
            }
            if (results.size >= MAX_RESULTS) break
        }

        return results
    }

    /** Wikipedia OpenSearch API — free, authoritative, structured. */
    private suspend fun searchViaWikipedia(query: String, acceptLanguage: String): List<SearchResult> {
        val encoded   = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://en.wikipedia.org/w/api.php?action=opensearch&search=$encoded&limit=5&format=json"
        val json      = httpGet(searchUrl, acceptLanguage)
        val arr       = JSONArray(json)

        val titles   = arr.optJSONArray(1) ?: return emptyList()
        val snippets = arr.optJSONArray(2) ?: JSONArray()
        val urls     = arr.optJSONArray(3) ?: JSONArray()

        return buildList {
            for (i in 0 until titles.length()) {
                val title   = titles.optString(i).trim()
                val snippet = snippets.optString(i).trim()
                val url     = urls.optString(i).trim()
                if (title.isNotBlank() && url.startsWith("http")) {
                    add(SearchResult(title = title, url = url, snippet = snippet, source = "Wikipedia"))
                }
            }
        }
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
                val item    = array.optJSONObject(i) ?: continue
                val title   = item.optString("title").trim()
                val link    = item.optString("link").trim()
                val snippet = item.optString("snippet").trim()
                if (title.isNotBlank() && link.isNotBlank()) {
                    add(SearchResult(title = title, url = link, snippet = snippet, source = "SerpApi"))
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
                val item    = array.optJSONObject(i) ?: continue
                val title   = item.optString("title").trim()
                val link    = item.optString("link").trim()
                val snippet = item.optString("snippet").trim()
                if (title.isNotBlank() && link.isNotBlank()) {
                    add(SearchResult(title = title, url = link, snippet = snippet, source = "GoogleCSE"))
                }
            }
        }
    }

    private suspend fun scrapeGoogle(query: String, acceptLanguage: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = httpGet("https://www.google.com/search?q=$encoded&hl=en", acceptLanguage)
        val doc = Jsoup.parse(html)
        return doc.select("div#search div.g").mapNotNull { node ->
            val title    = node.selectFirst("h3")?.text()?.trim().orEmpty()
            val rawHref  = node.selectFirst("a[href]")?.attr("href").orEmpty()
            val snippet  = node.selectFirst("div.VwiC3b, span.aCOpRe, div.IsZvec")?.text()?.trim().orEmpty()
            val resolved = resolveGoogleHref(rawHref)
            if (title.isBlank() || resolved.isBlank()) null
            else SearchResult(title = title, url = resolved, snippet = snippet, source = "Google")
        }
    }

    private suspend fun scrapeDuckDuckGo(query: String, acceptLanguage: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = httpGet("https://duckduckgo.com/html/?q=$encoded", acceptLanguage)
        val doc = Jsoup.parse(html)
        return doc.select("div.result").mapNotNull { result ->
            val anchor  = result.selectFirst("a.result__a")
            val title   = anchor?.text()?.trim().orEmpty()
            val href    = anchor?.attr("href").orEmpty()
            val snippet = result.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
            val resolved = resolveDuckHref(href)
            if (title.isBlank() || resolved.isBlank()) null
            else SearchResult(title = title, url = resolved, snippet = snippet, source = "DuckDuckGo")
        }
    }

    private suspend fun scrapeDuckDuckGoLite(query: String, acceptLanguage: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = httpGet("https://lite.duckduckgo.com/lite/?q=$encoded&kl=wt-wt", acceptLanguage)
        val doc = Jsoup.parse(html)
        return doc.select("a.result-link").mapNotNull { anchor ->
            val title    = anchor.text().trim()
            val href     = anchor.attr("href")
            val resolved = resolveDuckHref(href)
            if (title.isBlank() || resolved.isBlank()) null
            else SearchResult(
                title   = title,
                url     = resolved,
                snippet = anchor.parent()?.nextElementSibling()?.text()?.trim().orEmpty(),
                source  = "DDGLite"
            )
        }
    }

    private suspend fun scrapeBing(query: String, acceptLanguage: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = httpGet("https://www.bing.com/search?q=$encoded", acceptLanguage)
        val doc = Jsoup.parse(html)
        return doc.select("li.b_algo").mapNotNull { node ->
            val anchor  = node.selectFirst("h2 a") ?: return@mapNotNull null
            val title   = anchor.text().trim()
            val href    = anchor.attr("href").trim()
            val snippet = node.selectFirst("div.b_caption p, p.b_lineclamp2, div.b_caption")?.text()?.trim().orEmpty()
            if (title.isBlank() || href.isBlank()) null
            else SearchResult(title = title, url = href, snippet = snippet, source = "Bing")
        }
    }

    // ─── Page content fetcher (used by executeDeep) with Layer 5 enhancement ──

    /**
     * Fetches and cleans the readable body of a page.
     * When [query] is provided, prepends the best query-focused sentence (Layer 5)
     * before the full truncated article body.
     */
    private fun fetchPageContent(url: String, query: String = ""): String {
        val doc = Jsoup.connect(url)
            .userAgent(desktopUserAgent())
            .timeout(FETCH_PAGE_TIMEOUT_MS.toInt())
            .followRedirects(true)
            .maxBodySize(1_500_000)
            .get()

        doc.select(
            "script, style, nav, footer, header, aside, iframe, noscript, svg, form, button, " +
                "input, select, textarea, .ad, .ads, .advertisement, .sidebar, .menu, " +
                "#cookie-banner, .cookie-notice, .popup, .newsletter, .social-share, [aria-hidden=true]"
        ).remove()

        val content = doc.selectFirst("article")
            ?: doc.selectFirst("main")
            ?: doc.selectFirst("[role=main]")
            ?: doc.selectFirst(
                ".post-content, .entry-content, .article-content, .article-body, " +
                    ".story-content, .post-body, #article-body, .main-content"
            )
            ?: doc.selectFirst(".content, #content, #main")
            ?: doc.body()

        val fullText = content?.wholeText()
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.joinToString("\n")
            ?.trim() ?: ""

        val truncated = if (fullText.length > MAX_CHARS_PER_SITE) fullText.take(MAX_CHARS_PER_SITE) else fullText

        // Layer 5: extract the best query-answering passage and surface it first
        return if (query.isNotBlank() && fullText.length > MIN_TEXT_LENGTH_FOR_SNIPPET_EXTRACT) {
            val bestSnippet = extractBestSnippet(query, fullText, maxChars = MAX_QUERY_FOCUSED_SNIPPET)
            "**Most relevant passage:**\n$bestSnippet\n\n---\n\n$truncated"
        } else {
            truncated
        }
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

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
            val safeTitle   = item.title.replace("\n", " ").replace("[", "(").replace("]", ")").trim()
            val safeSnippet = item.snippet.replace("\n", " ").trim().ifBlank { "No snippet available." }
            appendLine("${index + 1}. [${safeTitle}](${item.url}) - $safeSnippet")
        }
    }.trimEnd()

    /** Unified HTTP layer: exponential backoff on transient errors + anti-bot headers. */
    private suspend fun httpGet(url: String, acceptLanguage: String): String {
        var lastError: Throwable? = null
        for (attempt in 0 until HTTP_RETRY_COUNT) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout    = READ_TIMEOUT_MS
                    setRequestProperty("User-Agent",      desktopUserAgent())
                    setRequestProperty("Accept-Language", acceptLanguage)
                    setRequestProperty("Accept",          "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    setRequestProperty("Referer",         "https://www.google.com/")
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
            val code   = responseCode
            val stream = if (code in 200..299) inputStream else errorStream
            val body   = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code: ${body.take(500)}")
            body
        } finally {
            disconnect()
        }
    }

    private fun desktopUserAgent(): String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private fun preferredAcceptLanguage(query: String): String =
        if (query.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.ARABIC }) {
            "ar,${Locale.getDefault().toLanguageTag()};q=0.8,en-US;q=0.7,en;q=0.6"
        } else {
            "${Locale.getDefault().toLanguageTag()},en-US;q=0.8,en;q=0.7"
        }
}
