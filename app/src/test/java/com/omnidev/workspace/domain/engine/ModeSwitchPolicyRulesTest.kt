package com.omnidev.workspace.domain.engine

import org.junit.Assert.*
import org.junit.Test

class ModeSwitchPolicyRulesTest {
    @Test fun staySelectedSuppressesEveryAutomaticHandoff() {
        assertFalse(ModeSwitchPolicyRules.shouldOffer(
            ModeSwitchPermissionStore.SwitchPolicy.STAY_IN_SELECTED_MODE,
            OmniMode.SWARM, OmniMode.AGENT, false
        ))
        assertFalse(ModeSwitchPolicyRules.canAutoSwitch(
            ModeSwitchPermissionStore.SwitchPolicy.STAY_IN_SELECTED_MODE, true
        ))
    }

    @Test fun askEveryTimeShowsProposalButNeverUsesSavedGrantAutomatically() {
        assertTrue(ModeSwitchPolicyRules.shouldOffer(
            ModeSwitchPermissionStore.SwitchPolicy.ASK_EVERY_TIME,
            OmniMode.AGENT, OmniMode.SWARM, false
        ))
        assertFalse(ModeSwitchPolicyRules.canAutoSwitch(
            ModeSwitchPermissionStore.SwitchPolicy.ASK_EVERY_TIME, true
        ))
    }

    @Test fun autoApprovedRequiresAnExplicitPreviousGrant() {
        assertTrue(ModeSwitchPolicyRules.canAutoSwitch(
            ModeSwitchPermissionStore.SwitchPolicy.AUTO_APPROVED, true
        ))
        assertFalse(ModeSwitchPolicyRules.canAutoSwitch(
            ModeSwitchPermissionStore.SwitchPolicy.AUTO_APPROVED, false
        ))
    }

    @Test fun deniedTransitionIsSuppressedForTheCurrentSession() {
        assertFalse(ModeSwitchPolicyRules.shouldOffer(
            ModeSwitchPermissionStore.SwitchPolicy.ASK_EVERY_TIME,
            OmniMode.SWARM, OmniMode.AGENT, true
        ))
    }

    @Test fun sameModeAndAutoTargetAreNotValidHandoffs() {
        val policy = ModeSwitchPermissionStore.SwitchPolicy.AUTO_APPROVED
        assertFalse(ModeSwitchPolicyRules.shouldOffer(policy, OmniMode.AGENT, OmniMode.AGENT, false))
        assertFalse(ModeSwitchPolicyRules.shouldOffer(policy, OmniMode.AGENT, OmniMode.AUTO, false))
    }
}
