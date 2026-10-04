package com.omnidev.workspace.data.assistant

import com.omnidev.workspace.BuildConfig
import com.omnidev.workspace.core.policy.*
import com.omnidev.workspace.domain.engine.IntentClassifier.ToolDomain
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AssistantFlavorPolicyTest {
    private class Policy(
        override val tier: String,
        override val allowAccessibility: Boolean = false,
        override val allowRoot: Boolean = false,
        override val allowShizuku: Boolean = false,
        override val allowSystemIntegration: Boolean = false,
        override val autoApproveConfirmations: Boolean = false
    ) : TierPolicy {
        override val allowDeepSecurity = false
        override val allowDeviceAdminWipe = false
        override val allowLocalSlm = false
        override fun confirmationGate(uiGate: ConfirmationGate): ConfirmationGate = uiGate
    }
    @After fun clearAudit() { OmniAuditLog.clearForTest() }

    @Test fun liteDoesNotAdvertiseLiveDeviceActionsOrBubble() {
        val flavor = AssistantFlavorPolicy(Policy("LITE"))
        assertFalse(flavor.allowScreenActions)
        assertFalse(flavor.allowBubble)
        assertFalse(ToolDomain.DEVICE_CONTROL in flavor.toolDomains)
        assertTrue(flavor.preferredToolNames.isEmpty())
        assertTrue(ToolDomain.WEB_SEARCH in flavor.toolDomains)
    }
    @Test fun normExposesOrdinaryUiWithoutPrivilegedBackends() {
        val flavor = AssistantFlavorPolicy(Policy("NORM", allowAccessibility = true))
        assertTrue(flavor.allowScreenActions)
        assertTrue(flavor.allowBubble)
        assertTrue("semantic_ui" in flavor.preferredToolNames)
        assertTrue(flavor.promptContext.contains("Shizuku is unavailable"))
        assertTrue(flavor.promptContext.contains("Root is unavailable"))
    }
    @Test fun screenAssistantKeepsPermissionAndConnectedAppToolsDiscoverable() {
        val flavor = AssistantFlavorPolicy(Policy("PRO", true, true, true))
        assertTrue(flavor.preferredToolNames.containsAll(setOf("check_permission", "request_permission", "omni_link")))
        assertTrue(ToolDomain.MESSAGING in flavor.toolDomains)
        assertTrue(flavor.promptContext.contains("manifest declaration is not runtime authority"))
    }
    @Test fun proRequiresAvailableAndAuthorizedRootAndShizuku() {
        val flavor = AssistantFlavorPolicy(Policy("PRO", true, true, true))
        assertTrue(flavor.promptContext.contains("runtime grant"))
        assertTrue(flavor.promptContext.contains("authorized backend"))
        assertFalse(flavor.promptContext.contains("approval is automatic"))
        assertFalse(ToolDomain.ROOT_CONTROL in flavor.toolDomains) // Root is selected only when relevant.
    }
    @Test fun oemUsesSystemEntitlementWithoutAdvertisingRootOrShizuku() {
        val flavor = AssistantFlavorPolicy(Policy("OEM", allowAccessibility = true,
            allowSystemIntegration = true, autoApproveConfirmations = true))
        assertTrue(flavor.promptContext.contains("device entitlement"))
        assertTrue(flavor.promptContext.contains("automatic and audited"))
        assertTrue(flavor.promptContext.contains("Root is unavailable"))
        assertTrue(flavor.promptContext.contains("Shizuku is unavailable"))
    }
    @Test fun tierNameCannotGrantMissingCapabilities() {
        val flavor = AssistantFlavorPolicy(Policy("ADMIN"))
        assertFalse(flavor.allowScreenActions)
        assertFalse(flavor.allowBubble)
        assertTrue(flavor.preferredToolNames.isEmpty())
    }
    private fun buildPolicy(): TierPolicy {
        val name = mapOf("LITE" to "Lite", "NORM" to "Norm", "PRO" to "Pro", "OEM" to "Oem", "ADMIN" to "Admin").getValue(BuildConfig.TIER)
        return Class.forName("com.omnidev.workspace.core.policy.${name}TierPolicy")
            .getDeclaredConstructor().newInstance() as TierPolicy
    }
    @Test fun activeBuildUsesItsActualFlavorCapabilities() {
        val policy = buildPolicy()
        val flavor = AssistantFlavorPolicy(policy)
        assertEquals(BuildConfig.TIER, flavor.tier)
        assertEquals(BuildConfig.ALLOW_ACCESSIBILITY, flavor.allowScreenActions)
        assertEquals(BuildConfig.ALLOW_ACCESSIBILITY, flavor.allowBubble)
        assertEquals(BuildConfig.ALLOW_ACCESSIBILITY, "semantic_ui" in flavor.preferredToolNames)
    }
    @Test fun actualFlavorControlsConsentAndAutomaticApproval() = runBlocking {
        val policy = buildPolicy()
        var uiCalls = 0
        val gate = AssistantFlavorPolicy(policy).confirmationGate(ConfirmationGate { kind, _, _ ->
            assertEquals(ConfirmationKind.ASSISTANT_ACTION, kind)
            uiCalls++; false
        })
        val approved = gate.request(ConfirmationKind.ASSISTANT_ACTION, "Fill N3 with Hello", null)
        when (BuildConfig.TIER) {
            "LITE" -> { assertFalse(approved); assertEquals(0, uiCalls) }
            "NORM", "PRO" -> { assertFalse(approved); assertEquals(1, uiCalls) }
            "OEM", "ADMIN" -> {
                assertTrue(approved); assertEquals(0, uiCalls)
                assertTrue(OmniAuditLog.snapshot().any { it.tier == policy.tier && it.autoApproved && it.kind == ConfirmationKind.ASSISTANT_ACTION })
            }
        }
    }
}
