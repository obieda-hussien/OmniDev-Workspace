package com.omnidev.workspace.data.auth

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class OAuthPkceTransactionTest {
    @Test fun pkceChallengeMatchesS256AndStateIsRandom() {
        val first = OAuthPkceTransaction.create(1000L)
        val second = OAuthPkceTransaction.create(1000L)
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.codeChallenge, second.codeChallenge)
        assertEquals(43, first.state.length)
        assertEquals(43, first.codeChallenge.length)
        val verifier = first.consumeVerifier(first.state, 1001L)!!
        assertTrue(verifier.matches(Regex("[A-Za-z0-9_-]{43}")))
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        assertEquals(expected, first.codeChallenge)
        assertEquals("OAuthPkceTransaction(redacted)", first.toString())
    }

    @Test fun wrongStateAndReplayFailClosed() {
        val transaction = OAuthPkceTransaction.create(1000L)
        assertNull(transaction.consumeVerifier("wrong", 1001L))
        assertNull(transaction.consumeVerifier(transaction.state + "x", 1001L))
        assertNotNull(transaction.consumeVerifier(transaction.state, 1001L))
        assertNull(transaction.consumeVerifier(transaction.state, 1002L))
    }

    @Test fun expiryRevocationAndOverflowFailClosed() {
        val expired = OAuthPkceTransaction.create(1000L, 1L)
        assertNull(expired.consumeVerifier(expired.state, 999L))
        assertNull(expired.consumeVerifier(expired.state, 1001L))
        assertNotNull(expired.consumeVerifier(expired.state, 1000L))
        val revoked = OAuthPkceTransaction.create(1000L)
        revoked.revoke()
        assertNull(revoked.consumeVerifier(revoked.state, 1001L))
        for (invalid in listOf(0L, -1L, 600_001L)) {
            assertThrows(IllegalArgumentException::class.java) {
                OAuthPkceTransaction.create(1000L, invalid)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            OAuthPkceTransaction.create(Long.MAX_VALUE)
        }
    }

    @Test fun concurrentCallbacksCannotConsumeTwice() {
        val transaction = OAuthPkceTransaction.create(1000L)
        val ready = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(12)
        val wins = java.util.concurrent.atomic.AtomicInteger()
        repeat(12) {
            Thread {
                try {
                    ready.await()
                    if (transaction.consumeVerifier(transaction.state, 1001L) != null) wins.incrementAndGet()
                } finally { done.countDown() }
            }.start()
        }
        ready.countDown()
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, wins.get())
    }
}
