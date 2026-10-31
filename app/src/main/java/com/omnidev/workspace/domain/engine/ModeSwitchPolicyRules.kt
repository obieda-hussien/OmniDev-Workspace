package com.omnidev.workspace.domain.engine

/** Pure policy predicates shared by the persistent store and JVM tests. */
internal object ModeSwitchPolicyRules {
    fun shouldOffer(
        policy: ModeSwitchPermissionStore.SwitchPolicy,
        from: OmniMode,
        to: OmniMode,
        deniedForSession: Boolean
    ): Boolean = from != to && to != OmniMode.AUTO &&
        policy != ModeSwitchPermissionStore.SwitchPolicy.STAY_IN_SELECTED_MODE &&
        !deniedForSession

    fun canAutoSwitch(
        policy: ModeSwitchPermissionStore.SwitchPolicy,
        previouslyApproved: Boolean
    ): Boolean = policy == ModeSwitchPermissionStore.SwitchPolicy.AUTO_APPROVED && previouslyApproved
}
