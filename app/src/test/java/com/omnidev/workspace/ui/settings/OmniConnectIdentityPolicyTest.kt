package com.omnidev.workspace.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniConnectIdentityPolicyTest {
    private fun binding(
        source: String,
        account: String,
        receipt: String,
        principal: String = "verified-principal",
        expires: Long = 200L
    ) = OmniConnectIdentityPolicy.VerifiedBinding(
        principal, source, account, receipt, 100L, expires
    )

    @Test fun independentVerifiedBindingsCanLink() {
        assertTrue(OmniConnectIdentityPolicy.canLink(
            binding("telegram", "account-a", "proof-a"),
            binding("discord", "account-b", "proof-b"), 150L
        ))
    }

    @Test fun missingOrExpiredProofCannotLink() {
        val left = binding("telegram", "account-a", "proof-a")
        val right = binding("discord", "account-b", "proof-b")
        assertFalse(OmniConnectIdentityPolicy.canLink(null, right, 150L))
        assertFalse(OmniConnectIdentityPolicy.canLink(left, right, 200L))
        assertFalse(OmniConnectIdentityPolicy.canLink(left, right, 99L))
    }

    @Test fun sharedNamesOrReplayedReceiptNeverProveIdentity() {
        val left = binding("telegram", "same-name", "proof-a")
        assertFalse(OmniConnectIdentityPolicy.canLink(
            left, binding("discord", "same-name", "proof-a"), 150L
        ))
        assertFalse(OmniConnectIdentityPolicy.canLink(
            left, binding("discord", "same-name", "proof-b", "other-principal"), 150L
        ))
        assertFalse(OmniConnectIdentityPolicy.canLink(left, left, 150L))
    }

    @Test fun blankIdentityFieldsAreRejected() {
        assertFalse(OmniConnectIdentityPolicy.canLink(
            binding("telegram", "account-a", "proof-a", ""),
            binding("discord", "account-b", "proof-b", ""), 150L
        ))
    }
}
