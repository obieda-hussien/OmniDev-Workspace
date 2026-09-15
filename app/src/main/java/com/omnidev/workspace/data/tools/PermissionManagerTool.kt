package com.omnidev.workspace.data.tools

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ComponentName
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.omnidev.workspace.data.accessibility.AccessibilityStateManager
import com.omnidev.workspace.data.accessibility.OmniAccessibilityService
import com.omnidev.workspace.data.admin.OmniDeviceAdminReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PermissionManagerTool — capability bootstrap and audit layer for the agent.
 *
 * The ADMIN/PRO tiers can attempt grantable runtime permissions through Shizuku
 * first. Android signature/privileged permissions are still enforced by Android:
 * they require a system/OEM install (or an appropriate privileged/root path) and
 * are never falsely reported as granted just because a feature flag is enabled.
 */
object PermissionManagerTool {

    private const val RUNTIME_REQUEST_CODE = 0x4F60

    /** Common dangerous/runtime permissions declared by OmniDev. */
    private val runtimePermissionCandidates = listOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.WRITE_CALL_LOG,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.BODY_SENSORS,
        "android.permission.ACTIVITY_RECOGNITION",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH_ADVERTISE",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.NEARBY_WIFI_DEVICES"
    )

    /** Aliases make it practical for a model to request common capabilities. */
    private val aliases = mapOf(
        "camera" to Manifest.permission.CAMERA,
        "microphone" to Manifest.permission.RECORD_AUDIO,
        "mic" to Manifest.permission.RECORD_AUDIO,
        "fine_location" to Manifest.permission.ACCESS_FINE_LOCATION,
        "coarse_location" to Manifest.permission.ACCESS_COARSE_LOCATION,
        "contacts" to Manifest.permission.READ_CONTACTS,
        "calendar" to Manifest.permission.READ_CALENDAR,
        "phone" to Manifest.permission.CALL_PHONE,
        "sms" to Manifest.permission.READ_SMS,
        "notifications" to "android.permission.POST_NOTIFICATIONS"
    )

    /** Checks one permission, one special access, or the complete capability matrix. */
    fun checkPermission(context: Context, permission: String): String {
        val key = permission.lowercase().trim()
        if (key in setOf("all", "audit_all", "capabilities")) return auditAll(context)

        return when (key) {
            "vpn" -> if (VpnService.prepare(context) == null) "GRANTED" else "DENIED"
            "accessibility" -> if (isAccessibilityServiceEnabled(context)) "GRANTED" else "DENIED"
            "notification_listener" -> if (isNotificationListenerEnabled(context)) "GRANTED" else "DENIED"
            "usage_stats" -> if (hasUsageStatsPermission(context)) "GRANTED" else "DENIED"
            "overlay" -> if (Settings.canDrawOverlays(context)) "GRANTED" else "DENIED"
            "write_settings" -> if (Settings.System.canWrite(context)) "GRANTED" else "DENIED"
            "all_files" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) "GRANTED" else "DENIED"
            "battery_optimization" -> if (isIgnoringBatteryOptimizations(context)) "GRANTED" else "DENIED"
            "exact_alarms" -> if (canScheduleExactAlarms(context)) "GRANTED" else "DENIED"
            "install_unknown_apps" -> if (canRequestPackageInstalls(context)) "GRANTED" else "DENIED"
            "device_admin" -> if (OmniDeviceAdminReceiver.isAdminActive(context)) "GRANTED" else "DENIED"
            "background_location" -> standardStatus(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            else -> standardStatus(context, aliases[key] ?: permission)
        }
    }

    /**
     * Requests one capability or performs the maximum practical runtime bootstrap.
     * `permission=all`/`all_runtime`/`bootstrap_max` attempts Shizuku grants, then
     * asks Android for all still-missing declared runtime permissions in one batch.
     */
    suspend fun requestPermission(context: Context, permission: String): ToolExecutionResult {
        val key = permission.lowercase().trim()

        if (key in setOf("all", "all_runtime", "bootstrap_max")) {
            return requestAllRuntime(context)
        }

        val intent = when (key) {
            "vpn" -> VpnService.prepare(context)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "notification_listener" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "usage_stats" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            "write_settings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
            "all_files" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            } else null
            "battery_optimization" -> Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
            "exact_alarms" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
            } else null
            "install_unknown_apps" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            } else null
            "device_admin" -> {
                withContext(Dispatchers.Main) { OmniDeviceAdminReceiver.requestAdminActivation(context) }
                return ToolExecutionResult("Opened Device Admin activation. User approval is required by Android.")
            }
            else -> null
        }

        if (intent != null) {
            return launchIntent(context, intent, key)
        }

        val standardPermission = when (key) {
            "background_location" -> Manifest.permission.ACCESS_BACKGROUND_LOCATION
            else -> aliases[key] ?: permission
        }

        if (ContextCompat.checkSelfPermission(context, standardPermission) == PackageManager.PERMISSION_GRANTED) {
            return ToolExecutionResult("✅ Permission already granted: $standardPermission")
        }

        // On a Shizuku-enabled tier this can eliminate many runtime dialogs.
        val shizukuGranted = runCatching {
            ensurePermissionViaShizuku(standardPermission, context.packageName, context)
        }.getOrDefault(false)
        if (shizukuGranted && ContextCompat.checkSelfPermission(
                context,
                standardPermission
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return ToolExecutionResult("✅ Granted via Shizuku: $standardPermission")
        }

        val requested = PermissionRequestBridge.requestRuntimePermissions(
            arrayOf(standardPermission),
            RUNTIME_REQUEST_CODE
        )
        return if (requested) {
            ToolExecutionResult("Requested runtime permission: $standardPermission. Android is waiting for user approval.")
        } else {
            ToolExecutionResult(
                "USER_ACTION_REQUIRED: no foreground Activity is available to show the permission dialog for $standardPermission. Open OmniDev and retry.",
                isError = true
            )
        }
    }

    private suspend fun requestAllRuntime(context: Context): ToolExecutionResult {
        val declared = declaredPermissions(context)
        val candidates = runtimePermissionCandidates
            .filter { it in declared }
            .filterNot { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
            .distinct()

        if (candidates.isEmpty()) {
            return ToolExecutionResult(
                "✅ All currently requestable runtime permissions are granted.\n${specialAccessSummary(context)}"
            )
        }

        val grantedByShizuku = mutableListOf<String>()
        for (permission in candidates) {
            val granted = runCatching {
                ensurePermissionViaShizuku(permission, context.packageName, context)
            }.getOrDefault(false)
            if (granted && ContextCompat.checkSelfPermission(
                    context,
                    permission
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                grantedByShizuku += permission
            }
        }

        // Background location/sensors have staged Android flows and should never
        // be mixed into the same dialog with foreground permissions.
        val remaining = candidates
            .filterNot { it in grantedByShizuku }
            .filterNot { it == Manifest.permission.ACCESS_BACKGROUND_LOCATION || it == "android.permission.BODY_SENSORS_BACKGROUND" }

        val dialogStarted = if (remaining.isNotEmpty()) {
            PermissionRequestBridge.requestRuntimePermissions(
                remaining.toTypedArray(),
                RUNTIME_REQUEST_CODE
            )
        } else true

        return ToolExecutionResult(
            buildString {
                appendLine("Maximum runtime-permission bootstrap started.")
                appendLine("• Declared runtime candidates: ${candidates.size}")
                appendLine("• Granted through Shizuku this pass: ${grantedByShizuku.size}")
                appendLine("• Remaining Android runtime prompts: ${remaining.size}")
                if (remaining.isNotEmpty()) {
                    if (dialogStarted) appendLine("• Android permission dialog opened; user approval is required for the remainder.")
                    else appendLine("• USER_ACTION_REQUIRED: open OmniDev in foreground and retry to show the permission dialog.")
                }
                appendLine()
                append(specialAccessSummary(context))
                appendLine()
                append("Signature/system-only permissions are not forgeable by a normal APK; they become available only through the OEM/system/root/Shizuku paths Android actually permits.")
            }.trimEnd(),
            isError = remaining.isNotEmpty() && !dialogStarted
        )
    }

    private fun auditAll(context: Context): String {
        val declared = declaredPermissions(context)
        val granted = declared.count {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        val denied = declared.size - granted

        return buildString {
            appendLine("OmniDev permission/capability audit")
            appendLine("• Declared manifest permissions: ${declared.size}")
            appendLine("• Currently granted/checkable: $granted")
            appendLine("• Not currently granted: $denied")
            appendLine()
            appendLine(specialAccessSummary(context))
            appendLine()
            append("Use request_permission(permission='bootstrap_max') to attempt Shizuku grants and request all remaining runtime permissions. Special/system privileges still follow Android's mandatory user/OEM/root rules.")
        }.trimEnd()
    }

    private fun specialAccessSummary(context: Context): String = buildString {
        appendLine("Special access:")
        appendLine("• Accessibility: ${checkMark(isAccessibilityServiceEnabled(context))}")
        appendLine("• Notification listener: ${checkMark(isNotificationListenerEnabled(context))}")
        appendLine("• Usage stats: ${checkMark(hasUsageStatsPermission(context))}")
        appendLine("• Overlay: ${checkMark(Settings.canDrawOverlays(context))}")
        appendLine("• Write settings: ${checkMark(Settings.System.canWrite(context))}")
        appendLine("• All-files access: ${checkMark(Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager())}")
        appendLine("• Ignore battery optimizations: ${checkMark(isIgnoringBatteryOptimizations(context))}")
        appendLine("• Exact alarms: ${checkMark(canScheduleExactAlarms(context))}")
        appendLine("• Install unknown apps: ${checkMark(canRequestPackageInstalls(context))}")
        appendLine("• Device Admin: ${checkMark(OmniDeviceAdminReceiver.isAdminActive(context))}")
        append("• VPN consent: ${checkMark(VpnService.prepare(context) == null)}")
    }

    private fun checkMark(granted: Boolean): String = if (granted) "GRANTED" else "DENIED / USER ACTION"

    private fun standardStatus(context: Context, permission: String): String =
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"

    private fun declaredPermissions(context: Context): Set<String> = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
        info.requestedPermissions?.toSet().orEmpty()
    }.getOrDefault(emptySet())

    private suspend fun launchIntent(
        context: Context,
        intent: Intent,
        label: String
    ): ToolExecutionResult = withContext(Dispatchers.Main) {
        runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolExecutionResult("Opened Android settings/dialog for: $label. User approval is required where Android mandates it.")
        }.getOrElse { error ->
            ToolExecutionResult("Could not open settings for '$label': ${error.message}", isError = true)
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        if (AccessibilityStateManager.isServiceConnected.value) return true
        val expectedComponent = ComponentName(context, OmniAccessibilityService::class.java)
        val expectedShort = expectedComponent.flattenToShortString()
        val expectedFull = expectedComponent.flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').map { it.trim() }.any {
            it == expectedShort || it == expectedFull
        }
    }

    private fun isNotificationListenerEnabled(context: Context): Boolean {
        val pkgName = context.packageName
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return flat?.contains(pkgName) == true
    }

    private fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return false
        return alarm.canScheduleExactAlarms()
    }

    private fun canRequestPackageInstalls(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "check_permission",
            description = "Audit a specific permission/special access, or pass permission='all' to return the complete OmniDev capability matrix. Special keys: vpn, accessibility, notification_listener, usage_stats, overlay, write_settings, all_files, battery_optimization, exact_alarms, install_unknown_apps, device_admin, background_location. Common aliases such as camera, microphone, location, contacts, calendar, sms and notifications are accepted.",
            parameters = listOf(
                ToolParameter("permission", "string", "Permission name, alias, special key, or 'all'.", required = true)
            )
        ),
        ToolDefinition(
            name = "request_permission",
            description = "Request one permission/special access. Use permission='bootstrap_max' (or 'all') to attempt every declared runtime permission: Shizuku is tried first, then Android runtime dialogs are shown for the remainder. Signature/system-only privileges still require genuine OEM/system/root/Shizuku capability and are never spoofed.",
            parameters = listOf(
                ToolParameter("permission", "string", "Permission name, alias, special key, or bootstrap_max/all.", required = true)
            )
        )
    )
}
