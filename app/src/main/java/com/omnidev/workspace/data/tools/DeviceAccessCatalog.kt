package com.omnidev.workspace.data.tools

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.CrossProfileApps
import android.provider.MediaStore
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import com.omnidev.workspace.core.policy.TierPolicyHolder
import com.omnidev.workspace.data.accessibility.AccessibilityStateManager
import com.omnidev.workspace.data.accessibility.OmniAccessibilityService
import com.omnidev.workspace.data.admin.OmniDeviceAdminReceiver
import com.omnidev.workspace.data.input.OmniInputMethodService
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import com.omnidev.workspace.ui.assistant.AssistantSettings

/** Special access is independent of manifest grants and never inferred from a build name. */
object DeviceAccessCatalog {
    data class Entry(val key: String, val title: String, val detail: String, val minSdk: Int = 24)
    val entries = listOf(
        Entry("assistant", "Default assistant", "Home gesture, screen context and screenshots chosen in assistant settings."),
        Entry("accessibility", "Control apps on screen", "Inspect windows, navigate, scroll and fill ordinary fields."),
        Entry("overlay", "Floating bubble", "Display Omni over other apps."),
        Entry("notification_listener", "Notification and media access", "Read notifications and control supported media sessions."),
        Entry("usage_stats", "App usage", "Inspect foreground apps and usage history."),
        Entry("write_settings", "Device settings", "Change supported brightness, rotation and audio settings."),
        Entry("notification_policy", "Do Not Disturb", "Manage notification interruption policy."),
        Entry("all_files", "Shared file storage", "Manage shared storage; private app folders still have Android restrictions.", 30),
        Entry("manage_media", "Manage shared media", "Reduce media edit/delete prompts where Android permits.", 31),
        Entry("install_unknown_apps", "Install APKs", "Request package installation through Android's installer.", 26),
        Entry("exact_alarms", "Exact reminders", "Schedule time-sensitive reminders.", 31),
        Entry("battery_optimization", "Background reliability", "Request exclusion from battery optimization; OEM limits may still apply."),
        Entry("input_method", "Omni input method", "Enable and select Omni keyboard for ordinary text entry."),
        Entry("vpn", "Local VPN", "Local traffic tooling; Android permits one VPN at a time."),
        Entry("device_admin", "Device administrator", "Device lock and supported policies; this does not make Omni Device Owner."),
        Entry("cross_profile", "Work-profile interaction", "Android-managed consent for this same app installed in another eligible profile.", 30)
    )
    private val requiredPermissions = mapOf(
        "overlay" to "android.permission.SYSTEM_ALERT_WINDOW", "usage_stats" to "android.permission.PACKAGE_USAGE_STATS",
        "write_settings" to "android.permission.WRITE_SETTINGS", "notification_policy" to "android.permission.ACCESS_NOTIFICATION_POLICY",
        "all_files" to "android.permission.MANAGE_EXTERNAL_STORAGE", "manage_media" to "android.permission.MANAGE_MEDIA",
        "install_unknown_apps" to "android.permission.REQUEST_INSTALL_PACKAGES",
        "battery_optimization" to "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        "cross_profile" to "android.permission.INTERACT_ACROSS_PROFILES"
    )
    private fun hasComponent(context: Context, setting: String, component: ComponentName): Boolean {
        val raw = Settings.Secure.getString(context.contentResolver, setting).orEmpty()
        return raw.split(':').any { ComponentName.unflattenFromString(it) == component }
    }
    internal fun setupKey(permission: String): String? = requiredPermissions.entries.firstOrNull { it.value == permission }?.key
        ?: if (permission in setOf("android.permission.SCHEDULE_EXACT_ALARM", "android.permission.USE_EXACT_ALARM")) "exact_alarms" else null
    fun status(context: Context, key: String): String {
        return runCatching {
            val policy = TierPolicyHolder.current
            val entry = entries.firstOrNull { it.key == key }
            if (entry != null && Build.VERSION.SDK_INT < entry.minSdk) return "NOT_SUPPORTED"
            if (key in setOf("assistant", "accessibility", "overlay", "input_method", "device_admin") && !policy.allowAccessibility) return "TIER_BLOCKED"
            val declared = PermissionManagerTool.declaredPermissions(context)
            if (key == "exact_alarms" && declared.none { it in setOf("android.permission.SCHEDULE_EXACT_ALARM", "android.permission.USE_EXACT_ALARM") }) return "NOT_DECLARED"
            requiredPermissions[key]?.let { if (it !in declared) return "NOT_DECLARED" }
            val service = when (key) {
                "accessibility" -> OmniAccessibilityService::class.java
                "notification_listener" -> AgentNotificationService::class.java
                "input_method" -> OmniInputMethodService::class.java
                "vpn" -> com.omnidev.workspace.data.network.OmniDevVpnService::class.java
                else -> null
            }
            if (service != null && runCatching {
                    @Suppress("DEPRECATION")
                    context.packageManager.getServiceInfo(ComponentName(context, service), 0)
                }.isFailure) return "NOT_DECLARED"
            if (key in setOf("shizuku", "rish") && !policy.allowShizuku) return "TIER_BLOCKED"
            if (key == "system" && !policy.allowSystemIntegration) return "TIER_BLOCKED"
            val granted = when (key) {
                "assistant" -> AssistantSettings.isSelected(context)
                "accessibility" -> AccessibilityStateManager.isServiceConnected.value // Enabled but unbound cannot execute.
                "notification_listener" -> hasComponent(context, "enabled_notification_listeners", ComponentName(context, AgentNotificationService::class.java))
                "input_method" -> hasComponent(context, Settings.Secure.DEFAULT_INPUT_METHOD, ComponentName(context, OmniInputMethodService::class.java))
                "overlay" -> Settings.canDrawOverlays(context)
                "write_settings" -> Settings.System.canWrite(context)
                "notification_policy" -> context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
                "all_files" -> Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()
                "manage_media" -> Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(context)
                "install_unknown_apps" -> Build.VERSION.SDK_INT >= 26 && context.packageManager.canRequestPackageInstalls()
                "exact_alarms" -> Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
                "battery_optimization" -> context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
                "vpn" -> VpnService.prepare(context) == null
                "device_admin" -> OmniDeviceAdminReceiver.isAdminActive(context)
                "device_owner" -> OmniDeviceAdminReceiver.isDeviceOwner(context)
                "profile_owner" -> context.getSystemService(DevicePolicyManager::class.java)?.isProfileOwnerApp(context.packageName) == true
                "cross_profile" -> Build.VERSION.SDK_INT >= 30 && context.getSystemService(CrossProfileApps::class.java)?.canInteractAcrossProfiles() == true
                "system" -> policy.allowSystemIntegration && android.os.Process.myUid() % 100000 == 1000
                "shizuku" -> policy.allowShizuku && ShizukuCommandTool.isAvailable() && ShizukuCommandTool.hasPermission()
                "rish" -> policy.allowShizuku && PrivilegedExecutionManager.isRishReady()
                "root" -> {
                    if (!policy.allowRoot) return "TIER_BLOCKED"
                    return when (PrivilegedExecutionManager.cachedRootAvailable()) {
                        true -> "GRANTED"
                        false -> "UNAVAILABLE"
                        null -> "NOT_PROBED"
                    }
                }
                "usage_stats" -> {
                    val ops = context.getSystemService(AppOpsManager::class.java)
                    @Suppress("DEPRECATION")
                    ops?.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
                }
                else -> return "UNKNOWN_CAPABILITY"
            }
            if (granted) "GRANTED" else if (key == "accessibility" && hasComponent(context,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, ComponentName(context, OmniAccessibilityService::class.java))) "ENABLED_NOT_CONNECTED" else "DENIED"
        }.getOrDefault("UNAVAILABLE")
    }

    fun intent(context: Context, key: String): Intent? {
        val pkg = Uri.parse("package:${context.packageName}")
        return when (key) {
            "assistant" -> AssistantSettings.intent(context)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "notification_listener" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "usage_stats" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            "write_settings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg)
            "notification_policy" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            "all_files" -> if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg) else null
            "manage_media" -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, pkg) else null
            "install_unknown_apps" -> if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg) else null
            "exact_alarms" -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg) else null
            "battery_optimization" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            "input_method" -> Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            "vpn" -> VpnService.prepare(context)
            "device_admin" -> Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN, OmniDeviceAdminReceiver.getComponentName(context))
            "cross_profile" -> if (Build.VERSION.SDK_INT >= 30) context.getSystemService(CrossProfileApps::class.java)?.let {
                if (it.canRequestInteractAcrossProfiles()) it.createRequestInteractAcrossProfilesIntent() else null
            } else null
            else -> null
        }
    }

    fun summary(context: Context): String = buildString {
        appendLine("Android ${Build.VERSION.SDK_INT}; tier ${TierPolicyHolder.current.tier}; app UID ${android.os.Process.myUid()}")
        for (entry in entries) appendLine("${entry.key}: ${status(context, entry.key)}")
        for (key in listOf("shizuku", "rish", "root", "system", "device_owner", "profile_owner")) appendLine("$key: ${status(context, key)}")
        if (TierPolicyHolder.current.allowShizuku && ShizukuCommandTool.hasPermission()) {
            val uid = runCatching { rikka.shizuku.Shizuku.getUid() }.getOrNull()
            appendLine("Shizuku server UID: ${uid ?: "unknown"} (0=root, 2000=ADB shell; per-operation restrictions still apply)")
        }
        if (Build.VERSION.SDK_INT >= 26) {
            val count = runCatching {
                @Suppress("DEPRECATION")
                context.getSystemService(android.companion.CompanionDeviceManager::class.java)?.associations?.size ?: 0
            }.getOrNull()
            appendLine("CompanionDeviceManager associations: ${count ?: "unavailable"}; companion grants require an association and implemented device integration.")
        }
        append("OmniLink uses per-peer capabilities and grants. Connected apps, Termux, document providers and browser sessions retain their own authorization. Screen capture of protected windows is unavailable.")
    }
}
