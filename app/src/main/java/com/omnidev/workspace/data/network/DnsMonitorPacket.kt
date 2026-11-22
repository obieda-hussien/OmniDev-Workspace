package com.omnidev.workspace.data.network

import java.net.IDN
import java.util.Locale

/** Only IPv4 UDP DNS to the dedicated virtual resolver is eligible for interception. */
internal object DnsMonitorPacket {
    const val RESOLVER = "192.0.2.53"
    const val CLIENT = "192.0.2.54"
    const val MTU = 1500
    const val MAX_DNS = MTU - 28
    private val resolverBytes = byteArrayOf(192.toByte(), 0, 2, 53)

    data class Query(val source: ByteArray, val sourcePort: Int, val dns: ByteArray, val domain: String, val questionEnd: Int)

    fun normalizeDomain(raw: String): String? = runCatching {
        IDN.toASCII(raw.trim().trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            .takeIf { it.isNotBlank() && it.length <= 253 && it.contains('.') && it.any(Char::isLetter) }
    }.getOrNull()

    fun blocked(domain: String, rules: Set<String>): Boolean =
        rules.any { domain == it || domain.endsWith(".$it") }

    fun parse(packet: ByteArray, length: Int): Query? {
        if (length !in 28..packet.size || u8(packet[0]) ushr 4 != 4) return null
        val header = (u8(packet[0]) and 15) * 4
        if (header < 20 || header + 20 > length || u16(packet, 2) != length ||
            u16(packet, 6) and 0x3fff != 0 || u8(packet[9]) != 17 ||
            !packet.copyOfRange(16, 20).contentEquals(resolverBytes)) return null
        if (u16(packet, header + 2) != 53 || u16(packet, header + 4) != length - header) return null
        val port = u16(packet, header)
        if (port == 0) return null
        val dns = packet.copyOfRange(header + 8, length)
        if (dns.size !in 17..MAX_DNS || u16(dns, 4) != 1 || u8(dns[2]) and 0xf8 != 0) return null
        var pos = 12
        val labels = mutableListOf<String>()
        while (pos < dns.size) {
            val size = u8(dns[pos++])
            if (size == 0) {
                if (pos + 4 > dns.size || labels.isEmpty()) return null
                return Query(packet.copyOfRange(12, 16), port, dns,
                    labels.joinToString(".").lowercase(Locale.ROOT), pos + 4)
            }
            if (size > 63 || pos + size > dns.size || pos + size > 267) return null
            val label = dns.copyOfRange(pos, pos + size)
            if (label.any { u8(it) !in 33..126 || it == '.'.code.toByte() }) return null
            labels += String(label, Charsets.US_ASCII)
            pos += size
        }
        return null
    }

    /** REFUSED avoids inventing NXDOMAIN for existing DNSSEC-signed names. */
    fun refused(query: Query): ByteArray = query.dns.copyOf(query.questionEnd).apply {
        this[2] = (0x80 or (u8(this[2]) and 1)).toByte()
        this[3] = 0x85.toByte()
        for (i in 6..11) this[i] = 0
    }

    fun validReply(query: Query, response: ByteArray): Boolean =
        response.size in 12..MAX_DNS && u16(response, 0) == u16(query.dns, 0) &&
            u8(response[2]) and 0x80 != 0 && u8(response[2]) and 0x02 == 0

    /** Build an inbound packet with swapped endpoints, correct length and IPv4 checksum. */
    fun reply(query: Query, dns: ByteArray): ByteArray {
        require(dns.size in 12..MAX_DNS)
        val packet = ByteArray(28 + dns.size)
        packet[0] = 0x45
        put16(packet, 2, packet.size)
        packet[8] = 64
        packet[9] = 17
        resolverBytes.copyInto(packet, 12)
        query.source.copyInto(packet, 16)
        put16(packet, 10, checksum(packet, 20))
        put16(packet, 20, 53)
        put16(packet, 22, query.sourcePort)
        put16(packet, 24, dns.size + 8)
        // UDP checksum zero is permitted for IPv4. IPv6 is never intercepted.
        dns.copyInto(packet, 28)
        return packet
    }

    internal fun checksum(bytes: ByteArray, length: Int): Int {
        var sum = 0L
        for (i in 0 until length step 2) sum += (u8(bytes[i]) shl 8) or (if (i + 1 < length) u8(bytes[i + 1]) else 0)
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }
    private fun u8(value: Byte) = value.toInt() and 255
    private fun u16(bytes: ByteArray, offset: Int) = (u8(bytes[offset]) shl 8) or u8(bytes[offset + 1])
    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }
}
