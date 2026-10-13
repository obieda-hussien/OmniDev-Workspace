package com.omnidev.system

/** Manufacturer-facing contract: extending it requires a reviewed service implementation. */
object SystemActionPolicy {
    val actions = setOf("status", "settings_read", "settings_write", "volume_read", "volume_set", "wifi_read", "wifi_set")
    val readableSettings = setOf("screen_brightness", "screen_brightness_mode", "screen_off_timeout", "accelerometer_rotation")
    val writableRanges = mapOf("screen_brightness" to 0..255, "screen_brightness_mode" to 0..1,
        "screen_off_timeout" to 15_000..600_000, "accelerometer_rotation" to 0..1)
    fun mutation(action: String) = action in setOf("settings_write", "volume_set", "wifi_set")
    fun validate(action: String, arguments: Map<String, Any?>): String? {
        if (action !in actions) return "Unknown system action"
        val keys = when (action) {
            "settings_read" -> setOf("key")
            "settings_write" -> setOf("key", "value")
            "volume_read" -> setOf("stream")
            "volume_set" -> setOf("stream", "value")
            "wifi_set" -> setOf("enabled")
            else -> emptySet()
        }
        if (arguments.keys.any { it !in keys }) return "Unsupported arguments"
        if (arguments.values.any { it !is String && it !is Int && it !is Boolean }) return "Unsupported argument type"
        if (arguments.values.filterIsInstance<String>().any { it.length > 128 }) return "Argument too large"
        if (action.startsWith("settings_")) {
            val key = arguments["key"] as? String ?: return "Missing setting key"
            if (key !in readableSettings) return "Setting is outside the allowed scope"
            if (action == "settings_write") {
                val value = arguments["value"] as? Int ?: return "Value must be an integer"
                if (value !in writableRanges.getValue(key)) return "Setting value is out of range"
            }
        }
        if (action.startsWith("volume_")) {
            if ((arguments["stream"] as? Int) !in setOf(3, 4)) return "Only media and alarm volume are supported"
            if (action == "volume_set" && arguments["value"] !is Int) return "Volume must be an integer"
        }
        if (action == "wifi_set" && arguments["enabled"] !is Boolean) return "enabled must be a boolean"
        return null
    }
}
