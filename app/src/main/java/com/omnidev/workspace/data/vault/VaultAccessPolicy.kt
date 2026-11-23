package com.omnidev.workspace.data.vault

import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-local, one-use consent gate for credentials held by OmniVault.
 *
 * Approvals must be issued only by trusted UI code after an explicit user
 * confirmation. Never expose approval issuance through the agent tool catalog,
 * an IPC endpoint, a prompt, or a model-visible response.
 */
internal object VaultAccessPolicy {
    enum class Operation {
        SIGN_IN, READ_ACCOUNT, SUBMIT_FORM, SEND_MESSAGE, UPDATE_ACCOUNT
    }

    data class Scope(
        val referenceId: String,
        val origin: String,
        val operation: Operation,
        val requestId: String
    )

    /**
     * Only exact HTTPS origins are supported. Paths, fragments and query strings
     * are never part of the grant, so the caller must separately validate the
     * page and submitted action before a mutation.
     */
    fun canonicalOrigin(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.rawUserInfo != null || uri.rawAuthority.isNullOrBlank()) return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host.isBlank() || host.endsWith('.') || host.any { it.isWhitespace() }) return null
        if (uri.port != -1 && uri.port !in 1..65535) return null
        val port = if (uri.port == -1 || uri.port == 443) "" else ":" + uri.port
        return "https://" + host + port
    }

    class Grant internal constructor(
        private val scope: Scope,
        private val issuedAtMillis: Long,
        private val expiresAtMillis: Long
    ) {
        private val spent = AtomicBoolean(false)

        fun matches(candidate: Scope, nowMillis: Long): Boolean =
            !spent.get() && scope == candidate &&
                nowMillis >= issuedAtMillis && nowMillis < expiresAtMillis

        /** A confirmed action cannot be replayed, even concurrently. */
        fun consume(candidate: Scope, nowMillis: Long): Boolean =
            matches(candidate, nowMillis) && spent.compareAndSet(false, true)

        fun revoke() { spent.set(true) }

        override fun toString(): String = "VaultConsentGrant(redacted)"
    }

    /**
     * Caller contract: invoke only from the native, user-confirmed approval
     * callback; do not turn this into an agent-callable operation.
     *
     * This intentionally has no persistent grant store: after process death,
     * the user must confirm again rather than replay a sensitive action.
     */
    fun issueAfterUserConfirmation(
        scope: Scope,
        issuedAtMillis: Long,
        validForMillis: Long = 60_000L
    ): Grant {
        require(scope.referenceId.matches(REFERENCE_ID))
        require(scope.origin == canonicalOrigin(scope.origin))
        require(scope.requestId.length in 8..128 && scope.requestId.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
        })
        require(validForMillis in 1L..300_000L)
        require(issuedAtMillis >= 0L && issuedAtMillis <= Long.MAX_VALUE - validForMillis)
        return Grant(scope, issuedAtMillis, issuedAtMillis + validForMillis)
    }

    private val REFERENCE_ID = Regex(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    )
}
