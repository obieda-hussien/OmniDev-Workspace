package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.tools.DeviceAccessSetupPlan.Access
import com.omnidev.workspace.data.tools.DeviceAccessSetupPlan.Route
import org.junit.Assert.*
import org.junit.Test

class DeviceAccessSetupPlanTest {
    private fun access(key: String, route: Route, status: String = "DENIED", declaration: Boolean = true) =
        Access(key, key, key, status, route, declaration)

    @Test fun discoversDevelopmentGrantsInsteadOfUsingAFixedAliasList() {
        val entries = listOf(access("android.permission.NEW_DEVELOPMENT", Route.DEVELOPMENT),
            access("android.permission.CAMERA", Route.RUNTIME), access("normal", Route.INSTALL),
            access("signature", Route.ENTITLEMENT), access("removed", Route.UNSUPPORTED),
            access("ready", Route.RUNTIME, "GRANTED"))
        assertEquals(listOf("android.permission.NEW_DEVELOPMENT", "android.permission.CAMERA"), DeviceAccessSetupPlan.automaticPermissions(entries))
    }
    @Test fun automaticBackgroundGrantsFollowForegroundPermissions() {
        val entries = listOf(access(PermissionRequestPlan.BACKGROUND_LOCATION, Route.RUNTIME),
            access("android.permission.ACCESS_COARSE_LOCATION", Route.RUNTIME))
        assertEquals(listOf("android.permission.ACCESS_COARSE_LOCATION", PermissionRequestPlan.BACKGROUND_LOCATION), DeviceAccessSetupPlan.automaticPermissions(entries))
    }
    @Test fun foregroundDenialDoesNotReopenDialogsOrRequestDependentBackgroundAccess() {
        val entries = listOf(access("android.permission.ACCESS_FINE_LOCATION", Route.RUNTIME),
            access("android.permission.ACCESS_COARSE_LOCATION", Route.RUNTIME),
            access(PermissionRequestPlan.BACKGROUND_LOCATION, Route.RUNTIME), access("overlay", Route.SPECIAL, declaration = false))
        val first = DeviceAccessSetupPlan.nextStep(entries, emptySet(), 30)!!
        assertEquals("runtime_batch", first.key)
        assertFalse(PermissionRequestPlan.BACKGROUND_LOCATION in first.permissions)
        assertEquals("overlay", DeviceAccessSetupPlan.nextStep(entries, setOf(first.key) + first.permissions, 30)?.key)
    }
    @Test fun backgroundsAdvanceAfterAnActualForegroundGrant() {
        val entries = listOf(access("android.permission.ACCESS_COARSE_LOCATION", Route.RUNTIME, "GRANTED"),
            access(PermissionRequestPlan.BACKGROUND_LOCATION, Route.RUNTIME), access("overlay", Route.SPECIAL, declaration = false))
        assertEquals(PermissionRequestPlan.BACKGROUND_LOCATION, DeviceAccessSetupPlan.nextStep(entries, emptySet(), 30)?.key)
        assertEquals("overlay", DeviceAccessSetupPlan.nextStep(entries, setOf(PermissionRequestPlan.BACKGROUND_LOCATION), 30)?.key)
    }
    @Test fun specialDeclarationAndItsSetupRouteNeverOpenDuplicateScreens() {
        val entries = listOf(access("android.permission.SYSTEM_ALERT_WINDOW", Route.SPECIAL),
            access("overlay", Route.SPECIAL, declaration = false))
        assertEquals("overlay", DeviceAccessSetupPlan.nextStep(entries, emptySet(), 36)?.key)
        assertNull(DeviceAccessSetupPlan.nextStep(entries, setOf("overlay"), 36))
    }
    @Test fun protectedUnsupportedAndTierBlockedEntriesNeverBecomeDialogSteps() {
        val entries = listOf(access("signature", Route.ENTITLEMENT), access("development", Route.DEVELOPMENT),
            access("unsupported", Route.UNSUPPORTED), access("accessibility", Route.SPECIAL, "TIER_BLOCKED", false))
        assertNull(DeviceAccessSetupPlan.nextStep(entries, emptySet(), 30))
    }
    @Test fun successfulReadBackSkipsAlreadyGrantedSpecialAccess() {
        val entries = listOf(access("overlay", Route.SPECIAL, "GRANTED", false), access("vpn", Route.SPECIAL, declaration = false))
        assertEquals("vpn", DeviceAccessSetupPlan.nextStep(entries, emptySet(), 30)?.key)
        assertNull(DeviceAccessSetupPlan.nextStep(entries, setOf("vpn"), 30))
    }
    @Test fun android11AndAndroid16UseTheSupportedPermissionSets() {
        val entries = listOf(access("android.permission.READ_EXTERNAL_STORAGE", Route.RUNTIME),
            access("android.permission.READ_MEDIA_IMAGES", Route.RUNTIME), access("android.permission.READ_MEDIA_VISUAL_USER_SELECTED", Route.RUNTIME))
        assertEquals(listOf("android.permission.READ_EXTERNAL_STORAGE"), DeviceAccessSetupPlan.nextStep(entries, emptySet(), 30)?.permissions)
        assertEquals(setOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"), DeviceAccessSetupPlan.nextStep(entries, emptySet(), 36)?.permissions?.toSet())
    }
    @Test fun companionRuntimePermissionIsIncludedInTheSameSetup() {
        assertEquals(listOf("com.termux.permission.RUN_COMMAND"), DeviceAccessSetupPlan.nextStep(
            listOf(access("com.termux.permission.RUN_COMMAND", Route.RUNTIME)), emptySet(), 30)?.permissions)
    }
}
