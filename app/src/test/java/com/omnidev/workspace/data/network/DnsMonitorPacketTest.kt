package com.omnidev.workspace.data.network

import org.junit.Assert.*
import org.junit.Test

class DnsMonitorPacketTest {
    private fun query(): ByteArray {
        val question = byteArrayOf(3, 119, 119, 119, 7, 101, 120, 97, 109, 112, 108, 101, 3, 99, 111, 109, 0, 0, 1, 0, 1)
        val dns = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0) + question
        return ByteArray(28 + dns.size).apply {
            this[0] = 0x45
            this[2] = (size ushr 8).toByte()
            this[3] = size.toByte()
            this[8] = 64
            this[9] = 17
            byteArrayOf(192.toByte(), 0, 2, 54, 192.toByte(), 0, 2, 53).copyInto(this, 12)
            this[20] = 0xc0.toByte()
            this[21] = 1
            this[23] = 53
            this[25] = (dns.size + 8).toByte()
            dns.copyInto(this, 28)
        }
    }

    @Test fun responseHasReversedEndpointsAndValidChecksum() {
        val packet = query()
        val parsed = requireNotNull(DnsMonitorPacket.parse(packet, packet.size))
        assertEquals("www.example.com", parsed.domain)
        assertEquals(49153, parsed.sourcePort)
        val answer = DnsMonitorPacket.refused(parsed)
        val reply = DnsMonitorPacket.reply(parsed, answer)
        assertArrayEquals(packet.copyOfRange(16, 20), reply.copyOfRange(12, 16))
        assertArrayEquals(packet.copyOfRange(12, 16), reply.copyOfRange(16, 20))
        assertEquals(53, reply[21].toInt() and 255)
        assertEquals(49153, ((reply[22].toInt() and 255) shl 8) or (reply[23].toInt() and 255))
        assertEquals(reply.size, ((reply[2].toInt() and 255) shl 8) or (reply[3].toInt() and 255))
        assertEquals(0, DnsMonitorPacket.checksum(reply, 20))
        assertEquals(5, answer[3].toInt() and 15)
        assertTrue(DnsMonitorPacket.validReply(parsed, answer))
        assertFalse(DnsMonitorPacket.validReply(parsed, answer.clone().apply { this[0] = 0 }))
        assertFalse(DnsMonitorPacket.validReply(parsed, answer.clone().apply { this[2] = 0x83.toByte() }))
    }

    @Test fun ordinaryTrafficFragmentsAndInvalidPacketsCannotBeForwarded() {
        val packet = query()
        for (modified in listOf(
            packet.clone().apply { this[9] = 6 },
            packet.clone().apply { this[19] = 99 },
            packet.clone().apply { this[6] = 0x20 },
            packet.clone().apply { this[0] = 0x65 },
            packet.clone().apply { this[25] = 0 },
            packet.clone().apply { this[40] = 0xc0.toByte() }
        )) assertNull(DnsMonitorPacket.parse(modified, modified.size))
        assertNull(DnsMonitorPacket.parse(packet, packet.size - 1))
        assertNull(DnsMonitorPacket.parse(packet, 2))
    }

    @Test fun blockRulesUseDomainBoundaries() {
        assertEquals("example.com", DnsMonitorPacket.normalizeDomain("EXAMPLE.com."))
        assertNull(DnsMonitorPacket.normalizeDomain("8.8.8.8"))
        assertTrue(DnsMonitorPacket.blocked("www.example.com", setOf("example.com")))
        assertFalse(DnsMonitorPacket.blocked("badexample.com", setOf("example.com")))
    }
}
