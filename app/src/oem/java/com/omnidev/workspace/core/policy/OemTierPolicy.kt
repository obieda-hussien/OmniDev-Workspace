package com.omnidev.workspace.core.policy

import com.omnidev.workspace.BuildConfig

/**
 * [TierPolicy] for the OEM flavor (B2B partners / Custom ROM / platform-signed).
 *
 * Capabilities:
 *   * `android:sharedUserId="android.uid.system"` → system-signature privilege.
 *   * Local SLM (llama.cpp) enabled for offline inference.
 *   * Zero-click execution: [autoApproveConfirmations] is `true` and every
 *     privileged action is auto-approved with an audit-log record.
 *   * **NO** Shizuku (system uid makes it redundant).
 *   * **NO** `su` root (OEM policy prefers system-service privilege).
 *   * **NO** Device Admin wipe (destructive; OEM contract disallows it).
 *
 * The [confirmationGate] returns a gate that:
 *   1. Writes every request to [OmniAuditLog] with `autoApproved = true`.
 *   2. Returns `true` immediately — no UI dialog is shown.
 *
 * This is THE mechanism that makes the agent fully autonomous for OEM partners.
 * The agent plans an action → calls `gate.request(...)` → the gate logs it and
 * returns `true` → the action executes without user interruption.
 *
 * For observability, the `OmniAuditLog.events` SharedFlow can be subscribed to
 * from an OEM partner's telemetry / remote-management app.
 */
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

    /**
     * Returns the OEM zero-click gate.
     *
     * The supplied [uiGate] is intentionally ignored — OEM partners have
     * explicitly contracted for fully autonomous execution. Every call is
     * forensic-logged via [OmniAuditLog.record].
     */
    override fun confirmationGate(uiGate: ConfirmationGate): ConfirmationGate =
        ConfirmationGate { kind, preview, diff ->
            OmniAuditLog.record(
                tier = tier,
                autoApproved = true,
                kind = kind,
                preview = preview,
                diffContent = diff
            )
            // Zero-click: approve immediately, agent proceeds without user input.
            true
        }
}
