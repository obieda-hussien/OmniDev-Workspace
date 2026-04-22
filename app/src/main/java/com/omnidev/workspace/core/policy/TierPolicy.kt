package com.omnidev.workspace.core.policy

/**
 * Tier-level capability policy resolved at compile time from the active product flavor.
 *
 * Every privileged or restricted code path in the project MUST check the relevant
 * `allow*` flag on the `TierPolicy` bound in the application graph, rather than
 * branching on [com.omnidev.workspace.BuildConfig.TIER] directly. This makes the
 * policy testable (stubbable) and decouples business logic from the flavor system.
 *
 * Four implementations ship in the project, one per product flavor:
 *  - [com.omnidev.workspace.core.policy.LiteTierPolicy] — in `src/lite/java`
 *  - [com.omnidev.workspace.core.policy.NormTierPolicy] — in `src/norm/java`
 *  - [com.omnidev.workspace.core.policy.ProTierPolicy]  — in `src/pro/java`
 *  - [com.omnidev.workspace.core.policy.OemTierPolicy]  — in `src/oem/java`
 *
 * The Android build system guarantees exactly one of those files is on the
 * classpath for any given build variant.
 */
interface TierPolicy {

    /** Human-readable tier name, e.g. `"LITE"`, `"NORM"`, `"PRO"`, `"OEM"`. */
    val tier: String

    /** Whether root-shell (su) escalation is permitted in this tier. */
    val allowRoot: Boolean

    /**
     * Whether the Shizuku privileged API is permitted.
     *
     * Lite / Norm / OEM return `false`. Pro returns `true`.
     * Even when `true`, callers must still verify runtime availability via
     * `ShizukuCommandTool.isAvailable() && ShizukuCommandTool.hasPermission()`.
     */
    val allowShizuku: Boolean

    /**
     * Whether the `OmniAccessibilityService` / Semantic UI tool may be used.
     *
     * Lite returns `false` (Play-Store compliance).
     * Norm / Pro / OEM return `true`.
     */
    val allowAccessibility: Boolean

    /**
     * Whether deep security / pentesting tools are permitted
     * ([com.omnidev.workspace.data.tools.security.AdvancedSecurityAnalyzer],
     *  [com.omnidev.workspace.data.tools.VulnResearchToolchain],
     *  [com.omnidev.workspace.data.tools.AndroidVulnResearchEngine], etc.).
     *
     * Only Pro returns `true`.
     */
    val allowDeepSecurity: Boolean

    /**
     * Whether `DevicePolicyManager.wipeData()` / factory-reset is permitted.
     *
     * Pro returns `true`. Lite / Norm / OEM return `false`.
     * OEM explicitly opts out of wipe even though it has system-uid privilege —
     * the OEM partner policy disallows destructive actions.
     */
    val allowDeviceAdminWipe: Boolean

    /**
     * Whether local SLM (llama.cpp) inference is enabled.
     *
     * Pro / OEM return `true`. Lite / Norm return `false` (saves ~40 MB APK).
     */
    val allowLocalSlm: Boolean

    /**
     * Whether this build has `android:sharedUserId="android.uid.system"`
     * and therefore can call system-signature APIs directly without Shizuku.
     *
     * Only OEM returns `true`.
     */
    val allowSystemIntegration: Boolean

    /**
     * Whether the agent may execute planned privileged actions WITHOUT prompting
     * the user for confirmation (zero-click execution).
     *
     * Only OEM returns `true` — partner policy allows fully autonomous execution
     * because the OEM build is a pre-installed system app on managed devices.
     * Every auto-approved action is still written to the audit log.
     */
    val autoApproveConfirmations: Boolean

    /**
     * Returns the effective [ConfirmationGate] to use for privileged actions.
     *
     * Implementations may wrap / replace the UI-backed gate. For example:
     *  - [com.omnidev.workspace.core.policy.OemTierPolicy] returns a gate that
     *    auto-approves everything and writes to the audit log.
     *  - [com.omnidev.workspace.core.policy.ProTierPolicy] returns the UI gate
     *    unchanged so the user always sees the Compose dialog.
     *  - [com.omnidev.workspace.core.policy.LiteTierPolicy] returns a gate that
     *    denies everything (the tools that would call it aren't registered
     *    anyway, but this is defense-in-depth).
     *
     * @param uiGate The UI-backed gate wired by `ChatViewModel.wireFileConfirmationGate`.
     *               It drives the Compose [com.omnidev.workspace.ui.chat.ConfirmationGateDialog].
     */
    fun confirmationGate(uiGate: ConfirmationGate): ConfirmationGate
}
