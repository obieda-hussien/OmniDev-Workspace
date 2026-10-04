package com.omnidev.workspace.data.tools

import org.junit.Assert.*
import org.junit.Test

class AppOpAccessPlanTest {
    private val declarations = AppOpAccessPlan.entries.map { it.permission }.toSet()

    @Test fun workProfileCommandUsesTheExplicitUserAndApp() {
        val command = AppOpAccessPlan.command("appop_all_files", "com.omnidev.workspace.pro", 10, 30, declarations)
        assertEquals("cmd appops set --user 10 'com.omnidev.workspace.pro' 'MANAGE_EXTERNAL_STORAGE' 'allow'", command)
    }
    @Test fun resetRestoresDefaultRatherThanDenyingOrResettingEveryOperation() {
        val command = AppOpAccessPlan.command("reset_appop_overlay", "com.omnidev.workspace", 0, 30, declarations)
        assertEquals("cmd appops set --user 0 'com.omnidev.workspace' 'SYSTEM_ALERT_WINDOW' 'default'", command)
    }
    @Test fun callerCannotSupplyAnArbitraryOperationOrCommand() {
        for (key in listOf("appop_READ_SMS", "appop_overlay; id", "reset_reset_appop_overlay", "appop_camera")) {
            assertNull(AppOpAccessPlan.entry(key))
            rejects { AppOpAccessPlan.command(key, "com.omnidev.workspace", 0, 36, declarations) }
        }
    }
    @Test fun packageInputCannotBecomeShellCode() {
        for (name in listOf("pkg; id", "com.example'", "com.example\nreboot", "")) {
            rejects { AppOpAccessPlan.command("appop_overlay", name, 0, 36, declarations) }
        }
        rejects { AppOpAccessPlan.command("appop_overlay", "com.omnidev.workspace", -1, 36, declarations) }
    }
    @Test fun oldAndroidCannotReceiveUnsupportedStorageOrMediaOperations() {
        rejects { AppOpAccessPlan.command("appop_all_files", "com.omnidev.workspace", 0, 29, declarations) }
        rejects { AppOpAccessPlan.command("appop_manage_media", "com.omnidev.workspace", 0, 30, declarations) }
        assertTrue(AppOpAccessPlan.command("appop_manage_media", "com.omnidev.workspace", 0, 31, declarations).endsWith("'allow'"))
    }
    @Test fun aStrippedManifestCannotReceiveSpecialAccess() {
        for (entry in AppOpAccessPlan.entries) rejects {
            AppOpAccessPlan.command(entry.key, "com.omnidev.workspace.lite", 0, 36, emptySet())
        }
    }
    @Test fun apkInstallAccessIsAvailableOnlyFromAndroid8WithItsDeclaration() {
        rejects { AppOpAccessPlan.command("appop_install_packages", "com.omnidev.workspace", 0, 25, declarations) }
        assertTrue(AppOpAccessPlan.command("appop_install_packages", "com.omnidev.workspace", 10, 26, declarations)
            .contains("--user 10 'com.omnidev.workspace' 'REQUEST_INSTALL_PACKAGES' 'allow'"))
        assertFalse(AppOpAccessPlan.verified("appop_install_packages", 0, false))
    }
    @Test fun exactAlarmAccessIsAvailableOnlyFromAndroid12AndMustBeEffective() {
        rejects { AppOpAccessPlan.command("appop_exact_alarms", "com.omnidev.workspace", 0, 30, declarations) }
        assertTrue(AppOpAccessPlan.command("appop_exact_alarms", "com.omnidev.workspace", 0, 31, declarations).contains("'SCHEDULE_EXACT_ALARM'"))
        assertFalse(AppOpAccessPlan.verified("appop_exact_alarms", 0, false))
        assertTrue(AppOpAccessPlan.verified("appop_exact_alarms", 0, true))
    }
    @Test fun foregroundOrUnknownModesCannotProveAnAllowGrant() {
        for (rawMode in listOf(null, 1, 2, 3, 4)) assertFalse(AppOpAccessPlan.verified("appop_overlay", rawMode, true))
        assertFalse(AppOpAccessPlan.verified("appop_overlay", 0, false))
        assertTrue(AppOpAccessPlan.verified("appop_overlay", 0, true))
    }
    @Test fun restoredDefaultCanStillHaveEffectiveAccess() {
        assertTrue(AppOpAccessPlan.verified("reset_appop_overlay", 3, true))
        assertTrue(AppOpAccessPlan.verified("reset_appop_overlay", 3, false))
        assertFalse(AppOpAccessPlan.verified("reset_appop_overlay", 0, true))
        assertFalse(AppOpAccessPlan.verified("appop_unknown", 0, true))
    }
    private fun rejects(action: () -> Unit) {
        try { action(); fail("Invalid command must be rejected before execution") }
        catch (_: IllegalArgumentException) { }
    }
}
