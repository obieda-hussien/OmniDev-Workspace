package com.omnidev.workspace.core.policy

/**
 * Flavor-specific bootstrap for the LITE build variant.
 *
 * Exactly one file with this name exists per flavor source set:
 *   * `src/lite/java/.../TierPolicyBootstrap.kt` → installs [LiteTierPolicy]
 *   * `src/norm/java/.../TierPolicyBootstrap.kt` → installs [NormTierPolicy]
 *   * `src/pro/java/.../TierPolicyBootstrap.kt`  → installs [ProTierPolicy]
 *   * `src/oem/java/.../TierPolicyBootstrap.kt`  → installs [OemTierPolicy]
 *
 * The Android build system guarantees exactly one is on the classpath for any
 * given build variant.
 */
object TierPolicyBootstrap {
    /** Invoked from `OmniDevApp.onCreate()`. */
    fun install() {
        TierPolicyHolder.install(LiteTierPolicy())
    }
}
