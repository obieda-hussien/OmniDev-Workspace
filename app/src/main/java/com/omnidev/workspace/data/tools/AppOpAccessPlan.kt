package com.omnidev.workspace.data.tools

/** Fixed operations for OmniDev's own package; no arbitrary app, operation or shell input. */
internal object AppOpAccessPlan {
    data class Entry(val key: String, val operation: String, val permission: String, val specialKey: String, val minSdk: Int = 24)
    val entries = listOf(
        Entry("appop_overlay", "SYSTEM_ALERT_WINDOW", "android.permission.SYSTEM_ALERT_WINDOW", "overlay"),
        Entry("appop_usage_stats", "GET_USAGE_STATS", "android.permission.PACKAGE_USAGE_STATS", "usage_stats"),
        Entry("appop_write_settings", "WRITE_SETTINGS", "android.permission.WRITE_SETTINGS", "write_settings"),
        Entry("appop_all_files", "MANAGE_EXTERNAL_STORAGE", "android.permission.MANAGE_EXTERNAL_STORAGE", "all_files", 30),
        Entry("appop_manage_media", "MANAGE_MEDIA", "android.permission.MANAGE_MEDIA", "manage_media", 31)
    )
    fun entry(key: String): Entry? = entries.firstOrNull { it.key == key.removePrefix("reset_") }
    fun mode(key: String): String = if (key.startsWith("reset_")) "default" else "allow"
    fun command(key: String, packageName: String, userId: Int, sdk: Int, declarations: Set<String>): String {
        val entry = requireNotNull(entry(key)) { "Unsupported special-access operation" }
        require(sdk >= entry.minSdk && entry.permission in declarations) { "Operation unavailable in this APK or Android version" }
        require(userId >= 0 && packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))) { "Invalid app identity" }
        return "cmd appops set --user $userId '$packageName' '${entry.operation}' '${mode(key)}'"
    }
}
