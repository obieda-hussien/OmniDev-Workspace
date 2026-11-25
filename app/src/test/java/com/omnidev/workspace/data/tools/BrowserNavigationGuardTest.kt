package com.omnidev.workspace.data.tools

import org.junit.Assert.*
import org.junit.Test

class BrowserNavigationGuardTest {
    private fun verify(
        requested: String,
        landed: String,
        title: String = "Results",
        ready: Boolean = true,
        status: Int? = null
    ) = BrowserNavigationGuard.verify(requested, landed, landed, title, ready, status)

    @Test fun `stale previous google page never verifies requested job site`() {
        val result = verify(
            "https://wuzzuf.net/search/jobs/?q=AI",
            "https://www.google.com/search?q=movies",
            "Just a moment...", true
        )
        assertEquals("BROWSER_UNVERIFIED_REDIRECT", result?.classification)
    }

    @Test fun `cloudflare title does not count as destination content`() {
        val result = verify(
            "https://wuzzuf.net/search/jobs/?q=AI",
            "https://wuzzuf.net/search/jobs/?q=AI",
            "\u0644\u062d\u0638\u0629\u2026", true
        )
        assertEquals("BROWSER_CHALLENGE", result?.classification)
    }

    @Test fun `valid www redirects and ready page are accepted`() {
        assertNull(verify(
            "https://wuzzuf.net/search/jobs",
            "https://www.wuzzuf.net/search/jobs",
            "Jobs", true
        ))
    }

    @Test fun `same host stale path and search query are never verified`() {
        val requested = "https://example.com/search?q=jobs"
        listOf(
            "https://example.com/home",
            "https://example.com/search?q=movies",
            "https://example.com/search",
            "https://example.com/search?q=jobs#old-view"
        ).forEach { landed ->
            assertEquals(landed, "BROWSER_UNVERIFIED_REDIRECT",
                verify(requested, landed)?.classification)
        }
    }

    @Test fun `same host navigation cannot verify a different scheme or port`() {
        val requested = "https://example.com/search?q=jobs"
        listOf(
            "http://example.com/search?q=jobs",
            "https://example.com:8443/search?q=jobs"
        ).forEach { landed ->
            assertEquals(landed, "BROWSER_UNVERIFIED_REDIRECT",
                verify(requested, landed)?.classification)
        }
    }

    @Test fun `navigation finish callback must agree with the live WebView URL`() {
        val requested = "https://example.com/jobs"
        assertEquals("BROWSER_URL_UNVERIFIED", BrowserNavigationGuard.verify(
            requested, requested, "https://example.com/previous", "Previous page", true
        )?.classification)
    }

    @Test fun `ready HTTP error document is failed navigation evidence`() {
        val requested = "https://example.com/jobs"
        for (status in listOf(404, 500)) {
            assertEquals("BROWSER_HTTP_ERROR", verify(requested, requested, status = status)?.classification)
        }
        assertEquals("BROWSER_CHALLENGE",
            verify(requested, requested, title = "Just a moment...", status = 403)?.classification)
    }

    @Test fun `URL serialization keeps the same destination recognizable`() {
        assertNull(verify("https://example.com:443", "https://example.com/"))
        assertNull(verify("https://example.com/?q=وظائف",
            "https://example.com/?q=%D9%88%D8%B8%D8%A7%D8%A6%D9%81"))
    }

    @Test fun `WebView URL canonicalization still verifies the requested page`() {
        assertNull(verify(
            "https://example.com/a/%2E./search?q=women's+jobs",
            "https://example.com/search?q=women%27s+jobs"
        ))
        assertNull(verify("https://example.com/a/../jobs/.", "https://example.com/jobs/"))
    }

    @Test fun `canonicalized navigation keeps distinct paths and queries separate`() {
        assertEquals("BROWSER_UNVERIFIED_REDIRECT", verify(
            "https://example.com/a/../jobs//open?q=women's+jobs",
            "https://example.com/jobs/open?q=women%27s+jobs"
        )?.classification)
        assertEquals("BROWSER_UNVERIFIED_REDIRECT", verify(
            "https://example.com/a/../jobs?q=women's+jobs",
            "https://example.com/jobs?q=women%27s%2Bjobs"
        )?.classification)
    }

    @Test fun `partial load is unverified despite matching host`() {
        assertEquals("BROWSER_PAGE_NOT_READY", verify(
            "https://example.com/jobs", "https://example.com/jobs", "Jobs", false
        )?.classification)
    }

    @Test fun `unparseable destination is not reported as success`() {
        assertEquals("BROWSER_URL_UNVERIFIED", verify(
            "https://example.com", "about:blank", "", true
        )?.classification)
    }
}
