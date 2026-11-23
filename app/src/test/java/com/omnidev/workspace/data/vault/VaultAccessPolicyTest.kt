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
            "request\\n12345", "request\\r12345", "request 12345",
            "request/12345", "request:12345", "request\\t12345",
            "request\\u000012345"
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
}
