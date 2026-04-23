package com.omnidev.workspace.core.policy

/**
 * Process-wide holder for the active [TierPolicy].
 *
 * Initialised exactly once from the flavor-specific `OmniDevApp` bootstrap path
 * (see `src/lite/java/.../TierPolicyBootstrap.kt` and friends) with the policy
 * matching the current build variant.
 *
 * Anywhere in the codebase that needs to consult the tier can do:
 *
 * ```kotlin
 * if (TierPolicyHolder.current.allowShizuku) {
 *     // Pro-only path
 * }
 * ```
 *
 * The holder defaults to [DefaultFallbackTierPolicy] — an extremely restrictive
 * Lite-like policy — to guard against code paths that run before `OmniDevApp`
 * has had a chance to initialise (e.g. direct-boot receivers). In a correctly
 * wired build, `current` is replaced before any privileged code runs.
 */
object TierPolicyHolder {

    @Volatile
    private var policy: TierPolicy = DefaultFallbackTierPolicy

    /** Returns the currently installed policy. Never null. */
    val current: TierPolicy get() = policy

    /**
     * Installs the tier policy. Must be called exactly once from
     * `OmniDevApp.onCreate()` via the flavor-specific bootstrap.
     *
     * Subsequent calls REPLACE the policy (useful for instrumentation tests
     * that stub a specific tier).
     */
    fun install(policy: TierPolicy) {
        this.policy = policy
    }
}

/**
 * The deny-all safety net used until a real [TierPolicy] is installed.
 *
 * Returning this from any code path in production indicates a bootstrap bug:
 * the tier never got wired up.
 */
private object DefaultFallbackTierPolicy : TierPolicy {
    override val tier: String = "UNINITIALIZED"
    override val allowRoot: Boolean = false
    override val allowShizuku: Boolean = false
    override val allowAccessibility: Boolean = false
    override val allowDeepSecurity: Boolean = false
    override val allowDeviceAdminWipe: Boolean = false
    override val allowLocalSlm: Boolean = false
    override val allowSystemIntegration: Boolean = false
    override val autoApproveConfirmations: Boolean = false

    override fun confirmationGate(uiGate: ConfirmationGate): ConfirmationGate =
        ConfirmationGate { kind, preview, diff ->
            OmniAuditLog.record(
                tier = tier,
                autoApproved = false,
                kind = kind,
                preview = "[UNINITIALIZED TIER — DENIED] $preview",
                diffContent = diff
            )
            false
        }
}
