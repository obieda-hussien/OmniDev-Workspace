package com.omnidev.workspace.data.tools

import org.jsoup.Jsoup

internal object ScholarlySearchProviders {
    /** arXiv performs announced-date descending sorting on the public result page. */
    suspend fun arxiv(query: String, lang: String): List<WebSearchHit> {
        val base = "https://arxiv.org/search/?query=${KeylessSearchHttp.encode(query)}&searchtype=all&abstracts=show&order=-announced_date_first&size=25"
        val doc = Jsoup.parse(KeylessSearchHttp.get(base, lang), base)
        return doc.select("li.arxiv-result").mapNotNull { node ->
            val anchor = node.selectFirst("p.list-title a[href*=\"/abs/\"], a[href*=\"/abs/\"]")
                ?: return@mapNotNull null
            val dateText = node.select("p.is-size-7")
                .firstOrNull { it.text().contains("Submitted", ignoreCase = true) }
                ?.text()
            SearchHitFactory.create(
                title = node.selectFirst("p.title")?.text().orEmpty(),
                url = KeylessSearchHttp.absolute(base, anchor.attr("href")),
                snippet = node.selectFirst("span.abstract-full, p.abstract")?.text().orEmpty()
                    .replace("△ Less", "").replace("▽ More", ""),
                source = "arXiv",
                publishedLabel = dateText,
                research = true
            )
        }.take(25)
    }

    /** PubMed public HTML search sorted by date; no E-utilities endpoint is used. */
    suspend fun pubMed(query: String, lang: String): List<WebSearchHit> {
        val base = "https://pubmed.ncbi.nlm.nih.gov/?term=${KeylessSearchHttp.encode(query)}&sort=date&size=50"
        val doc = Jsoup.parse(KeylessSearchHttp.get(base, lang), base)
        return doc.select("div.docsum-wrap, article.full-docsum").mapNotNull { node ->
            val anchor = node.selectFirst("a.docsum-title[href]") ?: return@mapNotNull null
            val citation = node.selectFirst(".docsum-journal-citation, .full-journal-citation")?.text().orEmpty()
            val snippet = node.selectFirst(".full-view-snippet, .docsum-snippet")?.text().orEmpty()
                .ifBlank { citation }
            SearchHitFactory.create(
                title = anchor.text(),
                url = KeylessSearchHttp.absolute(base, anchor.attr("href")),
                snippet = snippet,
                source = "PubMed",
                publishedLabel = citation,
                research = true
            )
        }.distinctBy { SearchSemantics.dedupKey(it) }.take(30)
    }
}
