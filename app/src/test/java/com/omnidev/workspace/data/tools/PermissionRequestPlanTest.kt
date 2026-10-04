package com.omnidev.workspace.data.tools

import org.junit.Assert.*
import org.junit.Test

class PermissionRequestPlanTest {
    private val prefix = "android.permission."
    @Test fun android11KeepsLegacyStorageInsteadOfUnsupportedMediaPermissions() {
        val batch = PermissionRequestPlan.foregroundBatch(listOf(prefix + "READ_EXTERNAL_STORAGE", prefix + "WRITE_EXTERNAL_STORAGE", prefix + "READ_MEDIA_IMAGES", prefix + "POST_NOTIFICATIONS"), 30)
        assertEquals(listOf(prefix + "READ_EXTERNAL_STORAGE"), batch)
    }
    @Test fun android10StillRequestsWritableLegacyStorage() {
        assertTrue(PermissionRequestPlan.supported(prefix + "WRITE_EXTERNAL_STORAGE", 29))
        assertFalse(PermissionRequestPlan.supported(prefix + "WRITE_EXTERNAL_STORAGE", 30))
    }
    @Test fun android12FineLocationRequestIncludesCoarseInSameDialog() {
        assertEquals(setOf(prefix + "ACCESS_FINE_LOCATION", prefix + "ACCESS_COARSE_LOCATION"),
            PermissionRequestPlan.foregroundBatch(listOf(prefix + "ACCESS_FINE_LOCATION"), 31).toSet())
    }
    @Test fun backgroundGrantsNeverMixWithForegroundBatch() {
        for (sdk in listOf(29, 30, 33, 34, 36)) {
            val batch = PermissionRequestPlan.foregroundBatch(PermissionRequestPlan.staged + (prefix + "CAMERA"), sdk)
            assertEquals(listOf(prefix + "CAMERA"), batch)
        }
    }
    @Test fun android13MediaReplacesLegacyStorage() {
        assertFalse(PermissionRequestPlan.supported(prefix + "READ_EXTERNAL_STORAGE", 33))
        assertEquals(listOf(prefix + "READ_MEDIA_VIDEO"), PermissionRequestPlan.foregroundBatch(listOf(prefix + "READ_MEDIA_VIDEO"), 33))
    }
    @Test fun android14IncludesSelectedMediaAccess() {
        val batch = PermissionRequestPlan.foregroundBatch(listOf(prefix + "READ_MEDIA_IMAGES", prefix + "READ_MEDIA_VIDEO"), 34)
        assertTrue(prefix + "READ_MEDIA_VISUAL_USER_SELECTED" in batch)
        assertEquals(3, batch.size)
    }
    @Test fun notificationAndNearbyGrantsAreExcludedOnOldAndroid() {
        for (name in listOf("POST_NOTIFICATIONS", "NEARBY_WIFI_DEVICES")) {
            assertFalse(PermissionRequestPlan.supported(prefix + name, 32))
            assertTrue(PermissionRequestPlan.supported(prefix + name, 33))
        }
    }
    @Test fun android16SensorMigrationDoesNotOfferLegacySensorGrants() {
        assertTrue(PermissionRequestPlan.supported(prefix + "BODY_SENSORS", 35))
        assertFalse(PermissionRequestPlan.supported(prefix + "BODY_SENSORS", 36))
        assertFalse(PermissionRequestPlan.supported(PermissionRequestPlan.BACKGROUND_SENSORS, 36))
        assertTrue(PermissionRequestPlan.supported(prefix + "health.READ_HEART_RATE", 36))
        assertFalse(PermissionRequestPlan.supported(prefix + "health.READ_HEART_RATE", 35))
    }
    @Test fun backgroundDependenciesUseActualForegroundGrantNames() {
        assertEquals(setOf(prefix + "ACCESS_COARSE_LOCATION", prefix + "ACCESS_FINE_LOCATION"), PermissionRequestPlan.prerequisites(PermissionRequestPlan.BACKGROUND_LOCATION))
        assertEquals(setOf(prefix + "BODY_SENSORS"), PermissionRequestPlan.prerequisites(PermissionRequestPlan.BACKGROUND_SENSORS))
        assertTrue(prefix + "health.READ_HEART_RATE" in PermissionRequestPlan.prerequisites(PermissionRequestPlan.BACKGROUND_HEALTH))
        assertTrue(PermissionRequestPlan.prerequisites(prefix + "CAMERA").isEmpty())
    }
    @Test fun rangingIsAvailableOnlyOnAndroid16OrHigher() {
        assertFalse(PermissionRequestPlan.supported(prefix + "RANGING", 35))
        assertTrue(PermissionRequestPlan.supported(prefix + "RANGING", 36))
    }
    @Test fun companionAppPermissionIsNotDiscardedAsUnknownAndroidAlias() {
        assertEquals(listOf("com.termux.permission.RUN_COMMAND"), PermissionRequestPlan.foregroundBatch(listOf("com.termux.permission.RUN_COMMAND"), 30))
    }
    @Test fun android16HealthBackgroundRequestReachesRuntimeController() {
        assertFalse(PermissionRequestPlan.usesAppDetails(PermissionRequestPlan.BACKGROUND_HEALTH, 36))
        assertFalse(PermissionRequestPlan.usesAppDetails(PermissionRequestPlan.BACKGROUND_SENSORS, 33))
        assertTrue(PermissionRequestPlan.usesAppDetails(PermissionRequestPlan.BACKGROUND_LOCATION, 30))
        assertFalse(PermissionRequestPlan.usesAppDetails(PermissionRequestPlan.BACKGROUND_LOCATION, 29))
    }
    @Test fun companionPresenceDeclarationsRespectTheirPlatformBoundaries() {
        assertFalse(PermissionRequestPlan.supported(prefix + "REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE", 30))
        assertTrue(PermissionRequestPlan.supported(prefix + "REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE", 31))
        assertFalse(PermissionRequestPlan.supported(prefix + "REQUEST_OBSERVE_DEVICE_UUID_PRESENCE", 35))
        assertTrue(PermissionRequestPlan.supported(prefix + "REQUEST_OBSERVE_DEVICE_UUID_PRESENCE", 36))
    }
}
