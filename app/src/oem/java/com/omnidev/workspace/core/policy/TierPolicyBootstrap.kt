package com.omnidev.workspace.core.policy

/** Flavor-specific bootstrap for the OEM build variant. See LiteTierPolicyBootstrap for details. */
object TierPolicyBootstrap {
    fun install() {
        TierPolicyHolder.install(OemTierPolicy())
    }
}
