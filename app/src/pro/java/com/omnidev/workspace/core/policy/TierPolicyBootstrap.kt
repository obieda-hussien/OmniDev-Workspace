package com.omnidev.workspace.core.policy

/** Flavor-specific bootstrap for the PRO build variant. See LiteTierPolicyBootstrap for details. */
object TierPolicyBootstrap {
    fun install() {
        TierPolicyHolder.install(ProTierPolicy())
    }
}
