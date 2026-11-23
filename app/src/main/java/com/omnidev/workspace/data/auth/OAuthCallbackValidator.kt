package com.omnidev.workspace.data.auth

import java.net.URI
import java.net.URLDecoder

/**
 * Strict, side-effect-free callback validation for a future PKCE OAuth integration.
 * No browser launch, token exchange, storage or legacy OAuth re-enablement occurs here.
 */
internal object OAuthCallbackValidator {
    internal data class VerifiedCode(val code: String, val verifier: String) {
        override fun toString(): String = "VerifiedCode(redacted)"
    }

    fun verify(
        callback: String,
        expectedRedirect: String,
        transaction: OAuthPkceTransaction,
        nowMillis: Long
    ): VerifiedCode? {
        val expected = runCatching { URI(expectedRedirect) }.getOrNull() ?: return null
        val actual = runCatching { URI(callback) }.getOrNull() ?: return null
        // Exact redirect binding. No fragment, user-info, alternate port, path or authority.
        if (expected.fragment != null || expected.rawQuery != null ||
            expected.userInfo != null || expected.scheme.isNullOrBlank() ||
            expected.rawAuthority.isNullOrBlank() || expected.rawPath.isNullOrBlank()
        ) return null
        if (actual.scheme != expected.scheme || actual.rawAuthority != expected.rawAuthority ||
            actual.rawPath != expected.rawPath || actual.rawFragment != null ||
            actual.rawUserInfo != null || actual.isOpaque
        ) return null
        val query = actual.rawQuery ?: return null
        if (query.isEmpty() || query.length > 4096) return null
        val fields = mutableMapOf<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty() || pair.count { it == '=' } != 1) return null
            val parts = pair.split('=', limit = 2)
            val key = decode(parts[0]) ?: return null
            val value = decode(parts[1]) ?: return null
            if (key !in setOf("code", "state") || value.isEmpty() || fields.put(key, value) != null) return null
        }
        if (fields.size != 2) return null
        val code = fields["code"] ?: return null
        val state = fields["state"] ?: return null
        if (code.length > 2048 || code.any { it.isWhitespace() || it.isISOControl() }) return null
        val verifier = transaction.consumeVerifier(state, nowMillis) ?: return null
        return VerifiedCode(code, verifier)
    }

    private fun decode(value: String): String? {
        // Reject malformed percent escapes and non-ASCII encodings of parameter names.
        if (value.any { it == '+' }) return null
        var i = 0
        while (i < value.length) {
            if (value[i] == '%') {
                if (i + 2 >= value.length ||
                    !value[i + 1].isHexDigit() || !value[i + 2].isHexDigit()) return null
                i += 3
            } else i++
        }
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
