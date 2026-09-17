package com.omnidev.workspace.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class SearchRankingEngineTest {
    private fun epoch(date: String): Long = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.parse(date)!!.time

    @Test
    fun `newest relevant research is ranked before older higher-rank paper`() {
        val older = WebSearchHit(
            title = "LLM safety benchmark methods",
            url = "https://arxiv.org/abs/2501.00001",
            snippet = "LLM safety benchmark research methods",
            source = "arXiv",
            publishedAtEpochMs = epoch("2025-01-05"),
            isResearch = true
        )
        val newer = WebSearchHit(
            title = "New LLM safety benchmark",
            url = "https://arxiv.org/abs/2609.00002",
            snippet = "New research benchmark for LLM safety",
            source = "arXiv",
            publishedAtEpochMs = epoch("2026-09-15"),
            isResearch = true
        )
        val ranked = SearchRankingEngine.rank(
            query = "latest LLM safety research benchmark",
            batches = listOf(
                WebSearchProviderBatch("arXiv", listOf(older, newer)),
                WebSearchProviderBatch("DuckDuckGo", listOf(older))
            ),
            maxResults = 10,
            mode = WebSearchMode.DEEP,
            now = epoch("2026-09-17")
        )
        assertEquals(newer.url, ranked.first().url)
    }

    @Test
    fun `tracking parameters collapse to one canonical result`() {
        val a = WebSearchHit(
            "Omni search architecture",
            "https://example.com/research?id=7&utm_source=x&fbclid=abc",
            "search architecture research",
            "DuckDuckGo"
        )
        val b = a.copy(url = "https://www.example.com/research?fbclid=other&id=7")
        assertEquals(SearchSemantics.dedupKey(a), SearchSemantics.dedupKey(b))
        val ranked = SearchRankingEngine.rank(
            "search architecture research",
            listOf(WebSearchProviderBatch("one", listOf(a)), WebSearchProviderBatch("two", listOf(b))),
            10,
            WebSearchMode.QUICK,
            epoch("2026-09-17")
        )
        assertEquals(1, ranked.size)
    }

    @Test
    fun `arxiv and pubmed style dates are parsed`() {
        assertEquals(
            epoch("2026-08-06"),
            SearchSemantics.parseDate("Submitted 6 August, 2026; originally announced August 2026")
        )
        assertEquals(
            epoch("2026-09-14"),
            SearchSemantics.parseDate("Nat Med. 2026 Sep 14;32(9):100-110")
        )
    }

    @Test
    fun `research intent works in english and arabic`() {
        assertTrue(SearchSemantics.isResearchIntent("latest LLM research papers"))
        assertTrue(SearchSemantics.isResearchIntent("أحدث أبحاث الذكاء الاصطناعي"))
        assertFalse(SearchSemantics.isResearchIntent("weather in Cairo"))
    }

    @Test
    fun `near duplicate mirrored titles are removed`() {
        val first = WebSearchHit(
            "A New Benchmark for Reliable Agentic Search Systems",
            "https://a.example/paper",
            "agentic search systems benchmark",
            "arXiv",
            epoch("2026-09-10"),
            isResearch = true
        )
        val mirror = first.copy(url = "https://mirror.example/paper", source = "DuckDuckGo", isResearch = false)
        val ranked = SearchRankingEngine.rank(
            "agentic search benchmark research",
            listOf(WebSearchProviderBatch("a", listOf(first)), WebSearchProviderBatch("b", listOf(mirror))),
            10,
            WebSearchMode.DEEP,
            epoch("2026-09-17")
        )
        assertEquals(1, ranked.size)
    }
}
