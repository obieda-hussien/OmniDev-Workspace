package com.omnidev.workspace.data.tools

import java.net.InetAddress

/**
 * Pure validation rules for the desktop_link tool. Kept free of Android/transport types so every
 * rule is unit-testable and the agent can never widen them through tool arguments.
 */
internal object DesktopLinkPolicy {
    const val TOOL_NAME = "desktop_link"
    const val MAX_REQUEST_BYTES = 64 * 1024
    /** Lower than the transport library's 4 MiB default to bound received response allocation. */
    const val MAX_ENCRYPTED_FRAME_BYTES = 128 * 1024
    const val MAX_CONFIRMATION_PAYLOAD_CHARS = 4_000
    /** Keep the confirmation body's combined preview below its 1,000-line UI truncation guard. */
    const val MAX_CONFIRMATION_PAYLOAD_LINE_BREAKS = 990
    const val MAX_RESPONSE_CHARS = 16_000
    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val MAX_TIMEOUT_MS = 120_000L
    const val MAX_CAPABILITIES = 16

    /**
     * The transport requires explicit ACLs in both directions. Workspace runs no server, so the
     * inbound side is an inert capability nothing handles: the desktop cannot call into the phone.
     */
    const val INBOUND_PLACEHOLDER = "desktop.notify"

    private val CAPABILITY = Regex("^[a-z][a-z0-9_.-]{0,63}$")
    private val FINGERPRINT = Regex("^[0-9a-f]{64}$")
    private val PEER_ID = Regex("^[A-Za-z0-9_.:@-]{1,64}$")
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val IPV6_LITERAL = Regex("^[0-9a-f:]{2,45}(?:%[a-z0-9_.-]{1,32})?$")
    private val HOSTNAME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$")
    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home.arpa")

    fun validCapability(value: String): Boolean = CAPABILITY.matches(value)

    fun validPeerId(value: String): Boolean = PEER_ID.matches(value)

    /** Accepts `AB:CD:..`, spaced or plain hex; returns canonical lowercase 64-hex or null. */
    fun normalizeFingerprint(value: String?): String? {
        val cleaned = value.orEmpty().trim().replace(":", "").replace(" ", "").lowercase()
        return cleaned.takeIf { FINGERPRINT.matches(it) }
    }

    /** Comma separated explicit capability names. Wildcards and empty lists are rejected. */
    fun parseCapabilities(csv: String?): Set<String>? {
        val items = csv.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty() || items.size > MAX_CAPABILITIES) return null
        if (!items.all(::validCapability)) return null
        return items.toSet()
    }

    fun parsePort(value: String?, default: Int): Int? {
        if (value.isNullOrBlank()) return default
        return value.trim().toIntOrNull()?.takeIf { it in 1024..65535 }
    }

    fun clampTimeout(value: String?): Long {
        val requested = value?.trim()?.toLongOrNull() ?: DEFAULT_TIMEOUT_MS
        return requested.coerceIn(1_000L, MAX_TIMEOUT_MS)
    }

    fun withinRequestLimit(payload: String): Boolean =
        payload.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES

    fun payloadCanBeFullyReviewed(payload: String): Boolean =
        payload.length <= MAX_CONFIRMATION_PAYLOAD_CHARS &&
            payload.count { it == '\n' || it == '\r' } <= MAX_CONFIRMATION_PAYLOAD_LINE_BREAKS

    fun truncateResponse(text: String): Pair<String, Boolean> =
        if (text.length <= MAX_RESPONSE_CHARS) text to false
        else text.take(MAX_RESPONSE_CHARS) to true

    /**
     * Syntax-only host policy: numeric private endpoints and reserved local host suffixes are
     * accepted here. Call resolvePrivateHost() before every connection so local names are resolved
     * once, all answers are checked, and the socket receives a verified numeric address. The
     * transport must still authenticate the pinned peer key.
     */
    fun isPrivateHost(rawHost: String?): Boolean {
        val host = rawHost.orEmpty().trim().lowercase().removePrefix("[").removeSuffix("]")
        if (host.isEmpty() || host.length > 253) return false
        if (host == "localhost") return true

        IPV4.matchEntire(host)?.let { match ->
            val o = match.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
            if (o.any { it !in 0..255 }) return false
            return when {
                o[0] == 10 -> true
                o[0] == 127 -> true
                o[0] == 192 && o[1] == 168 -> true
                o[0] == 172 && o[1] in 16..31 -> true
                o[0] == 169 && o[1] == 254 -> true
                o[0] == 100 && o[1] in 64..127 -> true
                else -> false
            }
        }

        if (host.contains(':')) {
            if (!IPV6_LITERAL.matches(host)) return false
            val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            val first = address.address.firstOrNull()?.toInt()?.and(0xFF) ?: return false
            return address.isLoopbackAddress || address.isLinkLocalAddress || (first and 0xFE) == 0xFC
        }

        if (!HOSTNAME.matches(host)) return false
        return LOCAL_SUFFIXES.any { host.endsWith(it) }
    }

    /**
     * Resolves an allowed host once, rejects mixed/private+public DNS answers, and returns the
     * private numeric address the caller must actually connect to. This avoids validating a
     * .local/.lan name and then handing the unresolved hostname to a socket that may resolve it
     * differently. The injectable resolver keeps DNS policy deterministic in unit tests.
     */
    fun resolvePrivateHost(
        rawHost: String?,
        resolver: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) }
    ): String? {
        val host = rawHost.orEmpty().trim().lowercase().removePrefix("[").removeSuffix("]")
        if (!isPrivateHost(host)) return null

        // Preserve explicit numeric endpoints (including scoped IPv6 literals); normalize localhost.
        if (host == "localhost") return "127.0.0.1"
        IPV4.matchEntire(host)?.let { match ->
            // Normalize leading zeros so the socket sees the same dotted-decimal address as policy.
            return match.groupValues.drop(1).joinToString(".") { it.toInt().toString() }
        }
        if (IPV6_LITERAL.matches(host)) return host

        val addresses = runCatching { resolver(host) }.getOrNull() ?: return null
        if (addresses.isEmpty() || addresses.any { !isPrivateResolvedAddress(it) }) return null
        // Prefer IPv4 for broad Android/JVM socket compatibility; otherwise use a verified IPv6.
        return addresses.firstOrNull { it.address.size == 4 }?.hostAddress
            ?: addresses.firstOrNull()?.hostAddress
    }

    private fun isPrivateResolvedAddress(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size == 4) {
            val a = bytes[0].toInt() and 0xFF
            val b = bytes[1].toInt() and 0xFF
            return when {
                a == 10 -> true
                a == 192 && b == 168 -> true
                a == 172 && b in 16..31 -> true
                a == 169 && b == 254 -> true
                a == 100 && b in 64..127 -> true
                else -> false // Do not allow a hostname to redirect to loopback or a public IPv4.
            }
        }
        if (bytes.size != 16) return false
        val first = bytes[0].toInt() and 0xFF
        return address.isLinkLocalAddress || (first and 0xFE) == 0xFC
    }
}
