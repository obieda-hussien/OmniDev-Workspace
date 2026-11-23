package com.omnidev.workspace.data.auth

import java.net.URI
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

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
        if (code.length > 2048 || code.any { it.isWhitespace() || Character.isISOControl(it) }) return null
        val verifier = transaction.consumeVerifier(state, nowMillis) ?: return null
        return VerifiedCode(code, verifier)
    }

    private fun decode(value: String): String? {
        // URLDecoder silently replaces malformed UTF-8. OAuth callbacks must fail closed
        // instead of accepting multiple byte representations of the same parameter.
        val bytes = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val char = value[i]
            when {
                char == '%' -> {
                    if (i + 2 >= value.length || !value[i + 1].isHexDigit() ||
                        !value[i + 2].isHexDigit()) return null
                    val octet = (Character.digit(value[i + 1], 16) shl 4) or
                        Character.digit(value[i + 2], 16)
                    bytes.write(octet)
                    i += 3
                }
                char == '+' || char.toInt() > 0x7f -> return null
                else -> {
                    bytes.write(char.toInt())
                    i++
                }
            }
        }
        return runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        }.getOrNull()
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
