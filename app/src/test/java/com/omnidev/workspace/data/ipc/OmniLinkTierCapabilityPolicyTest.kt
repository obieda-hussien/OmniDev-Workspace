package com.omnidev.workspace.data.ipc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniLinkTierCapabilityPolicyTest {
    private val ide = "dev.mutwakil.androidide/dev.mutwakil.androidide.omni.OmniIdeExtensionService"

    @Test
    fun adminGetsDeepIdeCapabilitiesOnlyFromVerifiedIdeId() {
        assertTrue(OmniLinkTierCapabilityPolicy.allowed("ADMIN", ide, "ide.apply_line_patch"))
        assertTrue(OmniLinkTierCapabilityPolicy.allowed("ADMIN", ide, "ide.export_payload"))
        assertFalse(
            OmniLinkTierCapabilityPolicy.allowed(
                "ADMIN", "com.evil.app/Clone", "ide.apply_line_patch"
            )
        )
    }

    @Test
    fun regularFlavorsDoNotGetMutationOrPayloadExport() {
        listOf("LITE", "NORM", "PRO", "OEM").forEach { tier ->
            assertFalse(OmniLinkTierCapabilityPolicy.allowed(tier, ide, "ide.git_push"))
            assertFalse(OmniLinkTierCapabilityPolicy.allowed(tier, ide, "ide.export_payload"))
        }
        assertTrue(OmniLinkTierCapabilityPolicy.allowed("PRO", ide, "ide.start_build"))
        assertFalse(OmniLinkTierCapabilityPolicy.allowed("LITE", ide, "ide.start_build"))
    }

    @Test
    fun unknownTierIsNotPrivileged() {
        assertFalse(OmniLinkTierCapabilityPolicy.allowed("UNKNOWN", ide, "ide.health"))
    }
}
