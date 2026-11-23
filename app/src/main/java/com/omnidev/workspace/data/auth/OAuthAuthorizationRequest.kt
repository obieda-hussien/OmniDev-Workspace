package com.omnidev.workspace.data.auth

import java.net.URI
import java.net.URLEncoder

/**
 * Pure authorization URL construction for a future verified OAuth flow.
 * Does not launch a browser, exchange tokens, or persist credentials.
 * The endpoint is pinned; callers cannot redirect authorization data to arbitrary hosts.
 */
internal object OAuthAuthorizationRequest {
    private const val ENDPOINT = "https://github.com/login/oauth/authorize"
    private val CLIENT_ID = Regex("[A-Za-z0-9_]{1,128}")
    private val SCOPE = Regex("[A-Za-z0-9:_-]{1,64}")

    fun github(clientId: String, redirectUri: String, transaction: OAuthPkceTransaction,
               scopes: List<String> = emptyList()): String {
        require(CLIENT_ID.matches(clientId)) { "Invalid OAuth client identifier" }
        require(scopes.size <= 16 && scopes.distinct().size == scopes.size && scopes.all(SCOPE::matches)) {
            "Invalid OAuth scopes"
        }
        val redirect = URI(redirectUri)
        require(redirect.toString() == OAuthManager.CALLBACK_URI) { "Unregistered redirect" }
        val params = linkedMapOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "state" to transaction.state,
            "code_challenge" to transaction.codeChallenge,
            "code_challenge_method" to "S256"
        )
        if (scopes.isNotEmpty()) params["scope"] = scopes.joinToString(" ")
        return ENDPOINT + "?" + params.entries.joinToString("&") {
            encode(it.key) + "=" + encode(it.value)
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
