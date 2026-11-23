package com.omnidev.workspace.ui.settings

/**
 * P13 identity safety boundary. A source badge, matching display name, or matching
 * address is never sufficient to link accounts across channels.
 *
 * This is a pure policy helper, not a credential verifier or identity database.
 * Only an explicit, independently verified binding may authorize a cross-source link.
 */
internal object OmniConnectIdentityPolicy {
    data class VerifiedBinding(
        val principalId: String,
        val source: String,
        val sourceAccountId: String,
        val verificationReceiptId: String,
        val verifiedAtMillis: Long,
        val expiresAtMillis: Long
    )

    fun canLink(
        left: VerifiedBinding?,
        right: VerifiedBinding?,
        nowMillis: Long
    ): Boolean {
        if (left == null || right == null || left === right) return false
        if (!valid(left, nowMillis) || !valid(right, nowMillis)) return false
        if (left.source == right.source && left.sourceAccountId == right.sourceAccountId) return false
        if (left.verificationReceiptId == right.verificationReceiptId) return false
        return left.principalId == right.principalId
    }

    private fun valid(binding: VerifiedBinding, nowMillis: Long): Boolean =
        binding.principalId.isNotBlank() &&
            binding.source.isNotBlank() &&
            binding.sourceAccountId.isNotBlank() &&
            binding.verificationReceiptId.isNotBlank() &&
            binding.verifiedAtMillis >= 0 &&
            binding.expiresAtMillis > binding.verifiedAtMillis &&
            nowMillis >= binding.verifiedAtMillis &&
            nowMillis < binding.expiresAtMillis
}
