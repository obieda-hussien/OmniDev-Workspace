package com.omnidev.workspace.core.policy

import com.omnidev.workspace.BuildConfig

/**
 * [TierPolicy] for the ADMIN flavor — the MASTER KEY build.
 *
 * ⚠️  **ADMIN IS A DEVELOPER-ONLY BUILD.** It is never shipped, never sold,
 * never side-loaded onto end-user devices. Its sole purpose is rapid end-to-end
 * QA of every tool, permission, and privileged path the project can exercise.
 *
 * Capabilities (literally every flag `true`):
 *   * Root shell (su) escalation.
 *   * Shizuku ADB-level execution.
 *   * Accessibility / Semantic UI reading.
 *   * Deep security / pentesting tools (AdvancedSecurityAnalyzer, VulnResearchToolchain…).
 *   * Device Admin wipe / factory reset.
 *   * Local SLM (llama.cpp) with the full ABI set.
 *   * System integration hooks (every high-privilege facade is active).
 *   * Zero-click auto-approval — every confirmation gate auto-approves with an
 *     audit-log entry so tests can run unattended.
 *
 * The [confirmationGate] intentionally ignores the supplied UI gate, mirroring
 * [OemTierPolicy]'s behaviour: the admin build must execute fully autonomously
 * so that automated UI / integration tests never stall on a dialog. Every
 * auto-approved request is still recorded via [OmniAuditLog] so a human can
 * audit the test run afterwards.
 */
class AdminTierPolicy : TierPolicy {
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
     * Returns the admin zero-click gate. Every call is forensic-logged via
     * [OmniAuditLog.record] and then auto-approved so that automated test suites
     * never hang on a Compose dialog.
     */
    override fun confirmationGate(uiGate: ConfirmationGate): ConfirmationGate =
        ConfirmationGate { kind, preview, diff ->
            OmniAuditLog.record(
                tier = tier,
                autoApproved = true,
                kind = kind,
                preview = "[ADMIN MASTER-KEY] $preview",
                diffContent = diff
            )
            // Master key: approve immediately, agent proceeds without user input.
            true
        }
}
