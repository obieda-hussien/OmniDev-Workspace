package com.omnidev.workspace.core.policy

import com.omnidev.workspace.BuildConfig

/** Lite keeps chat, bounded tools, delegated files, local models/voice and the floating assistant.
 * No autonomous Agent/Team, Shizuku, root, Accessibility or system broker is included.
 * Allowed mutations delegate to the user's confirmation gate. */
class LiteTierPolicy : TierPolicy {
    override val tier: String                 = BuildConfig.TIER
    override val allowRoot: Boolean            = BuildConfig.ALLOW_ROOT
    override val allowShizuku: Boolean         = BuildConfig.ALLOW_SHIZUKU
    override val allowAccessibility: Boolean   = BuildConfig.ALLOW_ACCESSIBILITY
    override val allowDeepSecurity: Boolean    = BuildConfig.ALLOW_DEEP_SECURITY
    override val allowDeviceAdminWipe: Boolean = BuildConfig.ALLOW_DEVICE_ADMIN_WIPE
    override val allowLocalSlm: Boolean        = BuildConfig.ENABLE_LOCAL_SLM
    override val allowSystemIntegration: Boolean = BuildConfig.ALLOW_SYSTEM_INTEGRATION
    override val autoApproveConfirmations: Boolean = BuildConfig.AUTO_APPROVE_CONFIRMATIONS

    // Allowed file/browser/connected-app mutations retain explicit user confirmation.
    override fun confirmationGate(uiGate: ConfirmationGate): ConfirmationGate = uiGate
}
