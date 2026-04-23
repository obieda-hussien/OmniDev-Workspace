package com.omnidev.workspace.core.policy

import com.omnidev.workspace.BuildConfig

/**
 * [TierPolicy] for the PRO flavor (Elite Hackers / Power Users, B2C Premium).
 *
 * Capabilities: full God Mode.
 *   * Shizuku + root shell.
 *   * AdvancedSecurityAnalyzer, VulnResearchToolchain, PrivilegedExecutionManager.
 *   * Full multi-agent swarm.
 *   * Device Admin wipe permitted.
 *   * Local SLM (llama.cpp) enabled.
 *
 * The [confirmationGate] always delegates to the UI gate — the user MUST see
 * and approve every privileged action, consistent with B2C transparency
 * expectations. If a power user wants zero-click execution they should use the
 * OEM flavor.
 */
class ProTierPolicy : TierPolicy {
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
