package com.omnidev.workspace.core.policy

/** Flavor-specific bootstrap for the NORM build variant. See LiteTierPolicyBootstrap for details. */
object TierPolicyBootstrap {
    fun install() {
        TierPolicyHolder.install(NormTierPolicy())
    }
}
