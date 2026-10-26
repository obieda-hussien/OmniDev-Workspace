package com.omnidev.workspace.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class DesktopLinkPolicyTest {
    @Test fun `private hosts are accepted`() {
        for (host in listOf("192.168.1.20", "10.0.0.5", "172.16.4.1", "172.31.255.254", "127.0.0.1",
            "169.254.10.2", "100.64.0.1", "100.127.255.1", "localhost", "my-pc.local", "desk.lan",
            "box.home.arpa", "::1", "[::1]", "fe80::1", "fd12:3456::1")) {
            assertTrue("$host should be private", DesktopLinkPolicy.isPrivateHost(host))
        }
    }

    @Test fun `public or malformed hosts are rejected`() {
        for (host in listOf("8.8.8.8", "172.32.0.1", "172.15.0.1", "100.63.0.1", "100.128.0.1",
            "192.169.1.1", "example.com", "evil.local.example.com", "2001:4860:4860::8888",
            "999.1.1.1", "10.0.0", "", "  ", "host name.local", "a@b.local", "http://10.0.0.1")) {
            assertFalse("$host should be rejected", DesktopLinkPolicy.isPrivateHost(host))
        }
    }

    @Test fun `hostname resolution rejects public or mixed address answers`() {
        val privateAddress = InetAddress.getByName("192.168.1.20")
        val publicAddress = InetAddress.getByName("8.8.8.8")
        assertEquals(
            "192.168.1.20",
            DesktopLinkPolicy.resolvePrivateHost("desk.local") { arrayOf(privateAddress) }
        )
        assertNull(DesktopLinkPolicy.resolvePrivateHost("desk.local") { arrayOf(publicAddress) })
        assertNull(DesktopLinkPolicy.resolvePrivateHost("desk.local") { arrayOf(privateAddress, publicAddress) })
    }

    @Test fun `numeric host validation is deterministic and refuses public addresses`() {
        assertEquals("10.0.0.5", DesktopLinkPolicy.resolvePrivateHost("10.0.0.5"))
        assertEquals("10.0.0.5", DesktopLinkPolicy.resolvePrivateHost("010.000.000.005"))
        assertEquals("127.0.0.1", DesktopLinkPolicy.resolvePrivateHost("localhost"))
        assertNull(DesktopLinkPolicy.resolvePrivateHost("8.8.8.8"))
        assertNull(DesktopLinkPolicy.resolvePrivateHost("example.com"))
    }

    @Test fun `fingerprints are normalized to lowercase hex`() {
        val hex = "AB".repeat(32)
        assertEquals("ab".repeat(32), DesktopLinkPolicy.normalizeFingerprint(hex))
        assertEquals("ab".repeat(32), DesktopLinkPolicy.normalizeFingerprint(hex.chunked(2).joinToString(":")))
        assertNull(DesktopLinkPolicy.normalizeFingerprint("abc"))
        assertNull(DesktopLinkPolicy.normalizeFingerprint("zz".repeat(32)))
        assertNull(DesktopLinkPolicy.normalizeFingerprint(null))
    }

    @Test fun `capabilities must be explicit names`() {
        assertEquals(setOf("desktop.read_file", "desktop.ping"), DesktopLinkPolicy.parseCapabilities("desktop.read_file, desktop.ping"))
        assertNull(DesktopLinkPolicy.parseCapabilities(""))
        assertNull(DesktopLinkPolicy.parseCapabilities(null))
        assertNull(DesktopLinkPolicy.parseCapabilities("desktop.*"))
        assertNull(DesktopLinkPolicy.parseCapabilities("*"))
        assertNull(DesktopLinkPolicy.parseCapabilities("Desktop.Read"))
        assertNull(DesktopLinkPolicy.parseCapabilities((1..17).joinToString(",") { "desktop.cap$it" }))
    }

    @Test fun `ports and timeouts are bounded`() {
        assertEquals(49371, DesktopLinkPolicy.parsePort(null, 49371))
        assertEquals(8080, DesktopLinkPolicy.parsePort("8080", 49371))
        assertNull(DesktopLinkPolicy.parsePort("80", 49371))
        assertNull(DesktopLinkPolicy.parsePort("70000", 49371))
        assertNull(DesktopLinkPolicy.parsePort("abc", 49371))
        assertEquals(30_000L, DesktopLinkPolicy.clampTimeout(null))
        assertEquals(1_000L, DesktopLinkPolicy.clampTimeout("5"))
        assertEquals(120_000L, DesktopLinkPolicy.clampTimeout("999999999"))
    }

    @Test fun `request and response sizes are capped`() {
        assertTrue(DesktopLinkPolicy.withinRequestLimit("x".repeat(DesktopLinkPolicy.MAX_REQUEST_BYTES)))
        assertFalse(DesktopLinkPolicy.withinRequestLimit("x".repeat(DesktopLinkPolicy.MAX_REQUEST_BYTES + 1)))
        val (short, shortTruncated) = DesktopLinkPolicy.truncateResponse("ok")
        assertEquals("ok", short); assertFalse(shortTruncated)
        val (long, longTruncated) = DesktopLinkPolicy.truncateResponse("y".repeat(DesktopLinkPolicy.MAX_RESPONSE_CHARS + 50))
        assertEquals(DesktopLinkPolicy.MAX_RESPONSE_CHARS, long.length); assertTrue(longTruncated)
    }

    @Test fun `desktop link is hidden from lite and norm but visible to pro`() {
        assertFalse("desktop_link" in TierToolGate.LITE_TOOLS)
        assertTrue("desktop_link" in TierToolGate.PRO_ONLY_TOOLS)
    }

    @Test fun `tool definition exposes the five actions and no pairing shortcut`() {
        val definition = DesktopLinkTool.getToolDefinitions().single()
        assertEquals("desktop_link", definition.name)
        for (action in listOf("status", "trust_peer", "ping", "request", "remove_peer")) {
            assertTrue("missing $action", definition.description.contains(action))
        }
        assertTrue(definition.parameters.none { it.name == "approve" || it.name == "auto_trust" })
    }
}
