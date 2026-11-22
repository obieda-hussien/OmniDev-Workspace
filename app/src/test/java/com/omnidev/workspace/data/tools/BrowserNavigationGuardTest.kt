package com.omnidev.workspace.data.tools

import org.junit.Assert.*
import org.junit.Test

class BrowserNavigationGuardTest {
    @Test fun `stale previous google page never verifies requested job site`() {
        val result = BrowserNavigationGuard.verify(
            "https://wuzzuf.net/search/jobs/?q=AI",
            "https://www.google.com/search?q=movies",
            "Just a moment...", true
        )
        assertEquals("BROWSER_UNVERIFIED_REDIRECT", result?.classification)
    }

    @Test fun `cloudflare title does not count as destination content`() {
        val result = BrowserNavigationGuard.verify(
            "https://wuzzuf.net/search/jobs/?q=AI",
            "https://wuzzuf.net/search/jobs/?q=AI",
            "لحظة…", true
        )
        assertEquals("BROWSER_CHALLENGE", result?.classification)
    }

    @Test fun `valid www redirects and ready page are accepted`() {
        assertNull(BrowserNavigationGuard.verify(
            "https://wuzzuf.net/search/jobs",
            "https://www.wuzzuf.net/search/jobs",
            "Jobs", true
        ))
    }

    @Test fun `partial load is unverified despite matching host`() {
        assertEquals("BROWSER_PAGE_NOT_READY", BrowserNavigationGuard.verify(
            "https://example.com/jobs", "https://example.com/jobs", "Jobs", false
        )?.classification)
    }

    @Test fun `unparseable destination is not reported as success`() {
        assertEquals("BROWSER_URL_UNVERIFIED", BrowserNavigationGuard.verify(
            "https://example.com", "about:blank", "", true
        )?.classification)
    }
}
