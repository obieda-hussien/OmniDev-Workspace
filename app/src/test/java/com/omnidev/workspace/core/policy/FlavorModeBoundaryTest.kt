package com.omnidev.workspace.core.policy

import com.omnidev.workspace.domain.engine.OmniMode
import org.junit.Assert.*
import org.junit.Test

class FlavorModeBoundaryTest {
    private fun policy(name: String) = object : TierPolicy {
        override val tier = name
        override val allowRoot = false
        override val allowShizuku = false
        override val allowAccessibility = false
        override val allowDeepSecurity = false
        override val allowDeviceAdminWipe = false
        override val allowLocalSlm = true
        override val allowSystemIntegration = name == "OEM"
        override val autoApproveConfirmations = false
        override fun confirmationGate(uiGate: ConfirmationGate) = uiGate
    }
    @Test fun liteKeepsVoiceAndAssistantWithoutAnyAutonomousMode() {
        val lite = policy("LITE")
        assertEquals(listOf(OmniMode.CHAT), OmniMode.available(lite))
        assertTrue(lite.allowLocalVoice)
        assertTrue(lite.allowBubble)
        assertFalse(lite.allowAccessibility)
    }
    @Test fun standardCannotSelectTeamAndOemCan() {
        assertFalse(OmniMode.SWARM in OmniMode.available(policy("NORM")))
        assertTrue(OmniMode.AGENT in OmniMode.available(policy("NORM")))
        assertTrue(OmniMode.SWARM in OmniMode.available(policy("OEM")))
    }
    @Test fun unknownPolicyCannotAcquirePrivilegedModesOrVoice() {
        val unknown = policy("NEW_UNREVIEWED_TIER")
        assertFalse(unknown.allowAgent)
        assertFalse(unknown.allowTeam)
        assertFalse(unknown.allowAssistant)
    }
}
