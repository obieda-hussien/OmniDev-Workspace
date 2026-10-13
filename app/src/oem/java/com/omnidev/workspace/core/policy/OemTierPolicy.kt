package com.omnidev.workspace.core.policy

import com.omnidev.workspace.BuildConfig

/** ROM assistant policy. Typed system actions use a separately signed broker;
 * the assistant keeps its own UID and asks for confirmation before mutations. */
class OemTierPolicy : TierPolicy {
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
