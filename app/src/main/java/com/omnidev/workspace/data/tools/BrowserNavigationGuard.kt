package com.omnidev.workspace.data.tools

import java.net.URI
import java.util.Locale

/**
 * DOM readiness means only that *some* page loaded. It does not prove that navigation
 * reached the requested site or passed an anti-bot interstitial.
 */
internal object BrowserNavigationGuard {
    data class Failure(val classification: String, val explanation: String)

    fun verify(requestedUrl: String, landedUrl: String, title: String, fullyReady: Boolean): Failure? {
        val requestedHost = host(requestedUrl)
        val landedHost = host(landedUrl)
        if (requestedHost == null || landedHost == null)
            return Failure("BROWSER_URL_UNVERIFIED", "Could not verify the requested and landed URLs.")
        if (requestedHost != landedHost)
            return Failure("BROWSER_UNVERIFIED_REDIRECT",
                "The browser landed on a different host ($landedHost instead of $requestedHost). " +
                    "This could be a redirect or stale navigation. Inspect the URL and page before continuing.")
        val pageTitle = title.trim().lowercase(Locale.ROOT)
        if (CHALLENGE_TITLES.any { it in pageTitle })
            return Failure("BROWSER_CHALLENGE",
                "An anti-bot or security-check page loaded, not verified destination content. " +
                    "User interaction or another permitted source may be needed.")
        if (!fullyReady)
            return Failure("BROWSER_PAGE_NOT_READY",
                "The page did not reach the required readiness score. Wait and inspect before interacting.")
        return null
    }

    private fun host(url: String): String? = runCatching {
        URI(url).host?.lowercase(Locale.ROOT)?.removePrefix("www.")?.takeIf(String::isNotBlank)
    }.getOrNull()

    private val CHALLENGE_TITLES = listOf(
        "just a moment", "checking your browser", "attention required",
        "verify you are human", "security check", "لحظة", "التحقق من الأمان"
    )
}
