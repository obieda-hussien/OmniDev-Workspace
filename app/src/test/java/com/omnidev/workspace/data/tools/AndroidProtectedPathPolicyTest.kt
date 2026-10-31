package com.omnidev.workspace.data.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidProtectedPathPolicyTest {
    @Test fun systemRootAndPrivilegedAppsUseShellRoute() {
        assertTrue(AndroidProtectedPathPolicy.requiresShell("/system"))
        assertTrue(AndroidProtectedPathPolicy.requiresShell("/system/"))
        assertTrue(AndroidProtectedPathPolicy.requiresShell("/system/build.prop"))
        assertTrue(AndroidProtectedPathPolicy.requiresShell("/system/priv-app/Settings/Settings.apk"))
    }

    @Test fun systemExtensionAndDataNamespacesUseShellRoute() {
        assertTrue(AndroidProtectedPathPolicy.requiresShell("/system_ext/priv-app/"))
        assertTrue(AndroidProtectedPathPolicy.requiresShell("/data"))
        assertTrue(AndroidProtectedPathPolicy.requiresShell("/data/app/com.example/base.apk"))
        assertTrue(AndroidProtectedPathPolicy.requiresShell("/data/media/0/Android/obb/com.example/main.obb"))
    }

    @Test fun androidObbAndDataDirectoriesUseShellRouteWithoutPrefixCollisions() {
        assertTrue(AndroidProtectedPathPolicy.requiresShell("/storage/emulated/0/Android/obb/com.example"))
        assertTrue(AndroidProtectedPathPolicy.requiresShell("/storage/emulated/0/Android/data/com.example"))
        assertFalse(AndroidProtectedPathPolicy.requiresShell("/storage/emulated/0/Android/obbish"))
        assertFalse(AndroidProtectedPathPolicy.requiresShell("/systematic/build.prop"))
        assertFalse(AndroidProtectedPathPolicy.requiresShell("/data_backup/file"))
    }

    @Test fun ordinaryWorkspacePathsRemainEligibleForDirectAccess() {
        assertFalse(AndroidProtectedPathPolicy.requiresShell("/storage/emulated/0/Documents/notes.txt"))
        assertFalse(AndroidProtectedPathPolicy.requiresShell("/data_local/tmp/file"))
    }
}
