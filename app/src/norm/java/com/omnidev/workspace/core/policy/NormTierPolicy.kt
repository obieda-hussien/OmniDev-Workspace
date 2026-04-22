package com.omnidev.workspace.core.policy

import com.omnidev.workspace.BuildConfig

/**
 * [TierPolicy] for the NORM flavor (Standard developer, B2C Basic).
 *
 * Capabilities:
 *   * Accessibility / Semantic UI reading.
 *   * Basic terminal, Git management, App manager (non-root).
 *   * NO Shizuku, NO root, NO deep-security / pentesting tools.
 *
 * The [confirmationGate] always delegates to the UI gate — the user sees the
 * standard Compose dialog before any privileged action proceeds.
 */
class NormTierPolicy : TierPolicy {
    override val tier: String                  = BuildConfig.TIER
    override val allowRoot: Boolean             = BuildConfig.ALLOW_ROOT
    override val allowShizuku: Boolean          = BuildConfig.ALLOW_SHIZUKU
    override val allowAccessibility: Boolean    = BuildConfig.ALLOW_ACCESSIBILITY
    override val allowDeepSecurity: Boolean     = BuildConfig.ALLOW_DEEP_SECURITY
    override val allowDeviceAdminWipe: Boolean  = BuildConfig.ALLOW_DEVICE_ADMIN_WIPE
    override val allowLocalSlm: Boolean         = BuildConfig.ENABLE_LOCAL_SLM
    override val allowSystemIntegration: Boolean = BuildConfig.ALLOW_SYSTEM_INTEGRATION
    override val autoApproveConfirmations: Boolean = BuildConfig.AUTO_APPROVE_CONFIRMATIONS

    override fun confirmationGate(uiGate: ConfirmationGate): ConfirmationGate = uiGate
}
