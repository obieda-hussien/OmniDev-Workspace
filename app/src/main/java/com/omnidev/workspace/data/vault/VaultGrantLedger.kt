package com.omnidev.workspace.data.vault

import java.net.URI
import java.util.Locale
import java.util.UUID

/** Agent-visible references contain no credential or authorization token. */
data class VaultHandle(val id: String, val origin: String, val label: String, val createdAt: Long)

enum class VaultOperation { FILL_LOGIN, OAUTH_REQUEST, READ_ACCOUNT, SEND_MESSAGE, SUBMIT_FORM }

/** Only exact HTTPS origins can be authorized; never infer trust from a parent domain. */
object VaultOrigin {
    fun normalize(raw: String): String? = runCatching {
        val uri = URI(raw.trim())
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            !uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return@runCatching null
        val host = uri.host?.lowercase(Locale.ROOT)?.removeSuffix(".")?.takeIf { it.isNotBlank() }
            ?: return@runCatching null
        if (host.contains("*") || host.contains("..") || uri.port > 65535 || uri.port == 0) return@runCatching null
        "https://$host" + (if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}")
    }.getOrNull()
}

/**
 * Memory-only, short-lived grants. Losing the process revokes them. These methods
 * are not registered as tools: only a trusted UI approval flow may mint a grant.
 * Consuming a grant is atomic; a model cannot approve itself by crafting arguments.
 */
internal class VaultGrantLedger(
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Grant(
        val handleId: String,
        val origin: String,
        val operation: VaultOperation,
        val expiresAt: Long
    )
    private val grants = mutableMapOf<String, Grant>()

    @Synchronized
    fun approve(handle: VaultHandle, origin: String, operation: VaultOperation, ttlMs: Long = 60_000L): String {
        val scope = VaultOrigin.normalize(origin) ?: throw IllegalArgumentException("Exact HTTPS origin required")
        require(scope == handle.origin) { "Origin does not match credential" }
        require(ttlMs in 1..300_000L) { "Grant duration must be 1 to 300000 ms" }
        val expiresAt = Math.addExact(clock(), ttlMs)
        val ticket = UUID.randomUUID().toString()
        grants[ticket] = Grant(handle.id, scope, operation, expiresAt)
        return ticket
    }

    @Synchronized
    fun consume(ticket: String, handle: VaultHandle, origin: String, operation: VaultOperation): Boolean {
        val grant = grants.remove(ticket) ?: return false
        return grant.handleId == handle.id && grant.origin == VaultOrigin.normalize(origin) &&
            grant.origin == handle.origin && grant.operation == operation &&
            clock() < grant.expiresAt
    }

    @Synchronized
    fun revoke(ticket: String) { grants.remove(ticket) }

    @Synchronized
    fun revokeForHandle(handleId: String) { grants.entries.removeAll { it.value.handleId == handleId } }

    @Synchronized
    fun revokeAll() { grants.clear() }
}
