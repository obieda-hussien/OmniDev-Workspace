package com.omnidev.workspace.data.tools

import java.net.URI
import java.util.Locale

/**
 * Agent-only Computer Use policy. The Browser Viewer UI does not pass through this gate.
 * No model-supplied argument can grant its own exception. Sensitive steps require manual
 * user takeover; no automated bypass of CAPTCHA, login, OTP, purchase or account consent.
 */
internal object AgentBrowserPolicy {
    data class Decision(val classification: String, val reason: String, val handoff: Boolean)

    private val rawSessionActions = setOf(
        "execute_js", "inject_js_persistent", "get_cookies", "set_cookie",
        "get_local_storage", "set_local_storage", "get_html", "get_network_log"
    )
    private val sensitiveInputs = Regex(
        "(?i)pass(word|key)?|otp|one.?time|two.?factor|2fa|mfa|verification.?code|" +
            "credit.?card|card.?number|cvv|cvc|secret|api.?key|access.?token|private.?key"
    )
    private val sensitivePage = Regex(
        "(?i)login|log.?in|sign.?in|sign.?up|register|auth|oauth|verify|identity|" +
            "checkout|payment|billing|purchase|password|reset|security|consent|captcha|passkey"
    )
    private val exposedReads = setOf(
        "get_dom", "find_element", "find_all", "extract_text", "extract_table",
        "screenshot", "extract_links", "extract_json"
    )
    private val interactive = setOf(
        "click", "type_text", "fill_form", "submit_form", "select_option", "clear_input"
    )

    fun evaluate(action: String, args: Map<String, String>, currentUrl: String, pageTitle: String): Decision? {
        val kind = action.trim().lowercase(Locale.ROOT)
        if (kind in rawSessionActions)
            return Decision("BROWSER_SESSION_SECRET_DENIED",
                "Raw scripts, HTML and session storage are unavailable to the agent. Open Browser Viewer to inspect the page manually.", false)
        if (kind == "navigate") {
            val url = runCatching { URI(args["url"].orEmpty()) }.getOrNull()
            val rawQuery = url?.rawQuery.orEmpty()
            if (url == null || url.userInfo != null || !url.scheme.equals("https", true) &&
                !url.scheme.equals("http", true) || url.host.isNullOrBlank() ||
                sensitiveInputs.containsMatchIn(rawQuery)) {
                return Decision("BROWSER_SENSITIVE_URL_DENIED",
                    "Navigation URL is invalid or contains potential credentials. Use a clean destination URL.", false)
            }
        }
        val url = runCatching { URI(currentUrl) }.getOrNull()
        val pagePath = url?.path.orEmpty()
        val hostname = url?.host.orEmpty()
        val protectedPage = sensitivePage.containsMatchIn(pagePath) ||
            sensitivePage.containsMatchIn(pageTitle) ||
            hostname.startsWith("accounts.", ignoreCase = true) ||
            hostname.startsWith("login.", ignoreCase = true)
        val sensitiveSelector = sensitiveInputs.containsMatchIn(args["selector"].orEmpty()) ||
            (kind == "fill_form" && sensitiveInputs.containsMatchIn(args["fields"].orEmpty()))
        if (kind == "submit_form" || kind in interactive && (protectedPage || sensitiveSelector) ||
            kind in exposedReads && protectedPage) {
            return Decision("USER_ACTION_REQUIRED",
                "Sensitive browser action requires manual control in Browser Viewer. No agent action executed.", true)
        }
        return null
    }
}
