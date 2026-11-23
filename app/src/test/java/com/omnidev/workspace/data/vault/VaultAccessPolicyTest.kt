package com.omnidev.workspace.data.vault

import org.junit.Assert.*
import org.junit.Test

class VaultAccessPolicyTest {
    private val reference = "550e8400-e29b-41d4-a716-446655440000"
    private val scope = VaultAccessPolicy.Scope(
        reference,
        "https://accounts.example.com",
        VaultAccessPolicy.Operation.SIGN_IN,
        "request-12345"
    )

    @Test fun canonicalOriginUsesExactHttpsHostAndPort() {
        assertEquals(
            "https://accounts.example.com",
            VaultAccessPolicy.canonicalOrigin("https://ACCOUNTS.EXAMPLE.COM:443/login?next=1")
        )
        assertEquals(
            "https://accounts.example.com:8443",
            VaultAccessPolicy.canonicalOrigin("https://accounts.example.com:8443/login")
        )
        assertNull(VaultAccessPolicy.canonicalOrigin("http://accounts.example.com"))
        assertNull(VaultAccessPolicy.canonicalOrigin("https://user:pass@accounts.example.com/login"))
        assertNull(VaultAccessPolicy.canonicalOrigin("javascript:alert(1)"))
        assertNull(VaultAccessPolicy.canonicalOrigin("https://accounts.example.com./"))
    }

    @Test fun userConsentIsOneUseAndBoundToRequestAndOperation() {
        val grant = VaultAccessPolicy.issueAfterUserConfirmation(scope, issuedAtMillis = 1000L)
        assertFalse(grant.matches(scope.copy(requestId = "different-123"), 1001L))
        assertFalse(grant.matches(scope.copy(operation = VaultAccessPolicy.Operation.SEND_MESSAGE), 1001L))
        assertTrue(grant.consume(scope, 1001L))
        assertFalse(grant.consume(scope, 1002L))
        assertEquals("VaultConsentGrant(redacted)", grant.toString())
    }

    @Test fun approvalCannotBeReusedForSubdomainsOrLookalikes() {
        val grant = VaultAccessPolicy.issueAfterUserConfirmation(scope, issuedAtMillis = 1000L)
        assertFalse(grant.consume(scope.copy(origin = "https://sub.accounts.example.com"), 1001L))
        assertFalse(grant.consume(scope.copy(origin = "https://accounts.example.com.evil.test"), 1001L))
        assertFalse(grant.consume(scope.copy(origin = "https://accounts.example.com:8443"), 1001L))
        assertFalse(grant.consume(scope.copy(referenceId = "550e8400-e29b-41d4-a716-446655440001"), 1001L))
        assertTrue(grant.consume(scope, 1001L))
    }

    @Test fun grantsExpireAndCannotBeReplayedAfterRevocation() {
        val expired = VaultAccessPolicy.issueAfterUserConfirmation(scope, 1000L, 25L)
        assertFalse(expired.consume(scope, 999L))
        assertTrue(expired.consume(scope, 1024L))
        assertFalse(expired.consume(scope, 1025L))

        val revoked = VaultAccessPolicy.issueAfterUserConfirmation(scope, 1000L)
        revoked.revoke()
        assertFalse(revoked.consume(scope, 1001L))
    }

    @Test fun consentRequestIdsRejectControlCharactersAndSeparators() {
        val unsafeIds = listOf(
            "request\n12345", "request\r12345", "request 12345",
            "request/12345", "request:12345", "request\t12345",
            "request\u000012345"
        )
        unsafeIds.forEach { id ->
            assertThrows(IllegalArgumentException::class.java) {
                VaultAccessPolicy.issueAfterUserConfirmation(
                    scope.copy(requestId = id), 1000L
                )
            }
        }
        val valid = VaultAccessPolicy.issueAfterUserConfirmation(
            scope.copy(requestId = "req_1234-ABCD"), 1000L
        )
        assertTrue(valid.consume(scope.copy(requestId = "req_1234-ABCD"), 1001L))
    }

    @Test fun invalidApprovalParametersFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            VaultAccessPolicy.issueAfterUserConfirmation(
                scope.copy(origin = "http://accounts.example.com"), 1000L
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultAccessPolicy.issueAfterUserConfirmation(scope, 1000L, 300_001L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultAccessPolicy.issueAfterUserConfirmation(scope.copy(requestId = ""), 1000L)
        }
    }
    @Test fun concurrentConsumersCannotSpendTheSameGrantTwice() {
        val grant = VaultAccessPolicy.issueAfterUserConfirmation(scope, 1000L)
        val ready = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(16)
        val successes = java.util.concurrent.atomic.AtomicInteger(0)
        repeat(16) {
            Thread {
                try {
                    ready.await()
                    if (grant.consume(scope, 1001L)) successes.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }.start()
        }
        ready.countDown()
        assertTrue("Consumers must terminate", done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("Grant must be consumed once", 1, successes.get())
    }

    @Test fun approvalLifetimeBoundariesFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            VaultAccessPolicy.issueAfterUserConfirmation(scope, Long.MAX_VALUE, 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultAccessPolicy.issueAfterUserConfirmation(scope, -1L, 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultAccessPolicy.issueAfterUserConfirmation(scope, 1000L, 0L)
        }
        val grant = VaultAccessPolicy.issueAfterUserConfirmation(scope, 1000L, 1L)
        assertFalse(grant.consume(scope, 999L))
        assertFalse(grant.consume(scope, 1001L))
        assertTrue(grant.consume(scope, 1000L))
    }

    @Test fun credentialConsentRejectsLocalAndAmbiguousHosts() {
        val rejected = listOf(
            "https://localhost/login",
            "https://intranet/login",
            "https://127.0.0.1/login",
            "https://192.168.1.1/login",
            "https://[::1]/login",
            "https://bad_host.example.com/login",
            "https://-bad.example.com/login",
            "https://bad-.example.com/login",
            "https://example..com/login",
            "https://example.com./login"
        )
        rejected.forEach { url ->
            assertNull("Must reject credential origin: $url", VaultAccessPolicy.canonicalOrigin(url))
        }
        assertEquals(
            "https://login.example.co.uk:8443",
            VaultAccessPolicy.canonicalOrigin("https://LOGIN.EXAMPLE.CO.UK:8443/sign-in")
        )
        assertEquals(
            "https://xn--bcher-kva.example",
            VaultAccessPolicy.canonicalOrigin("https://xn--bcher-kva.example/login")
        )
    }

    @Test fun credentialConsentRejectsSpecialUseDnsNamespaces() {
        val rejected = listOf(
            "https://login.localhost/auth",
            "https://printer.local/auth",
            "https://login.internal/auth",
            "https://login.test/auth",
            "https://login.invalid/auth",
            "https://router.home.arpa/auth",
            "https://1.168.192.in-addr.arpa/auth",
            "https://node.ip6.arpa/auth"
        )
        rejected.forEach { url ->
            assertNull("Must reject special-use credential origin: $url", VaultAccessPolicy.canonicalOrigin(url))
        }
        assertEquals(
            "https://accounts.example.com",
            VaultAccessPolicy.canonicalOrigin("https://accounts.example.com/login")
        )
        rejected.forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                VaultAccessPolicy.issueAfterUserConfirmation(scope.copy(origin = url), 1000L)
            }
        }
    }

}
