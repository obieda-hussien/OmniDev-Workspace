package com.omnidev.workspace.data.tools

import java.net.URI
import java.util.Locale

/**
 * DOM readiness means only that *some* page loaded. It does not prove that navigation
 * reached the requested site or passed an anti-bot interstitial.
 */
internal object BrowserNavigationGuard {
    data class Failure(val classification: String, val explanation: String)

    fun verify(
        requestedUrl: String,
        finishedUrl: String,
        currentUrl: String,
        title: String,
        fullyReady: Boolean,
        httpStatus: Int? = null
    ): Failure? {
        val requested = httpUri(requestedUrl)
        val finished = httpUri(finishedUrl)
        val current = httpUri(currentUrl)
        if (requested == null || finished == null || current == null)
            return Failure("BROWSER_URL_UNVERIFIED", "Could not verify the requested and landed URLs.")
        if (!sameOrigin(finished, current) || !samePage(finished, current))
            return Failure("BROWSER_URL_UNVERIFIED",
                "The current page changed after the navigation callback. Inspect the current page before continuing.")
        if (!sameOrigin(requested, current, allowWwwAlias = true))
            return Failure("BROWSER_UNVERIFIED_REDIRECT",
                "The browser landed on a different scheme, host or port. Inspect the URL and page before continuing.")
        val pageTitle = title.trim().lowercase(Locale.ROOT)
        if (CHALLENGE_TITLES.any { it in pageTitle })
            return Failure("BROWSER_CHALLENGE",
                "An anti-bot or security-check page loaded, not verified destination content. " +
                    "User interaction or another permitted source may be needed.")
        if (httpStatus != null && httpStatus >= 400)
            return Failure("BROWSER_HTTP_ERROR", "The main page returned HTTP $httpStatus.")
        if (!samePage(requested, current))
            return Failure("BROWSER_UNVERIFIED_REDIRECT",
                "The browser landed on a different path, query or fragment. Inspect the URL and page before continuing.")
        if (!fullyReady)
            return Failure("BROWSER_PAGE_NOT_READY",
                "The page did not reach the required readiness score. Wait and inspect before interacting.")
        return null
    }

    private fun httpUri(url: String): URI? = runCatching {
        val uri = URI(url)
        if (url.any { it.code > 127 }) URI(uri.toASCIIString()) else uri
    }.getOrNull()?.takeIf {
        (it.scheme.equals("https", ignoreCase = true) || it.scheme.equals("http", ignoreCase = true)) &&
            !it.host.isNullOrBlank() && it.rawUserInfo == null && it.port in -1..65535
    }

    private fun sameOrigin(first: URI, second: URI, allowWwwAlias: Boolean = false): Boolean {
        val firstHost = first.host.lowercase(Locale.ROOT)
        val secondHost = second.host.lowercase(Locale.ROOT)
        val matchesHost = firstHost == secondHost ||
            (allowWwwAlias && firstHost.removePrefix("www.") == secondHost.removePrefix("www."))
        return matchesHost && first.scheme.equals(second.scheme, ignoreCase = true) &&
            effectivePort(first) == effectivePort(second)
    }

    private fun effectivePort(uri: URI): Int =
        if (uri.port != -1) uri.port else if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80

    private fun samePage(first: URI, second: URI): Boolean =
        first.rawPath.ifEmpty { "/" } == second.rawPath.ifEmpty { "/" } &&
            first.rawQuery == second.rawQuery && first.rawFragment == second.rawFragment

    private val CHALLENGE_TITLES = listOf(
        "just a moment", "checking your browser", "attention required",
        "verify you are human", "security check", "\u0644\u062d\u0638\u0629", "\u0627\u0644\u062a\u062d\u0642\u0642 \u0645\u0646 \u0627\u0644\u0623\u0645\u0627\u0646"
    )
}
