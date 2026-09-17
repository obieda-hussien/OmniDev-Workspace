package com.omnidev.workspace.data.tools

import org.jsoup.Jsoup

internal object GeneralSearchProviders {
    suspend fun duckDuckGo(query: String, lang: String): List<WebSearchHit> {
        val base = "https://html.duckduckgo.com/html/?q=${KeylessSearchHttp.encode(query)}"
        val doc = Jsoup.parse(KeylessSearchHttp.get(base, lang), base)
        return doc.select("div.result, div.web-result").mapNotNull { node ->
            val anchor = node.selectFirst("a.result__a[href], h2 a[href]") ?: return@mapNotNull null
            val href = KeylessSearchHttp.duckHref(anchor.attr("href"))
                .ifBlank { KeylessSearchHttp.absolute(base, anchor.attr("href")) }
            SearchHitFactory.create(
                anchor.text(), href,
                node.selectFirst(".result__snippet, .result-snippet")?.text().orEmpty(),
                "DuckDuckGo"
            )
        }.take(20)
    }

    suspend fun duckDuckGoLite(query: String, lang: String): List<WebSearchHit> {
        val base = "https://lite.duckduckgo.com/lite/?q=${KeylessSearchHttp.encode(query)}&kl=wt-wt"
        val doc = Jsoup.parse(KeylessSearchHttp.get(base, lang), base)
        return doc.select("a.result-link[href], a.result__a[href]").mapNotNull { anchor ->
            val href = KeylessSearchHttp.duckHref(anchor.attr("href"))
                .ifBlank { KeylessSearchHttp.absolute(base, anchor.attr("href")) }
            val snippet = anchor.closest("tr")?.nextElementSibling()?.text().orEmpty()
                .ifBlank { anchor.parent()?.nextElementSibling()?.text().orEmpty() }
            SearchHitFactory.create(anchor.text(), href, snippet, "DuckDuckGo Lite")
        }.take(20)
    }

    suspend fun bing(query: String, lang: String): List<WebSearchHit> {
        val base = "https://www.bing.com/search?q=${KeylessSearchHttp.encode(query)}&count=20"
        val doc = Jsoup.parse(KeylessSearchHttp.get(base, lang), base)
        return doc.select("li.b_algo, div.b_algo").mapNotNull { node ->
            val anchor = node.selectFirst("h2 a[href], h3 a[href]") ?: return@mapNotNull null
            SearchHitFactory.create(
                anchor.text(), KeylessSearchHttp.absolute(base, anchor.attr("href")),
                node.selectFirst("div.b_caption p, p.b_lineclamp2, .b_caption")?.text().orEmpty(),
                "Bing"
            )
        }.take(20)
    }

    suspend fun google(query: String, lang: String): List<WebSearchHit> {
        val base = "https://www.google.com/search?q=${KeylessSearchHttp.encode(query)}&num=20&filter=0"
        val doc = Jsoup.parse(KeylessSearchHttp.get(base, lang), base)
        return doc.select("div#search div.g, div.MjjYud").mapNotNull { node ->
            val h3 = node.selectFirst("h3") ?: return@mapNotNull null
            val anchor = h3.closest("a") ?: node.selectFirst("a[href]") ?: return@mapNotNull null
            val href = KeylessSearchHttp.googleHref(anchor.attr("href"))
                .ifBlank { KeylessSearchHttp.absolute(base, anchor.attr("href")) }
            SearchHitFactory.create(
                h3.text(), href,
                node.selectFirst("div.VwiC3b, span.aCOpRe, div.IsZvec, div[data-sncf]")?.text().orEmpty(),
                "Google HTML"
            )
        }.distinctBy { SearchSemantics.dedupKey(it) }.take(20)
    }

    suspend fun mojeek(query: String, lang: String): List<WebSearchHit> {
        val base = "https://www.mojeek.com/search?q=${KeylessSearchHttp.encode(query)}"
        val doc = Jsoup.parse(KeylessSearchHttp.get(base, lang), base)
        return doc.select("ul.results-standard > li, li.result, div.result").mapNotNull { node ->
            val anchor = node.selectFirst("h2 a[href], h3 a[href], a.title[href]") ?: return@mapNotNull null
            SearchHitFactory.create(
                anchor.text(), KeylessSearchHttp.absolute(base, anchor.attr("href")),
                node.selectFirst("p.s, p.snippet, .snippet, .s")?.text().orEmpty(),
                "Mojeek"
            )
        }.take(20)
    }

    suspend fun wikipedia(query: String, lang: String): List<WebSearchHit> {
        val host = if (containsArabic(query)) "ar.wikipedia.org" else "en.wikipedia.org"
        val base = "https://$host/w/index.php?search=${KeylessSearchHttp.encode(query)}&title=Special%3ASearch&ns0=1"
        val doc = Jsoup.parse(KeylessSearchHttp.get(base, lang), base)
        val results = doc.select("li.mw-search-result, div.mw-search-result")
        if (results.isNotEmpty()) {
            return results.mapNotNull { node ->
                val anchor = node.selectFirst(".mw-search-result-heading a[href], a[href]") ?: return@mapNotNull null
                SearchHitFactory.create(
                    anchor.text(), KeylessSearchHttp.absolute(base, anchor.attr("href")),
                    node.selectFirst(".searchresult")?.text().orEmpty(), "Wikipedia"
                )
            }.take(10)
        }
        return listOfNotNull(
            SearchHitFactory.create(
                doc.selectFirst("h1#firstHeading, h1.firstHeading")?.text().orEmpty(),
                doc.selectFirst("link[rel=canonical]")?.attr("href").orEmpty(),
                doc.selectFirst("div.mw-parser-output > p:not(.mw-empty-elt)")?.text().orEmpty(),
                "Wikipedia"
            )
        )
    }

    private fun containsArabic(text: String): Boolean = text.any {
        Character.UnicodeBlock.of(it) == Character.UnicodeBlock.ARABIC
    }
}
