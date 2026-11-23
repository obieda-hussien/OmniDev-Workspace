package com.omnidev.workspace.data.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ephemeral OAuth transaction primitive. Not wired into the disabled legacy flow.
 * The caller must bind a verified redirect URI, client ID and authorization endpoint
 * before enabling any browser or token exchange. Never log the verifier or state.
 */
internal class OAuthPkceTransaction private constructor(
    val state: String,
    val codeChallenge: String,
    private val verifier: String,
    private val issuedAtMillis: Long,
    private val expiresAtMillis: Long
) {
    private val consumed = AtomicBoolean(false)

    /** Returns the verifier only once, for an exact state match inside the validity window. */
    fun consumeVerifier(candidateState: String, nowMillis: Long): String? {
        if (nowMillis < issuedAtMillis || nowMillis >= expiresAtMillis) return null
        val a = state.toByteArray(Charsets.US_ASCII)
        val b = candidateState.toByteArray(Charsets.US_ASCII)
        if (!MessageDigest.isEqual(a, b)) return null
        return if (consumed.compareAndSet(false, true)) verifier else null
    }

    fun revoke() { consumed.set(true) }

    override fun toString(): String = "OAuthPkceTransaction(redacted)"

    companion object {
        fun create(nowMillis: Long, lifetimeMillis: Long = 300_000L): OAuthPkceTransaction {
            require(nowMillis >= 0L && lifetimeMillis in 1L..600_000L)
            require(nowMillis <= Long.MAX_VALUE - lifetimeMillis)
            val random = SecureRandom()
            val stateBytes = ByteArray(32).also(random::nextBytes)
            val verifierBytes = ByteArray(32).also(random::nextBytes)
            val state = base64Url(stateBytes)
            val verifier = base64Url(verifierBytes)
            val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
            return OAuthPkceTransaction(state, challenge, verifier, nowMillis, nowMillis + lifetimeMillis)
        }

        // RFC 4648 URL-safe Base64 without padding; avoids API-26 java.util.Base64.
        private fun base64Url(bytes: ByteArray): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            val out = StringBuilder((bytes.size * 4 + 2) / 3)
            var i = 0
            while (i < bytes.size) {
                val a = bytes[i++].toInt() and 255
                val b = if (i < bytes.size) bytes[i++].toInt() and 255 else -1
                val c = if (i < bytes.size) bytes[i++].toInt() and 255 else -1
                out.append(alphabet[a ushr 2])
                out.append(alphabet[((a and 3) shl 4) or (if (b >= 0) b ushr 4 else 0)])
                if (b >= 0) out.append(alphabet[((b and 15) shl 2) or (if (c >= 0) c ushr 6 else 0)])
                if (c >= 0) out.append(alphabet[c and 63])
            }
            return out.toString()
        }
    }
}
