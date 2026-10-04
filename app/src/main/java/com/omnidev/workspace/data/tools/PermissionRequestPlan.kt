package com.omnidev.workspace.data.tools

/** Android permission dependencies, independent of the platform for regression tests. */
internal object PermissionRequestPlan {
    const val BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION"
    const val BACKGROUND_SENSORS = "android.permission.BODY_SENSORS_BACKGROUND"
    const val BACKGROUND_HEALTH = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    val staged = setOf(BACKGROUND_LOCATION, BACKGROUND_SENSORS, BACKGROUND_HEALTH)

    fun supported(permission: String, sdk: Int): Boolean = when (permission) {
        "android.permission.WRITE_EXTERNAL_STORAGE" -> sdk <= 29
        "android.permission.READ_EXTERNAL_STORAGE" -> sdk <= 32
        "android.permission.BODY_SENSORS" -> sdk <= 35
        BACKGROUND_SENSORS -> sdk in 33..35
        BACKGROUND_LOCATION, "android.permission.ACTIVITY_RECOGNITION" -> sdk >= 29
        "android.permission.READ_PHONE_NUMBERS", "android.permission.ANSWER_PHONE_CALLS" -> sdk >= 26
        "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH_ADVERTISE", "android.permission.UWB_RANGING" -> sdk >= 31
        "android.permission.POST_NOTIFICATIONS", "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO",
        "android.permission.NEARBY_WIFI_DEVICES" -> sdk >= 33
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED" -> sdk >= 34
        "android.permission.RANGING", BACKGROUND_HEALTH -> sdk >= 36
        "android.permission.REQUEST_OBSERVE_DEVICE_UUID_PRESENCE" -> sdk >= 36
        "android.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE",
        "android.permission.REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND" -> sdk >= 31
        else -> !permission.startsWith("android.permission.health.") || sdk >= 36
    }

    // Health grants must reach the runtime/Health Connect controller, never generic app details.
    fun usesAppDetails(permission: String, sdk: Int): Boolean = permission == BACKGROUND_LOCATION && sdk >= 30

    fun foregroundBatch(permissions: Collection<String>, sdk: Int): List<String> {
        val result = permissions.filter { supported(it, sdk) && it !in staged }.toMutableSet()
        // Android 12+ ignores a fine-only location dialog. Include coarse even if granted.
        if ("android.permission.ACCESS_FINE_LOCATION" in result) result += "android.permission.ACCESS_COARSE_LOCATION"
        // Android 14+ lets users choose partial media access in this same dialog.
        if (sdk >= 34 && result.any { it in setOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO") }) {
            result += "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
        }
        return result.toList()
    }

    fun prerequisites(permission: String): Set<String> = when (permission) {
        BACKGROUND_LOCATION -> setOf("android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION")
        BACKGROUND_SENSORS -> setOf("android.permission.BODY_SENSORS")
        BACKGROUND_HEALTH -> setOf("android.permission.health.READ_HEART_RATE", "android.permission.health.READ_OXYGEN_SATURATION", "android.permission.health.READ_SKIN_TEMPERATURE")
        else -> emptySet()
    }
}
