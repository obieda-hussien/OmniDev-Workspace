package com.omnidev.workspace.data.auth

import org.junit.Assert.*
import org.junit.Test

class OAuthCallbackValidatorTest {
    private val redirect = "omnidev://oauth/callback"

    @Test fun validCallbackConsumesVerifierExactlyOnce() {
        val tx = OAuthPkceTransaction.create(1000L)
        val callback = "$redirect?code=abc123&state=${tx.state}"
        val verified = OAuthCallbackValidator.verify(callback, redirect, tx, 1001L)
        assertNotNull(verified)
        assertEquals("abc123", verified!!.code)
        assertEquals("VerifiedCode(redacted)", verified.toString())
        assertNull(OAuthCallbackValidator.verify(callback, redirect, tx, 1002L))
    }

    @Test fun wrongStateDoesNotConsumeValidAuthorization() {
        val tx = OAuthPkceTransaction.create(1000L)
        assertNull(OAuthCallbackValidator.verify("$redirect?code=abc&state=wrong", redirect, tx, 1001L))
        assertNotNull(OAuthCallbackValidator.verify("$redirect?state=${tx.state}&code=abc", redirect, tx, 1001L))
    }

    @Test fun malformedAndAmbiguousCallbacksFailWithoutSpendingGrant() {
        val tx = OAuthPkceTransaction.create(1000L)
        val invalid = listOf(
            "omnidev://evil/callback?code=a&state=${tx.state}",
            "omnidev://oauth/other?code=a&state=${tx.state}",
            "omnidev://oauth:80/callback?code=a&state=${tx.state}",
            "$redirect?code=a&state=${tx.state}#fragment",
            "$redirect?code=a&code=b&state=${tx.state}",
            "$redirect?code=a&state=${tx.state}&error=access_denied",
            "$redirect?code=a&state=${tx.state}&",
            "$redirect?code=a&state=${tx.state}&state=bad",
            "$redirect?code=%GG&state=${tx.state}",
            "$redirect?code=%FF&state=${tx.state}",
            "$redirect?code=%C0%AF&state=${tx.state}",
            "$redirect?code=%E2%82&state=${tx.state}",
            "$redirect?code=%ED%A0%80&state=${tx.state}",
            "$redirect?code=%00&state=${tx.state}",
            "$redirect?co%64e=a&state=${tx.state}&code=b",
            "$redirect?code=a+b&state=${tx.state}",
            "$redirect?code=&state=${tx.state}",
            "$redirect?code=a&state=",
            "$redirect?code=a",
            "$redirect?code=a%0A&state=${tx.state}"
        )
        invalid.forEach { assertNull(it, OAuthCallbackValidator.verify(it, redirect, tx, 1001L)) }
        assertNotNull(OAuthCallbackValidator.verify("$redirect?code=valid&state=${tx.state}", redirect, tx, 1001L))
    }

    @Test fun expiredAndInvalidRedirectFailClosed() {
        val tx = OAuthPkceTransaction.create(1000L, 1L)
        val callback = "$redirect?code=a&state=${tx.state}"
        assertNull(OAuthCallbackValidator.verify(callback, redirect, tx, 1001L))
        assertNull(OAuthCallbackValidator.verify(callback, "$redirect?code=x", tx, 1000L))
        assertNull(OAuthCallbackValidator.verify(callback, "not a uri", tx, 1000L))
        assertNotNull(OAuthCallbackValidator.verify(callback, redirect, tx, 1000L))
    }
}
