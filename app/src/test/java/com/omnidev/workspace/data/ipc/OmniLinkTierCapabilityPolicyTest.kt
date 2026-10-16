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
    @Test
    fun launcherControlIsScopedByPackageTierAndKnownCapability() {
        val launcher = "app.lawnchair.debug/app.lawnchair.omni.OmniLauncherService"
        listOf("ADMIN", "PRO", "OEM").forEach { tier ->
            assertTrue(OmniLinkTierCapabilityPolicy.allowed(tier, launcher, "launcher.set_preference"))
        }
        assertFalse(OmniLinkTierCapabilityPolicy.allowed("NORM", launcher, "launcher.set_preference"))
        assertTrue(OmniLinkTierCapabilityPolicy.allowed("NORM", launcher, "launcher.open_app"))
        assertTrue(OmniLinkTierCapabilityPolicy.allowed("LITE", launcher, "launcher.list_apps"))
        assertTrue(OmniLinkTierCapabilityPolicy.allowed("LITE", launcher, "launcher.set_preference"))
        assertFalse(OmniLinkTierCapabilityPolicy.allowed("LITE", "evil.app/Service", "launcher.open_app"))
        assertTrue(OmniLinkTierCapabilityPolicy.allowed("LITE", launcher, "launcher.health"))
        assertFalse(OmniLinkTierCapabilityPolicy.allowed("ADMIN", "evil.app/Service", "launcher.health"))
        assertFalse(OmniLinkTierCapabilityPolicy.allowed("ADMIN", launcher, "launcher.clear_home"))
        assertFalse(OmniLinkTierCapabilityPolicy.allowed("UNKNOWN", launcher, "launcher.health"))
    }

    @Test
    fun layoutReadAndWritesMatchReceiverCeilings() {
        val launcher = "app.lawnchair/app.lawnchair.omni.OmniLauncherService"
        assertTrue(OmniLinkTierCapabilityPolicy.allowed("NORM", launcher, "launcher.get_layout"))
        listOf("launcher.create_home_folder", "launcher.move_items", "launcher.set_grid", "launcher.create_folder").forEach { action ->
            listOf("PRO", "OEM", "ADMIN").forEach { assertTrue(OmniLinkTierCapabilityPolicy.allowed(it, launcher, action)) }
            listOf("LITE", "NORM").forEach { assertFalse(OmniLinkTierCapabilityPolicy.allowed(it, launcher, action)) }
            assertFalse(OmniLinkTierCapabilityPolicy.allowed("ADMIN", "evil.app/Service", action))
        }
    }

    @Test
    fun homeExperiencesRequireKnownCapabilitiesAndAppropriateTiers() {
        val launcher = "app.lawnchair/app.lawnchair.omni.OmniLauncherService"
        assertTrue(OmniLinkTierCapabilityPolicy.allowed("NORM", launcher, "launcher.list_scenes"))
        assertTrue(OmniLinkTierCapabilityPolicy.allowed("NORM", launcher, "launcher.open_action"))
        assertFalse(OmniLinkTierCapabilityPolicy.allowed("NORM", launcher, "launcher.apply_scene"))
        assertTrue(OmniLinkTierCapabilityPolicy.allowed("PRO", launcher, "launcher.apply_scene"))
        assertFalse(OmniLinkTierCapabilityPolicy.allowed("PRO", launcher, "launcher.oem.suspend_app"))
        assertTrue(OmniLinkTierCapabilityPolicy.allowed("OEM", launcher, "launcher.oem.suspend_app"))
        assertFalse(OmniLinkTierCapabilityPolicy.allowed("LITE", launcher, "launcher.shelf.publish"))
        assertFalse(OmniLinkTierCapabilityPolicy.allowed("ADMIN", launcher, "launcher.future_unknown"))
        assertFalse(OmniLinkTierCapabilityPolicy.allowed("OEM", "evil.app/Service", "launcher.oem.suspend_app"))
    }
}

