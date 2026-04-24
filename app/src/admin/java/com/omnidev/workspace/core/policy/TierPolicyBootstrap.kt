package com.omnidev.workspace.core.policy

/**
 * Flavor-specific bootstrap for the ADMIN (master-key) build variant.
 * See `src/lite/java/.../TierPolicyBootstrap.kt` for the symmetry contract.
 */
object TierPolicyBootstrap {
    fun install() {
        TierPolicyHolder.install(AdminTierPolicy())
    }
}
