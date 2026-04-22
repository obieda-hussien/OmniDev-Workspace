package com.omnidev.workspace.core.policy

import com.omnidev.workspace.BuildConfig

/**
 * [TierPolicy] for the LITE flavor (Google Play Store Safe, B2C Free).
 *
 * Capabilities (summary):
 *   * Tools: `web_search`, `web_search_deep`, `web_scraper`, `read_file` only.
 *   * Monetization: Ads + BYOK.
 *   * No Shizuku, no root, no Accessibility, no system integration.
 *   * No local SLM (llama.cpp disabled, APK is ~40 MB smaller).
 *
 * The [confirmationGate] returns a deny-all gate as defense-in-depth:
 * the privileged tools that would call it are not even registered on Lite,
 * but if one slipped through a bug path it would be blocked here too.
 */
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

    override fun confirmationGate(uiGate: ConfirmationGate): ConfirmationGate =
        // Lite should never reach this gate, but as a final safety net we
        // record the attempt (so bugs surface in analytics) and deny.
        ConfirmationGate { kind, preview, diff ->
            OmniAuditLog.record(
                tier = tier,
                autoApproved = false,
                kind = kind,
                preview = "[LITE DENIED] $preview",
                diffContent = diff
            )
            false
        }
}
