package com.omnidev.workspace.data.auth

import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class OAuthAuthorizationRequestTest {
    @Test fun authorizationRequestPinsEndpointAndBindsTransaction() {
        val transaction = OAuthPkceTransaction.create(1000L)
        val url = OAuthAuthorizationRequest.github("client_123", OAuthManager.CALLBACK_URI,
            transaction, listOf("read:user", "repo"))
        val uri = URI(url)
        assertEquals("https", uri.scheme)
        assertEquals("github.com", uri.host)
        assertEquals("/login/oauth/authorize", uri.path)
        val fields = uri.rawQuery.split("&").associate {
            val parts = it.split("=", limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
        }
        assertEquals("client_123", fields["client_id"])
        assertEquals(OAuthManager.CALLBACK_URI, fields["redirect_uri"])
        assertEquals(transaction.state, fields["state"])
        assertEquals(transaction.codeChallenge, fields["code_challenge"])
        assertEquals("S256", fields["code_challenge_method"])
        assertEquals("code", fields["response_type"])
        assertEquals("read:user repo", fields["scope"])
        assertFalse(url.contains("client_secret"))
        assertFalse(url.contains("code_verifier"))
    }

    @Test fun malformedClientsRedirectsAndScopesFailClosed() {
        val transaction = OAuthPkceTransaction.create(1000L)
        for (client in listOf("", "client&evil=1", "a b", "a/b")) {
            assertThrows(IllegalArgumentException::class.java) {
                OAuthAuthorizationRequest.github(client, OAuthManager.CALLBACK_URI, transaction)
            }
        }
        for (redirect in listOf("https://evil.example/callback", "omnidev://oauth/callback?x=1",
                "omnidev://oauth/callback#fragment", "omnidev://oauth/other")) {
            assertThrows(IllegalArgumentException::class.java) {
                OAuthAuthorizationRequest.github("client_123", redirect, transaction)
            }
        }
        for (scopes in listOf(listOf("repo", "repo"), listOf("repo&evil=1"), listOf(""))) {
            assertThrows(IllegalArgumentException::class.java) {
                OAuthAuthorizationRequest.github("client_123", OAuthManager.CALLBACK_URI, transaction, scopes)
            }
        }
    }

    @Test fun eachTransactionUsesDifferentStateAndChallenge() {
        val a = OAuthPkceTransaction.create(1000L)
        val b = OAuthPkceTransaction.create(1000L)
        assertNotEquals(a.state, b.state)
        assertNotEquals(a.codeChallenge, b.codeChallenge)
    }
}
