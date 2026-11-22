package com.omnidev.workspace.data.vault

import org.junit.Assert.*
import org.junit.Test

class VaultGrantLedgerTest {
    private val handle = VaultHandle("safe-id", "https://example.com", "account", 10L)

    @Test fun `exact HTTPS origin cannot be relaxed to subdomain or path`() {
        assertEquals("https://example.com", VaultOrigin.normalize("https://EXAMPLE.com:443"))
        for (input in listOf("http://example.com", "https://example.com.evil.test",
            "https://example.com/login", "https://example.com?token=x",
            "https://user@example.com", "javascript:alert(1)", "https://*.example.com")) {
            if (input == "https://example.com.evil.test")
                assertNotEquals(handle.origin, VaultOrigin.normalize(input))
            else assertNull(input, VaultOrigin.normalize(input))
        }
    }

    @Test fun `grant is one use and rejects wrong origin operation or handle`() {
        var now = 100L
        val ledger = VaultGrantLedger { now }
        val ticket = ledger.approve(handle, "https://example.com", VaultOperation.FILL_LOGIN)
        assertFalse(ledger.consume(ticket, handle, "https://evil.example.com", VaultOperation.FILL_LOGIN))
        assertFalse(ledger.consume(ticket, handle, handle.origin, VaultOperation.FILL_LOGIN))
        val next = ledger.approve(handle, handle.origin, VaultOperation.FILL_LOGIN)
        assertFalse(ledger.consume(next, handle, handle.origin, VaultOperation.SEND_MESSAGE))
        val usable = ledger.approve(handle, handle.origin, VaultOperation.FILL_LOGIN)
        assertTrue(ledger.consume(usable, handle, handle.origin, VaultOperation.FILL_LOGIN))
        assertFalse(ledger.consume(usable, handle, handle.origin, VaultOperation.FILL_LOGIN))
    }

    @Test fun `grant expires and revocation takes effect immediately`() {
        var now = 50L
        val ledger = VaultGrantLedger { now }
        val expired = ledger.approve(handle, handle.origin, VaultOperation.OAUTH_REQUEST, ttlMs = 15)
        now = 65L
        assertFalse(ledger.consume(expired, handle, handle.origin, VaultOperation.OAUTH_REQUEST))
        val revoked = ledger.approve(handle, handle.origin, VaultOperation.OAUTH_REQUEST)
        ledger.revokeForHandle(handle.id)
        assertFalse(ledger.consume(revoked, handle, handle.origin, VaultOperation.OAUTH_REQUEST))
        val another = ledger.approve(handle, handle.origin, VaultOperation.OAUTH_REQUEST)
        ledger.revokeAll()
        assertFalse(ledger.consume(another, handle, handle.origin, VaultOperation.OAUTH_REQUEST))
    }

    @Test fun `grant duration cannot be indefinitely extended`() {
        val ledger = VaultGrantLedger { 10L }
        for (ttl in listOf(0L, 300_001L, -1L)) {
            assertThrows(IllegalArgumentException::class.java) {
                ledger.approve(handle, handle.origin, VaultOperation.FILL_LOGIN, ttl)
            }
        }
    }
}
